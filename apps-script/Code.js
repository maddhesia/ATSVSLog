/** ATSVSLog — Milestone 14 company-facing Daily Report projection.
 *
 * M14 boundary:
 *   Raw cloud ledgers (Beta1Test DB)
 *       -> Apps Script report derivation
 *       -> separate company-facing Nautanwa Sales workbook projection
 *       -> Android REPORT read path remains available
 *
 * IMPORTANT: Keep the recorder/backend spreadsheet and reporting workbook
 * separate. SPREADSHEET_ID continues to point to Beta1Test DB;
 * REPORT_SPREADSHEET_ID points to Nautanwa Sales.
 *
 * Historical reporting uses the Type/Brand recorded on each Transactions row.
 * MASTER_RECLASSIFY changes current catalogue ownership only and never moves
 * an already-recorded historical sale between American Tourister and Kamiliant.
 *
 * The company-facing workbook is treated as a derived presentation projection.
 * Raw ledger writes remain authoritative. If projection fails after a raw
 * mutation succeeds, the mutation still returns success and the projection
 * failure is recorded in backend Diagnostics for recovery.
 */

const API_VERSION = 1;
const SHEET_TRANSACTIONS = 'Transactions';
const SHEET_WALK_INS = 'WalkIns';
const SHEET_DAILY_REPORT = 'Daily Report';
const SHEET_MASTERS = 'Masters';
const SHEET_DIAGNOSTICS = 'Diagnostics';
const SHEET_MASTER_CORRECTIONS = 'MasterCorrections';

const REPORT_STORE_NAME = 'American Tourister Super Value Store';
const REPORT_STORE_LOCATION = 'Nautanwa';

function doGet(e) {
  try {
    authenticate_(e);

    const op = String(
      (e && e.parameter && e.parameter.op) || 'health'
    ).toLowerCase();

    if (op === 'health') {
      return json_(success_('SUCCESS', 'ATSVSLog API healthy', {
        backend: 'apps-script',
        environment: 'BETA',
        apiVersion: API_VERSION
      }));
    }

    if (op === 'masters') {
      return json_(handleMasters_());
    }

    return json_(failure_(
      'NOT_FOUND',
      'Unknown GET operation',
      { op: op }
    ));
  } catch (err) {
    return json_(errorResponse_(err));
  }
}

function doPost(e) {
  try {
    authenticate_(e);

    if (!e || !e.postData || !e.postData.contents) {
      return json_(failure_(
        'INVALID_BODY',
        'POST body is required',
        {}
      ));
    }

    const request = JSON.parse(e.postData.contents);
    validateEnvelope_(request);

    if (request.action === 'SYNC') {
      return json_(handleSync_(request));
    }

    if (request.action === 'REPORT') {
      return json_(handleReport_(request));
    }

    return json_(failure_(
      'INVALID_ACTION',
      'Unsupported POST action',
      { action: request.action }
    ));
  } catch (err) {
    return json_(errorResponse_(err));
  }
}

function handleSync_(request) {
  const payload = request.payload;

  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    return failure_(
      'VALIDATION_ERROR',
      'payload is required',
      {}
    );
  }

  if (payload.eventType === 'SALE') {
    return handleSale_(request, payload);
  }

  if (payload.eventType === 'SALE_CORRECTION') {
    return handleSaleCorrection_(request, payload);
  }

  if (payload.eventType === 'WALK_IN') {
    return handleWalkIn_(request, payload);
  }

  if (payload.eventType === 'MASTER') {
    return handleMasterMutation_(request, payload);
  }

  if (payload.eventType === 'MASTER_RECLASSIFY') {
    return handleMasterReclassification_(request, payload);
  }

  return failure_(
    'VALIDATION_ERROR',
    'Unsupported payload.eventType',
    { eventType: payload.eventType }
  );
}

function handleSale_(request, payload) {
  requireString_(payload.eventUuid, 'eventUuid');
  requireString_(payload.transactionUuid, 'transactionUuid');
  requireDate_(payload.transactionDate, 'transactionDate');
  requireString_(payload.completedAt, 'completedAt');

  if (!Array.isArray(payload.items) || payload.items.length === 0) {
    return failure_(
      'VALIDATION_ERROR',
      'SALE requires at least one item',
      {}
    );
  }

  const lock = LockService.getScriptLock();
  lock.waitLock(15000);

  try {
    const ss = spreadsheet_();

    const sheet = ensureSheetWithHeaders_(
      ss,
      SHEET_TRANSACTIONS,
      [
        'eventUuid',
        'requestId',
        'transactionUuid',
        'transactionDate',
        'completedAt',
        'itemUuid',
        'type',
        'brand',
        'model',
        'size',
        'colour',
        'sellingPrice',
        'receivedAt'
      ]
    );

    const values = sheet.getDataRange().getValues();

    if (findValue_(values, 0, payload.eventUuid).found) {
      projectDailyReportBestEffort_(ss, request, payload.transactionDate, payload.eventUuid);
      return success_(
        'IDEMPOTENT_SUCCESS',
        'Event already processed',
        {
          eventUuid: payload.eventUuid,
          insertedRows: 0
        }
      );
    }

    const seenItemUuids = {};

    payload.items.forEach(function(item) {
      validateSaleItem_(item);

      if (seenItemUuids[item.itemUuid]) {
        throw new Error('DUPLICATE_ITEM_UUID');
      }

      seenItemUuids[item.itemUuid] = true;
    });

    const receivedAt = new Date();

    const rows = payload.items.map(function(item) {
      return [
        payload.eventUuid,
        request.requestId,
        payload.transactionUuid,
        payload.transactionDate,
        payload.completedAt,
        item.itemUuid,
        item.type,
        item.brand,
        item.model,
        item.size,
        item.colour,
        item.sellingPrice,
        receivedAt
      ];
    });

    sheet
      .getRange(
        sheet.getLastRow() + 1,
        1,
        rows.length,
        rows[0].length
      )
      .setValues(rows);

    ensureDiagnosticsSheet_(ss).appendRow([
      receivedAt,
      request.requestId,
      payload.eventUuid,
      'SALE',
      'SUCCESS',
      rows.length,
      ''
    ]);

    projectDailyReportBestEffort_(ss, request, payload.transactionDate, payload.eventUuid);

    return success_(
      'SUCCESS',
      'SALE accepted',
      {
        eventUuid: payload.eventUuid,
        transactionUuid: payload.transactionUuid,
        insertedRows: rows.length
      }
    );
  } finally {
    lock.releaseLock();
  }
}

/**
 * M12-2 SALE_CORRECTION.
 *
 * Rewrites the existing raw Transactions row for one item after a user
 * corrects a previously completed sale. This is deliberately different from
 * SALE: the original SALE event may already have reached the cloud and failed
 * only because of the Model ownership conflict, so appending another SALE row
 * would duplicate merchandise.
 *
 * The canonical Masters catalogue is never changed by this operation.
 * Diagnostics records the correction event for audit/idempotency.
 */
function handleSaleCorrection_(request, payload) {
  requireString_(payload.eventUuid, 'eventUuid');
  requireString_(payload.transactionUuid, 'transactionUuid');
  requireDate_(payload.transactionDate, 'transactionDate');
  requireString_(payload.completedAt, 'completedAt');

  if (!payload.item || typeof payload.item !== 'object' || Array.isArray(payload.item)) {
    return failure_(
      'VALIDATION_ERROR',
      'SALE_CORRECTION requires item',
      {}
    );
  }

  const item = payload.item;
  requireString_(item.itemUuid, 'item.itemUuid');
  requireString_(item.type, 'item.type');
  requireString_(item.brand, 'item.brand');
  requireString_(item.model, 'item.model');
  requireString_(item.size, 'item.size');
  requireString_(item.colour, 'item.colour');
  requireInteger_(item.sellingPrice, 'item.sellingPrice');

  const lock = LockService.getScriptLock();
  lock.waitLock(15000);

  try {
    const ss = spreadsheet_();
    const sheet = ensureSheetWithHeaders_(
      ss,
      SHEET_TRANSACTIONS,
      [
        'eventUuid',
        'requestId',
        'transactionUuid',
        'transactionDate',
        'completedAt',
        'itemUuid',
        'type',
        'brand',
        'model',
        'size',
        'colour',
        'sellingPrice',
        'receivedAt'
      ]
    );
    const diagnostics = ensureDiagnosticsSheet_(ss);
    const diagnosticValues = diagnostics.getDataRange().getValues();

    if (findValue_(diagnosticValues, 2, payload.eventUuid).found) {
      return success_(
        'IDEMPOTENT_SUCCESS',
        'SALE_CORRECTION already processed',
        { eventUuid: payload.eventUuid, updatedRows: 0 }
      );
    }

    const values = sheet.getDataRange().getValues();
    const rows = values.length <= 1 ? [] : values.slice(1);
    const rowIndex = rows.findIndex(function(row) {
      return String(row[5] || '').trim() === item.itemUuid &&
        String(row[2] || '').trim() === payload.transactionUuid;
    });

    if (rowIndex < 0) {
      return failure_(
        'NOT_FOUND',
        'SALE_CORRECTION item was not found',
        {
          eventUuid: payload.eventUuid,
          transactionUuid: payload.transactionUuid,
          itemUuid: item.itemUuid
        }
      );
    }

    const masterValues = readDataRows_(ss, SHEET_MASTERS);
    const modelKey = normalizeMasterValue_(item.model);
    const modelRows = masterValues.filter(function(row) {
      return normalizeMasterValue_(row[2]) === modelKey;
    });

    const conflicting = modelRows.find(function(row) {
      return normalizeMasterValue_(row[0]) !== normalizeMasterValue_(item.type) ||
        normalizeMasterValue_(row[1]) !== normalizeMasterValue_(item.brand);
    });

    if (conflicting) {
      return failure_(
        'MASTER_CONFLICT',
        'Corrected sale still conflicts with the canonical Model owner',
        {
          eventUuid: payload.eventUuid,
          model: item.model,
          requestedType: item.type,
          requestedBrand: item.brand,
          canonicalType: String(conflicting[0] || ''),
          canonicalBrand: String(conflicting[1] || ''),
          transactionUuid: payload.transactionUuid
        }
      );
    }

    const sourceRow = rowIndex + 2;
    const previousTransactionDate = toDateKey_(sheet.getRange(sourceRow, 4).getValue());

    sheet.getRange(sourceRow, 2, 1, 4).setValues([[
      request.requestId,
      payload.transactionUuid,
      payload.transactionDate,
      payload.completedAt
    ]]);
    sheet.getRange(sourceRow, 7, 1, 6).setValues([[
      item.type,
      item.brand,
      item.model,
      item.size,
      item.colour,
      item.sellingPrice
    ]]);
    sheet.getRange(sourceRow, 13).setValue(new Date());

    diagnostics.appendRow([
      new Date(),
      request.requestId,
      payload.eventUuid,
      'SALE_CORRECTION',
      'SUCCESS',
      1,
      ''
    ]);

    if (previousTransactionDate && previousTransactionDate !== payload.transactionDate) {
      projectDailyReportBestEffort_(ss, request, previousTransactionDate, payload.eventUuid);
    }
    projectDailyReportBestEffort_(ss, request, payload.transactionDate, payload.eventUuid);

    return success_(
      'SUCCESS',
      'SALE_CORRECTION accepted',
      {
        eventUuid: payload.eventUuid,
        transactionUuid: payload.transactionUuid,
        itemUuid: item.itemUuid,
        updatedRows: 1
      }
    );
  } finally {
    lock.releaseLock();
  }
}

function handleWalkIn_(request, payload) {
  requireString_(payload.eventUuid, 'eventUuid');
  requireDate_(payload.businessDate, 'businessDate');
  requireString_(payload.operation, 'operation');

  if (
    payload.operation !== 'INCREMENT' &&
    payload.operation !== 'RESET'
  ) {
    return failure_(
      'VALIDATION_ERROR',
      'Unsupported WALK_IN operation',
      { operation: payload.operation }
    );
  }

  requireInteger_(payload.delta, 'delta');

  if (payload.operation === 'INCREMENT') {
    if (payload.delta !== 1 && payload.delta !== -1) {
      return failure_(
        'VALIDATION_ERROR',
        'WALK_IN INCREMENT delta must be 1 or -1',
        { delta: payload.delta }
      );
    }
  }

  if (payload.operation === 'RESET' && payload.delta !== 0) {
    return failure_(
      'VALIDATION_ERROR',
      'WALK_IN RESET delta must be 0',
      { delta: payload.delta }
    );
  }

  if (payload.resultingWalkIns != null) {
    requireNonNegativeInteger_(
      payload.resultingWalkIns,
      'resultingWalkIns'
    );
  }

  const lock = LockService.getScriptLock();
  lock.waitLock(15000);

  try {
    const ss = spreadsheet_();

    const sheet = ensureSheetWithHeaders_(
      ss,
      SHEET_WALK_INS,
      [
        'eventUuid',
        'requestId',
        'businessDate',
        'eventType',
        'operation',
        'delta',
        'resultingWalkIns',
        'receivedAt'
      ]
    );

    const values = sheet.getDataRange().getValues();

    if (findValue_(values, 0, payload.eventUuid).found) {
      projectDailyReportBestEffort_(ss, request, payload.businessDate, payload.eventUuid);
      return success_(
        'IDEMPOTENT_SUCCESS',
        'Event already processed',
        {
          eventUuid: payload.eventUuid,
          insertedRows: 0
        }
      );
    }

    const receivedAt = new Date();

    sheet.appendRow([
      payload.eventUuid,
      request.requestId,
      payload.businessDate,
      'WALK_IN',
      payload.operation,
      payload.delta,
      payload.resultingWalkIns == null
        ? ''
        : payload.resultingWalkIns,
      receivedAt
    ]);

    ensureDiagnosticsSheet_(ss).appendRow([
      receivedAt,
      request.requestId,
      payload.eventUuid,
      'WALK_IN',
      'SUCCESS',
      1,
      ''
    ]);

    projectDailyReportBestEffort_(ss, request, payload.businessDate, payload.eventUuid);

    return success_(
      'SUCCESS',
      'WALK_IN accepted',
      {
        eventUuid: payload.eventUuid,
        insertedRows: 1
      }
    );
  } finally {
    lock.releaseLock();
  }
}

/**
 * M11 MASTER mutation.
 *
 * One logical local master mutation is delivered as one SYNC request.
 * The script lock serializes concurrent devices so Model ownership is
 * established atomically:
 *   - absent Model -> create Type+Brand ownership + variant;
 *   - existing same-owner Model -> add Size/Colour if absent;
 *   - existing conflicting owner -> deterministic MASTER_CONFLICT.
 *
 * Idempotency is checked by eventUuid in Diagnostics first. Exact existing
 * catalogue combinations are also treated as idempotently applied, which
 * protects against a crash between the Masters write and Diagnostics write.
 */
function handleMasterMutation_(request, payload) {
  requireString_(payload.eventUuid, 'eventUuid');
  requireString_(payload.type, 'type');
  requireString_(payload.brand, 'brand');
  requireString_(payload.model, 'model');
  requireString_(payload.size, 'size');
  requireString_(payload.colour, 'colour');

  const lock = LockService.getScriptLock();
  lock.waitLock(15000);

  try {
    const ss = spreadsheet_();
    const sheet = ensureSheetWithHeaders_(
      ss,
      SHEET_MASTERS,
      ['type', 'brand', 'model', 'size', 'colour']
    );
    const diagnostics = ensureDiagnosticsSheet_(ss);

    const diagnosticValues = diagnostics.getDataRange().getValues();
    if (findValue_(diagnosticValues, 2, payload.eventUuid).found) {
      return success_(
        'IDEMPOTENT_SUCCESS',
        'Master event already processed',
        { eventUuid: payload.eventUuid, insertedRows: 0 }
      );
    }

    const values = sheet.getDataRange().getValues();
    const rows = values.length <= 1 ? [] : values.slice(1);
    const modelKey = normalizeMasterValue_(payload.model);
    const typeKey = normalizeMasterValue_(payload.type);
    const brandKey = normalizeMasterValue_(payload.brand);
    const sizeKey = normalizeMasterValue_(payload.size);
    const colourKey = normalizeMasterValue_(payload.colour);

    const modelRows = rows.filter(function(row) {
      return normalizeMasterValue_(row[2]) === modelKey;
    });

    const conflicting = modelRows.find(function(row) {
      return normalizeMasterValue_(row[0]) !== typeKey ||
        normalizeMasterValue_(row[1]) !== brandKey;
    });

    if (conflicting) {
      return failure_(
        'MASTER_CONFLICT',
        'Model already belongs to a different Type/Brand',
        {
          eventUuid: payload.eventUuid,
          model: payload.model,
          requestedType: payload.type,
          requestedBrand: payload.brand,
          itemUuid: String(payload.itemUuid || ''),
          transactionUuid: String(payload.transactionUuid || ''),
          canonicalType: String(conflicting[0] || ''),
          canonicalBrand: String(conflicting[1] || '')
        }
      );
    }

    const exact = modelRows.find(function(row) {
      return normalizeMasterValue_(row[0]) === typeKey &&
        normalizeMasterValue_(row[1]) === brandKey &&
        normalizeMasterValue_(row[3]) === sizeKey &&
        normalizeMasterValue_(row[4]) === colourKey;
    });

    const receivedAt = new Date();

    if (exact) {
      diagnostics.appendRow([
        receivedAt,
        request.requestId,
        payload.eventUuid,
        'MASTER',
        'IDEMPOTENT_SUCCESS',
        0,
        ''
      ]);

      return success_(
        'IDEMPOTENT_SUCCESS',
        'Master combination already present',
        { eventUuid: payload.eventUuid, insertedRows: 0 }
      );
    }

    sheet.appendRow([
      payload.type,
      payload.brand,
      payload.model,
      payload.size,
      payload.colour
    ]);

    diagnostics.appendRow([
      receivedAt,
      request.requestId,
      payload.eventUuid,
      'MASTER',
      'SUCCESS',
      1,
      ''
    ]);

    return success_(
      'SUCCESS',
      'MASTER accepted',
      { eventUuid: payload.eventUuid, insertedRows: 1 }
    );
  } finally {
    lock.releaseLock();
  }
}

/**
 * M12 MASTER_RECLASSIFY correction event.
 *
 * This changes the current canonical Masters ownership but NEVER rewrites
 * historical Transactions rows. The MasterCorrections sheet is an explicit
 * audit trail. M14 report projection deliberately uses the Type/Brand recorded
 * on each historical Transactions row, so reclassification affects future
 * ownership without moving old sales between American Tourister and Kamiliant.
 */
function handleMasterReclassification_(request, payload) {
  requireString_(payload.eventUuid, 'eventUuid');
  requireString_(payload.model, 'model');
  requireString_(payload.oldType, 'oldType');
  requireString_(payload.oldBrand, 'oldBrand');
  requireString_(payload.newType, 'newType');
  requireString_(payload.newBrand, 'newBrand');

  const lock = LockService.getScriptLock();
  lock.waitLock(15000);

  try {
    const ss = spreadsheet_();
    const mastersSheet = ensureSheetWithHeaders_(
      ss,
      SHEET_MASTERS,
      ['type', 'brand', 'model', 'size', 'colour']
    );
    const correctionsSheet = ensureSheetWithHeaders_(
      ss,
      SHEET_MASTER_CORRECTIONS,
      [
        'eventUuid', 'requestId', 'model',
        'oldType', 'oldBrand', 'newType', 'newBrand', 'correctedAt'
      ]
    );
    const diagnostics = ensureDiagnosticsSheet_(ss);
    const diagnosticValues = diagnostics.getDataRange().getValues();

    if (findValue_(diagnosticValues, 2, payload.eventUuid).found) {
      return success_(
        'IDEMPOTENT_SUCCESS',
        'Master reclassification already processed',
        { eventUuid: payload.eventUuid, insertedRows: 0 }
      );
    }

    const correctionValues = correctionsSheet.getDataRange().getValues();
    if (findValue_(correctionValues, 0, payload.eventUuid).found) {
      diagnostics.appendRow([
        new Date(), request.requestId, payload.eventUuid,
        'MASTER_RECLASSIFY', 'IDEMPOTENT_SUCCESS', 0, ''
      ]);
      return success_(
        'IDEMPOTENT_SUCCESS',
        'Master reclassification already recorded',
        { eventUuid: payload.eventUuid, insertedRows: 0 }
      );
    }

    const values = mastersSheet.getDataRange().getValues();
    const rows = values.length <= 1 ? [] : values.slice(1);
    const modelKey = normalizeMasterValue_(payload.model);
    const oldOwner = normalizeMasterValue_(payload.oldType) + '|' +
      normalizeMasterValue_(payload.oldBrand);
    const newOwner = normalizeMasterValue_(payload.newType) + '|' +
      normalizeMasterValue_(payload.newBrand);

    const modelRows = rows.filter(function(row) {
      return normalizeMasterValue_(row[2]) === modelKey;
    });

    if (modelRows.length === 0) {
      return failure_(
        'MASTER_CONFLICT',
        'Model is missing from the canonical Masters catalogue',
        {
          eventUuid: payload.eventUuid,
          model: payload.model,
          canonicalType: '',
          canonicalBrand: ''
        }
      );
    }

    const owners = {};
    modelRows.forEach(function(row) {
      owners[
        normalizeMasterValue_(row[0]) + '|' +
        normalizeMasterValue_(row[1])
      ] = true;
    });
    const ownerKeys = Object.keys(owners);

    if (ownerKeys.length !== 1 || ownerKeys[0] !== oldOwner) {
      if (ownerKeys.length === 1 && ownerKeys[0] === newOwner) {
        correctionsSheet.appendRow([
          payload.eventUuid,
          request.requestId,
          payload.model,
          payload.oldType,
          payload.oldBrand,
          payload.newType,
          payload.newBrand,
          new Date()
        ]);
        diagnostics.appendRow([
          new Date(), request.requestId, payload.eventUuid,
          'MASTER_RECLASSIFY', 'IDEMPOTENT_SUCCESS', 0, ''
        ]);
        return success_(
          'IDEMPOTENT_SUCCESS',
          'Model already has requested canonical ownership',
          { eventUuid: payload.eventUuid, insertedRows: 0 }
        );
      }

      const canonical = ownerKeys.length > 0 ? ownerKeys[0].split('|') : ['', ''];
      return failure_(
        'MASTER_CONFLICT',
        'Model ownership changed before reclassification',
        {
          eventUuid: payload.eventUuid,
          model: payload.model,
          canonicalType: canonical[0],
          canonicalBrand: canonical[1]
        }
      );
    }

    if (oldOwner === newOwner) {
      correctionsSheet.appendRow([
        payload.eventUuid,
        request.requestId,
        payload.model,
        payload.oldType,
        payload.oldBrand,
        payload.newType,
        payload.newBrand,
        new Date()
      ]);
      diagnostics.appendRow([
        new Date(), request.requestId, payload.eventUuid,
        'MASTER_RECLASSIFY', 'IDEMPOTENT_SUCCESS', 0, ''
      ]);
      return success_(
        'IDEMPOTENT_SUCCESS',
        'Model already has requested ownership',
        { eventUuid: payload.eventUuid, insertedRows: 0 }
      );
    }

    const receivedAt = new Date();

    // Update only the current Masters catalogue rows. Historical raw SALE
    // rows remain append-only and are intentionally untouched.
    modelRows.forEach(function(row) {
      const sourceRow = rows.indexOf(row) + 2;
      mastersSheet.getRange(sourceRow, 1, 1, 2)
        .setValues([[payload.newType, payload.newBrand]]);
    });

    correctionsSheet.appendRow([
      payload.eventUuid,
      request.requestId,
      payload.model,
      payload.oldType,
      payload.oldBrand,
      payload.newType,
      payload.newBrand,
      receivedAt
    ]);

    diagnostics.appendRow([
      receivedAt,
      request.requestId,
      payload.eventUuid,
      'MASTER_RECLASSIFY',
      'SUCCESS',
      modelRows.length,
      ''
    ]);

    return success_(
      'SUCCESS',
      'MASTER_RECLASSIFY accepted',
      { eventUuid: payload.eventUuid, insertedRows: 0, updatedRows: modelRows.length }
    );
  } finally {
    lock.releaseLock();
  }
}

function normalizeMasterValue_(value) {
  return String(value == null ? '' : value).trim().toLowerCase();
}

/**
 * One-time Beta migration helper.
 *
 * Populates the canonical Masters sheet from already accepted raw SALE rows.
 * It is intentionally NOT called by handleMasters_ or automatically at app
 * startup. Run it explicitly once when promoting the existing Beta catalogue
 * into the canonical Masters dataset.
 *
 * The helper refuses to choose an owner if historical Transactions contain a
 * Model under conflicting Type/Brand combinations.
 */
function seedMastersFromTransactions() {
  const lock = LockService.getScriptLock();
  lock.waitLock(15000);

  try {
    const ss = spreadsheet_();
    const transactionRows = readDataRows_(ss, SHEET_TRANSACTIONS);
    const sheet = ensureSheetWithHeaders_(
      ss,
      SHEET_MASTERS,
      ['type', 'brand', 'model', 'size', 'colour']
    );
    const existingRows = sheet.getDataRange().getValues();

    const catalogue = {};

    transactionRows.forEach(function(row) {
      const type = String(row[6] || '').trim();
      const brand = String(row[7] || '').trim();
      const model = String(row[8] || '').trim();
      const size = String(row[9] || '').trim();
      const colour = String(row[10] || '').trim();

      if (!type || !brand || !model || !size || !colour) {
        return;
      }

      const modelKey = normalizeMasterValue_(model);
      const owner = normalizeMasterValue_(type) + '|' +
        normalizeMasterValue_(brand);

      if (catalogue[modelKey] && catalogue[modelKey].owner !== owner) {
        throw new Error('MASTER_MODEL_CONFLICT:' + model);
      }

      catalogue[modelKey] = catalogue[modelKey] || {
        type: type,
        brand: brand,
        model: model,
        owner: owner,
        variants: {}
      };

      const variantKey = normalizeMasterValue_(size) + '|' +
        normalizeMasterValue_(colour);
      catalogue[modelKey].variants[variantKey] = {
        type: type,
        brand: brand,
        model: model,
        size: size,
        colour: colour
      };
    });

    const existing = {};
    existingRows.slice(1).forEach(function(row) {
      const modelKey = normalizeMasterValue_(row[2]);
      const owner = normalizeMasterValue_(row[0]) + '|' +
        normalizeMasterValue_(row[1]);
      if (modelKey) {
        if (existing[modelKey] && existing[modelKey].owner !== owner) {
          throw new Error('MASTER_SHEET_MODEL_CONFLICT:' + row[2]);
        }
        existing[modelKey] = existing[modelKey] || {
          owner: owner,
          variants: {}
        };
        existing[modelKey].variants[
          normalizeMasterValue_(row[3]) + '|' +
          normalizeMasterValue_(row[4])
        ] = true;
      }
    });

    const rowsToInsert = [];

    Object.keys(catalogue).forEach(function(modelKey) {
      const entry = catalogue[modelKey];
      const existingModel = existing[modelKey];

      if (existingModel && existingModel.owner !== entry.owner) {
        throw new Error('MASTER_SHEET_MODEL_CONFLICT:' + entry.model);
      }

      Object.keys(entry.variants).forEach(function(variantKey) {
        if (!existingModel || !existingModel.variants[variantKey]) {
          const variant = entry.variants[variantKey];
          rowsToInsert.push([
            variant.type,
            variant.brand,
            variant.model,
            variant.size,
            variant.colour
          ]);
        }
      });
    });

    if (rowsToInsert.length > 0) {
      sheet.getRange(
        sheet.getLastRow() + 1,
        1,
        rowsToInsert.length,
        rowsToInsert[0].length
      ).setValues(rowsToInsert);
    }

    return {
      success: true,
      models: Object.keys(catalogue).length,
      insertedRows: rowsToInsert.length
    };
  } finally {
    lock.releaseLock();
  }
}

/**
 * REPORT read path.
 *
 * The report is always derived from authoritative raw ledgers. M14 additionally
 * projects the same derivation into the separate company-facing workbook when
 * explicitly requested via projectLegacyReport or after a successful SALE /
 * SALE_CORRECTION / WALK_IN mutation.
 *
 * Payload:
 * {
 *   reportDate: "YYYY-MM-DD",
 *   projectLegacyReport: true // optional manual recovery/projection
 * }
 */
function handleReport_(request) {
  const payload = request.payload;

  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    return failure_(
      'VALIDATION_ERROR',
      'payload is required',
      {}
    );
  }

  requireDate_(payload.reportDate, 'reportDate');

  const report = buildReport_(payload.reportDate);
  let projection = null;

  if (payload.projectLegacyReport === true) {
    projection = projectDailyReport_(payload.reportDate, request.requestId, 'REPORT_MANUAL');
  }

  return success_(
    'SUCCESS',
    projection ? 'REPORT generated and projected' : 'REPORT generated',
    { report: report, projection: projection }
  );
}

/**
 * M14 company-facing projection.
 *
 * The reporting workbook is a presentation layer only. This function derives
 * the selected date from the authoritative backend ledgers and overwrites
 * only the daily projection cells in the matching legacy row. MTD/formula
 * columns and all workbook formatting are left untouched.
 */
function projectDailyReport_(reportDate, requestId, eventUuid) {
  requireDate_(reportDate, 'reportDate');

  const backendSs = spreadsheet_();
  const report = buildReport_(reportDate);
  const reportingSs = reportingSpreadsheet_();
  const location = findReportDateLocation_(reportingSs, reportDate);
  const sheet = location.sheet;
  const row = location.row;

  validateLegacyReportRow_(sheet, row);

  const atMerchandise = report.merchandiseSold.americanTourister || [];
  const kamMerchandise = report.merchandiseSold.kamiliant || [];

  if (atMerchandise.length > 2) {
    throw new Error('REPORT_AT_MERCHANDISE_CAPACITY_EXCEEDED:' + atMerchandise.length);
  }
  if (kamMerchandise.length > 6) {
    throw new Error('REPORT_KAM_MERCHANDISE_CAPACITY_EXCEEDED:' + kamMerchandise.length);
  }

  // Match the established workbook convention: zero daily values are blank,
  // while the MTD cells remain formulas that treat blanks as zero.
  sheet.getRange(row, 3).setValue(report.atSales === 0 ? '' : report.atSales);
  sheet.getRange(row, 5).setValue(report.kamSales === 0 ? '' : report.kamSales);
  sheet.getRange(row, 8).setValue(report.footfall === 0 ? '' : report.footfall);
  sheet.getRange(row, 10).setValue(report.conversions === 0 ? '' : report.conversions);

  // L:S = AT1, KAM1, KAM2, AT2, KAM3, KAM4, KAM5, KAM6.
  // The interleaved order is fixed by the supplied legacy workbook.
  // Build it explicitly so AT2 remains column O rather than being shifted.
  const cells = new Array(8).fill('');
  atMerchandise.forEach(function(item, index) {
    const targetIndex = index === 0 ? 0 : 3;
    cells[targetIndex] = formatLegacyMerchandise_(item);
  });
  kamMerchandise.forEach(function(item, index) {
    const targetIndex = [1, 2, 4, 5, 6, 7][index];
    cells[targetIndex] = formatLegacyMerchandise_(item);
  });

  sheet.getRange(row, 12, 1, 8).setValues([cells]);

  // Company-facing monetary presentation: Indian grouping, no symbol and
  // no decimals. This changes display formatting only; formulas and numeric
  // values remain intact.
  applyReportMonetaryNumberFormats_(sheet, row);

  const diagnostics = ensureDiagnosticsSheet_(backendSs);
  diagnostics.appendRow([
    new Date(),
    requestId || '',
    eventUuid || '',
    'REPORT_PROJECTION',
    'SUCCESS',
    1,
    ''
  ]);

  return {
    success: true,
    reportDate: reportDate,
    sheetName: sheet.getName(),
    row: row,
    atSales: report.atSales,
    kamSales: report.kamSales,
    footfall: report.footfall,
    conversions: report.conversions,
    atMerchandise: atMerchandise.length,
    kamMerchandise: kamMerchandise.length
  };
}

/**
 * Automatic projection wrapper used after an authoritative raw-ledger
 * mutation. Projection is deliberately best-effort: a reporting failure must
 * never cause Android to retry an already-committed raw SALE/WALK_IN.
 */
function projectDailyReportBestEffort_(backendSs, request, reportDate, eventUuid) {
  try {
    // The public projection function resolves the configured workbooks and
    // derives the report again from raw ledgers, guaranteeing idempotency.
    return projectDailyReport_(reportDate, request && request.requestId, eventUuid);
  } catch (err) {
    ensureDiagnosticsSheet_(backendSs).appendRow([
      new Date(),
      request && request.requestId ? request.requestId : '',
      eventUuid || '',
      'REPORT_PROJECTION',
      'FAILED',
      0,
      String(err && err.message ? err.message : err)
    ]);
    return null;
  }
}

/**
 * Manual recovery entry point for Apps Script editor use.
 * Example: projectDailyReport('2026-09-14')
 */
function projectDailyReport(reportDate) {
  return projectDailyReport_(reportDate, 'MANUAL', 'MANUAL');
}

/**
 * Manual recovery/backfill helper for a bounded date range. It is deliberately
 * explicit rather than automatic so deployment cannot unexpectedly rewrite a
 * large portion of the stakeholder workbook.
 * Example: projectDailyReportRange('2026-09-01', '2026-09-14')
 */
function projectDailyReportRange(startDate, endDate) {
  requireDate_(startDate, 'startDate');
  requireDate_(endDate, 'endDate');

  if (startDate > endDate) {
    throw new Error('INVALID_REPORT_DATE_RANGE');
  }

  const start = new Date(startDate + 'T00:00:00');
  const end = new Date(endDate + 'T00:00:00');
  const results = [];

  for (let cursor = new Date(start.getTime()); cursor <= end; cursor.setDate(cursor.getDate() + 1)) {
    const date = Utilities.formatDate(
      cursor,
      Session.getScriptTimeZone(),
      'yyyy-MM-dd'
    );
    results.push(projectDailyReport_(date, 'MANUAL_RANGE', 'MANUAL_RANGE:' + date));
  }

  return results;
}

/** Locate the legacy row by actual Date-column value, not row number or tab
 * naming. This makes the projection robust to the workbook's Jul/Aug/Sept
 * naming convention and future month tabs, provided the date rows exist.
 */
function findReportDateLocation_(reportingSs, reportDate) {
  const sheets = reportingSs.getSheets();
  const matches = [];

  sheets.forEach(function(sheet) {
    const lastRow = sheet.getLastRow();
    if (lastRow < 4) {
      return;
    }

    const values = sheet.getRange(4, 1, lastRow - 3, 1).getValues();
    values.forEach(function(value, offset) {
      if (toDateKey_(value[0]) === reportDate) {
        matches.push({ sheet: sheet, row: offset + 4 });
      }
    });
  });

  if (matches.length === 0) {
    throw new Error('REPORT_DATE_ROW_NOT_FOUND:' + reportDate);
  }

  if (matches.length > 1) {
    throw new Error('REPORT_DATE_ROW_AMBIGUOUS:' + reportDate);
  }

  return matches[0];
}

/**
 * Apply the locked company-facing monetary display convention to the Daily
 * Report row. C/E are daily AT/Kamiliant values; D/F/G are formula-derived
 * MTD monetary values. setNumberFormat changes presentation only and leaves
 * formulas/numeric values intact.
 */
function applyReportMonetaryNumberFormats_(sheet, row) {
  sheet.getRange(row, 3, 1, 5).setNumberFormat('#,##,##0');
}

/**
 * Guard against accidentally projecting into an unrelated worksheet. The
 * supplied workbook's row-3 headers are the canonical mapping.
 */
function validateLegacyReportRow_(sheet, row) {
  const expected = {
    1: 'Date',
    3: 'Daily',
    5: 'Daily',
    8: 'Daily',
    10: 'Daily',
    12: 'AT 1',
    13: 'KAM 1',
    14: 'KAM 2',
    15: 'AT 2',
    16: 'KAM 3',
    17: 'KAM 4',
    18: 'KAM 5',
    19: 'KAM 6'
  };

  Object.keys(expected).forEach(function(column) {
    const actual = String(sheet.getRange(3, Number(column)).getDisplayValue() || '').trim();
    if (actual !== expected[column]) {
      throw new Error(
        'REPORT_LAYOUT_MISMATCH:' + sheet.getName() + ':C' + column +
        ':expected=' + expected[column] + ':actual=' + actual
      );
    }
  });

  // MTD/formula columns are protected. Do not silently overwrite a broken
  // template; surface it as a projection failure instead.
  [4, 6, 7, 9, 11].forEach(function(column) {
    const formula = String(sheet.getRange(row, column).getFormula() || '').trim();
    if (!formula) {
      throw new Error(
        'REPORT_MTD_FORMULA_MISSING:' + sheet.getName() + ':R' + row + 'C' + column
      );
    }
  });
}

function formatLegacyMerchandise_(item) {
  const model = String(item.model || '').trim();
  const size = String(item.size || '').trim();
  const colour = String(item.colour || '').trim();
  const parts = [];

  if (model) {
    parts.push(model);
  }
  if (size) {
    parts.push(size);
  }
  if (colour) {
    parts.push(colour);
  }

  return parts.join(' ');
}

function buildReport_(reportDate) {
  const ss = spreadsheet_();

  const transactionRows = readDataRows_(ss, SHEET_TRANSACTIONS);
  const walkInRows = readDataRows_(ss, SHEET_WALK_INS);

  const dailyTransactions = transactionRows.filter(function(row) {
    return toDateKey_(row[3]) === reportDate;
  });

  const atItems = dailyTransactions
    .filter(function(row) {
      return recordedBrand_(row) === 'American Tourister';
    })
    .map(function(row) {
      return transactionRowToReportItem_(row);
    });

  const kamItems = dailyTransactions
    .filter(function(row) {
      return recordedBrand_(row) === 'Kamiliant';
    })
    .map(function(row) {
      return transactionRowToReportItem_(row);
    });

  const atSales = sumItems_(atItems);
  const kamSales = sumItems_(kamItems);
  const totalSales = atSales + kamSales;

  const conversionIds = {};
  dailyTransactions.forEach(function(row) {
    const transactionUuid = String(row[2] || '').trim();
    if (transactionUuid) {
      conversionIds[transactionUuid] = true;
    }
  });

  const conversions = Object.keys(conversionIds).length;
  const footfall = calculateDailyFootfall_(
    walkInRows,
    reportDate
  );

  const conversionPercent =
    footfall > 0
      ? (conversions / footfall) * 100
      : 0;

  const monthPrefix = reportDate.substring(0, 7);

  const mtdTransactions = transactionRows.filter(function(row) {
    return toDateKey_(row[3]).substring(0, 7) === monthPrefix;
  });

  const mtdAtSales = sumColumnForBrand_(
    mtdTransactions,
    'American Tourister'
  );

  const mtdKamSales = sumColumnForBrand_(
    mtdTransactions,
    'Kamiliant'
  );

  const mtdConversionIds = {};
  mtdTransactions.forEach(function(row) {
    const transactionUuid = String(row[2] || '').trim();
    if (transactionUuid) {
      mtdConversionIds[transactionUuid] = true;
    }
  });

  const mtdConversions = Object.keys(mtdConversionIds).length;

  const mtdWalkIns = calculateMtdFootfall_(
    walkInRows,
    monthPrefix
  );

  const mtdConversionPercent =
    mtdWalkIns > 0
      ? (mtdConversions / mtdWalkIns) * 100
      : 0;
  const mtdTotalSales = mtdAtSales + mtdKamSales;
  const aov =
    mtdConversions > 0
      ? Math.round(mtdTotalSales / mtdConversions)
      : 0;

  const insights = buildInsights_({
    atSales: atSales,
    kamSales: kamSales,
    totalSales: totalSales,
    footfall: footfall,
    conversions: conversions,
    conversionPercent: conversionPercent,
    atItems: atItems.length,
    kamItems: kamItems.length
  });

  return {
    storeName: REPORT_STORE_NAME,
    location: REPORT_STORE_LOCATION,
    reportDate: reportDate,

    atSales: atSales,
    kamSales: kamSales,
    totalSales: totalSales,

    footfall: footfall,
    conversions: conversions,
    conversionPercent: conversionPercent,

    merchandiseSold: {
      americanTourister: atItems,
      kamiliant: kamItems
    },

    monthToDate: {
      atSales: mtdAtSales,
      kamSales: mtdKamSales,
      totalSales: mtdAtSales + mtdKamSales,
      footfall: mtdWalkIns,
      conversions: mtdConversions,
      conversionPercent: mtdConversionPercent,
      aov: aov
    },

    insights: insights
  };
}

function transactionRowToReportItem_(row) {
  return {
    itemUuid: String(row[5] || ''),
    type: String(row[6] || ''),
    brand: String(row[7] || ''),
    model: String(row[8] || ''),
    size: String(row[9] || ''),
    colour: String(row[10] || ''),
    sellingPrice: toNumber_(row[11])
  };
}

function sumItems_(items) {
  return items.reduce(function(total, item) {
    return total + toNumber_(item.sellingPrice);
  }, 0);
}

function sumColumnForBrand_(rows, brand) {
  return rows.reduce(function(total, row) {
    if (recordedBrand_(row) !== brand) {
      return total;
    }

    return total + toNumber_(row[11]);
  }, 0);
}

/**
 * M14 historical-reporting rule: use the brand captured on the SALE row.
 * Current Masters ownership is intentionally ignored here so a later
 * MASTER_RECLASSIFY cannot rewrite history.
 */
function recordedBrand_(row) {
  return String(row[7] || '').trim();
}

function buildCurrentModelOwnerMap_(ss) {
  const rows = readDataRows_(ss, SHEET_MASTERS);
  const owners = {};

  rows.forEach(function(row) {
    const model = normalizeMasterValue_(row[2]);
    if (!model) {
      return;
    }

    const owner = {
      type: String(row[0] || ''),
      brand: String(row[1] || '')
    };

    if (owners[model] && (
      normalizeMasterValue_(owners[model].type) !== normalizeMasterValue_(owner.type) ||
      normalizeMasterValue_(owners[model].brand) !== normalizeMasterValue_(owner.brand)
    )) {
      throw new Error('MASTER_SHEET_MODEL_CONFLICT:' + row[2]);
    }

    owners[model] = owner;
  });

  return owners;
}

/**
 * Walk-in footfall is reconstructed from the raw event deltas.
 *
 * A RESET establishes a new zero point. All subsequent INCREMENT deltas
 * (+1/-1) are then applied. This avoids trusting a possibly stale
 * resultingWalkIns value from another device.
 *
 * Sheet row order is the event append order.
 */
function calculateDailyFootfall_(rows, reportDate) {
  const events = rows.filter(function(row) {
    return toDateKey_(row[2]) === reportDate;
  });

  let lastResetIndex = -1;

  events.forEach(function(row, index) {
    if (String(row[4]) === 'RESET') {
      lastResetIndex = index;
    }
  });

  let footfall = 0;

  for (
    let i = lastResetIndex + 1;
    i < events.length;
    i++
  ) {
    footfall += toNumber_(events[i][5]);
  }

  return Math.max(0, footfall);
}

function calculateMtdFootfall_(rows, monthPrefix) {
  const dates = {};

  rows.forEach(function(row) {
    const date = toDateKey_(row[2]);
    if (date.substring(0, 7) === monthPrefix) {
      dates[date] = true;
    }
  });

  return Object.keys(dates).reduce(function(total, date) {
    return total + calculateDailyFootfall_(rows, date);
  }, 0);
}

function buildInsights_(data) {
  const insights = [];

  if (data.totalSales === 0) {
    insights.push('No merchandise sales recorded for this date.');
  } else {
    if (data.atSales > 0 && data.kamSales > 0) {
      insights.push(
        'Both American Tourister and Kamiliant recorded sales today.'
      );
    } else if (data.atSales > 0) {
      insights.push(
        "Today's sales were entirely from American Tourister."
      );
    } else if (data.kamSales > 0) {
      insights.push(
        "Today's sales were entirely from Kamiliant."
      );
    }
  }

  if (data.footfall === 0) {
    insights.push('Footfall is zero for the selected date.');
  } else {
    insights.push(
      'Conversion rate is ' +
      data.conversionPercent.toFixed(1) +
      '%.'
    );
  }

  return insights;
}

function readDataRows_(ss, name) {
  const sheet = ss.getSheetByName(name);

  if (!sheet || sheet.getLastRow() <= 1) {
    return [];
  }

  return sheet
    .getRange(
      2,
      1,
      sheet.getLastRow() - 1,
      sheet.getLastColumn()
    )
    .getValues();
}

/**
 * Milestone 10 fix: Google Sheets auto-converts date-shaped strings
 * written via the API into an actual Date cell type, even though the
 * sheet UI still displays something that looks like "2026-08-27". When
 * read back via getValues(), such a cell comes back as a JS Date
 * object, not the original string — so a naive String(row[i]) ===
 * reportDate comparison silently never matches. This normalizes either
 * shape (Date object or plain string) to a canonical yyyy-MM-dd key in
 * the script's own timezone, so date-column comparisons work
 * regardless of which type Sheets decided to store a given row as.
 */
function toDateKey_(value) {
  if (Object.prototype.toString.call(value) === '[object Date]') {
    return Utilities.formatDate(
      value,
      Session.getScriptTimeZone(),
      'yyyy-MM-dd'
    );
  }
  return String(value || '').trim();
}

function toNumber_(value) {
  if (typeof value === 'number') {
    return isFinite(value) ? value : 0;
  }

  const parsed = Number(value);
  return isFinite(parsed) ? parsed : 0;
}

function handleMasters_() {
  const sheet = ensureSheetWithHeaders_(
    spreadsheet_(),
    SHEET_MASTERS,
    ['type', 'brand', 'model', 'size', 'colour']
  );

  const rows = sheet.getDataRange().getValues();

  const masters = rows.length <= 1
    ? []
    : rows.slice(1).map(function(row) {
        return {
          type: row[0],
          brand: row[1],
          model: row[2],
          size: row[3],
          colour: row[4]
        };
      });

  return success_(
    'SUCCESS',
    'Masters returned',
    { masters: masters }
  );
}

function validateEnvelope_(request) {
  if (
    !request ||
    typeof request !== 'object' ||
    Array.isArray(request)
  ) {
    throw new Error('INVALID_JSON_OBJECT');
  }

  if (request.apiVersion !== API_VERSION) {
    throw new Error('UNSUPPORTED_API_VERSION');
  }

  requireString_(request.requestId, 'requestId');
  requireString_(request.action, 'action');
  requireString_(request.timestamp, 'timestamp');
}

function validateSaleItem_(item) {
  if (!item || typeof item !== 'object' || Array.isArray(item)) {
    throw new Error('INVALID_ITEM');
  }

  requireString_(item.itemUuid, 'itemUuid');
  requireString_(item.type, 'type');
  requireString_(item.brand, 'brand');
  requireString_(item.model, 'model');
  requireString_(item.size, 'size');
  requireString_(item.colour, 'colour');

  if (
    typeof item.sellingPrice !== 'number' ||
    !isFinite(item.sellingPrice) ||
    item.sellingPrice < 0
  ) {
    throw new Error('INVALID_SELLING_PRICE');
  }
}

function authenticate_(e) {
  const expected =
    PropertiesService.getScriptProperties()
      .getProperty('API_KEY');

  if (!expected) {
    throw new Error('SERVER_NOT_CONFIGURED');
  }

  const supplied =
    e && e.parameter ? e.parameter.apiKey : null;

  if (!supplied || supplied !== expected) {
    throw new Error('UNAUTHORIZED');
  }
}

function spreadsheet_() {
  const id =
    PropertiesService.getScriptProperties()
      .getProperty('SPREADSHEET_ID');

  if (!id) {
    throw new Error('SERVER_NOT_CONFIGURED');
  }

  return SpreadsheetApp.openById(id);
}

/** M14: separate company-facing reporting workbook. */
function reportingSpreadsheet_() {
  const id =
    PropertiesService.getScriptProperties()
      .getProperty('REPORT_SPREADSHEET_ID');

  if (!id) {
    throw new Error('REPORT_SPREADSHEET_NOT_CONFIGURED');
  }

  return SpreadsheetApp.openById(id);
}

function ensureDiagnosticsSheet_(ss) {
  return ensureSheetWithHeaders_(
    ss,
    SHEET_DIAGNOSTICS,
    [
      'receivedAt',
      'requestId',
      'eventUuid',
      'eventType',
      'status',
      'rows',
      'error'
    ]
  );
}

function ensureSheetWithHeaders_(ss, name, headers) {
  let sheet = ss.getSheetByName(name);

  if (!sheet) {
    sheet = ss.insertSheet(name);
  }

  if (sheet.getLastRow() === 0) {
    sheet
      .getRange(1, 1, 1, headers.length)
      .setValues([headers]);

    sheet.setFrozenRows(1);
  }

  return sheet;
}

function findValue_(values, columnIndex, value) {
  for (let i = 1; i < values.length; i++) {
    if (
      String(values[i][columnIndex]) ===
      String(value)
    ) {
      return {
        found: true,
        row: i + 1
      };
    }
  }

  return {
    found: false,
    row: -1
  };
}

function requireString_(value, field) {
  if (
    typeof value !== 'string' ||
    value.trim() === ''
  ) {
    throw new Error(
      'MISSING_' + field.toUpperCase()
    );
  }
}

function requireDate_(value, field) {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}$/.test(value)
  ) {
    throw new Error(
      'INVALID_' + field.toUpperCase()
    );
  }
}

function requireInteger_(value, field) {
  if (
    typeof value !== 'number' ||
    !Number.isInteger(value)
  ) {
    throw new Error(
      'INVALID_' + field.toUpperCase()
    );
  }
}

function requireNonNegativeInteger_(value, field) {
  requireInteger_(value, field);

  if (value < 0) {
    throw new Error(
      'INVALID_' + field.toUpperCase()
    );
  }
}

function success_(statusCode, message, payload) {
  return {
    success: true,
    statusCode: statusCode,
    message: message,
    serverTime: new Date().toISOString(),
    apiVersion: API_VERSION,
    payload: payload || {}
  };
}

function failure_(statusCode, message, payload) {
  return {
    success: false,
    statusCode: statusCode,
    message: message,
    serverTime: new Date().toISOString(),
    apiVersion: API_VERSION,
    payload: payload || {}
  };
}

function errorResponse_(err) {
  const code = String(
    err && err.message || 'SERVER_ERROR'
  );

  const status =
    code === 'UNAUTHORIZED'
      ? 'UNAUTHORIZED'
      : code === 'SERVER_NOT_CONFIGURED'
        ? 'SERVER_NOT_CONFIGURED'
        : code === 'INVALID_JSON_OBJECT'
          ? 'INVALID_JSON'
          : code === 'UNSUPPORTED_API_VERSION'
            ? 'UNSUPPORTED_API_VERSION'
            : code.indexOf('MISSING_') === 0 ||
              code.indexOf('INVALID_') === 0 ||
              code.indexOf('DUPLICATE_') === 0
              ? 'VALIDATION_ERROR'
              : 'SERVER_ERROR';

  return failure_(status, code, {});
}

function json_(object) {
  return ContentService
    .createTextOutput(JSON.stringify(object))
    .setMimeType(ContentService.MimeType.JSON);
}
