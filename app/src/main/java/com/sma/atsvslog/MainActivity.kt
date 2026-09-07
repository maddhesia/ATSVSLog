package com.sma.atsvslog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sma.atsvslog.di.DatabaseProvider
import com.sma.atsvslog.features.home.HomeScreen
import com.sma.atsvslog.features.home.HomeViewModel
import com.sma.atsvslog.features.report.ReportScreen
import com.sma.atsvslog.features.report.ReportViewModel
import com.sma.atsvslog.features.sale.SaleRecorderScreen
import com.sma.atsvslog.features.sale.SaleRecorderViewModel
import com.sma.atsvslog.notifications.MasterConflictNotificationManager
import com.sma.atsvslog.repository.CloudMasterRepository
import com.sma.atsvslog.repository.LocalSalesRepository
import com.sma.atsvslog.sync.SyncScheduler
import com.sma.atsvslog.ui.ui.ATSVSLogTheme
import java.time.LocalDate
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val database by lazy {
        DatabaseProvider.get(applicationContext)
    }

    private val salesRepository by lazy {
        LocalSalesRepository(database) {
            SyncScheduler.enqueueImmediate(applicationContext)
        }
    }

    private val cloudMasterRepository by lazy {
        CloudMasterRepository(BetaNetwork.client.api)
    }

    private var dismissedConflictId by mutableStateOf<Long?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var selectedDate by mutableStateOf(
        LocalDate.now().toString()
    )

    private var showSaleRecorder by mutableStateOf(false)
    private var showReport by mutableStateOf(false)
    private var saleSessionId by mutableIntStateOf(0)
    private var editingConflictId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleConflictIntent(intent)

        setContent {
            ATSVSLogTheme {
                val pendingConflict by salesRepository
                    .observeOldestPendingMasterConflict()
                    .collectAsState(initial = null)

                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    if (pendingConflict?.notifiedAt != null &&
                        pendingConflict?.localId != dismissedConflictId &&
                        editingConflictId == null
                    ) {
                        val conflict = pendingConflict!!
                        AlertDialog(
                            onDismissRequest = {
                                dismissedConflictId = conflict.localId
                            },
                            title = { Text("Model conflict") },
                            text = {
                                Text(
                                    "Model ${conflict.model} is currently assigned to " +
                                        "${conflict.canonicalType} / ${conflict.canonicalBrand}, " +
                                        "but this sale uses ${conflict.requestedType} / " +
                                        "${conflict.requestedBrand}. Choose EDIT SALE if the " +
                                        "sale entry is wrong, or REASSIGN MODEL if the " +
                                        "catalogue ownership is wrong. Historical sales are not changed by reassigning."
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        dismissedConflictId = conflict.localId
                                        editingConflictId = conflict.localId
                                        saleSessionId += 1
                                        showReport = false
                                        showSaleRecorder = true
                                        MasterConflictNotificationManager.cancel(this@MainActivity)
                                    }
                                ) {
                                    Text("EDIT SALE")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        lifecycleScope.launch {
                                            runCatching {
                                                salesRepository.resolveMasterConflict(
                                                    conflict.localId
                                                )
                                            }.onSuccess {
                                                dismissedConflictId = null
                                                MasterConflictNotificationManager.cancel(
                                                    this@MainActivity
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Text("REASSIGN MODEL")
                                }
                            }
                        )
                    }

                    BackHandler(
                        enabled = showSaleRecorder || showReport
                    ) {
                        if (editingConflictId != null) {
                            val conflictId = editingConflictId
                            lifecycleScope.launch {
                                if (conflictId != null) {
                                    runCatching {
                                        salesRepository.cancelConflictSaleEditing(conflictId)
                                    }
                                }
                                editingConflictId = null
                                dismissedConflictId = null
                                showSaleRecorder = false
                            }
                        } else {
                            showSaleRecorder = false
                            showReport = false
                        }
                    }

                    when {
                        showSaleRecorder -> {
                            val saleViewModel: SaleRecorderViewModel =
                                viewModel(
                                    key = "sale-$selectedDate-$saleSessionId",
                                    factory = SaleRecorderViewModel.Factory(
                                        repository = salesRepository,
                                        cloudMasterRepository = cloudMasterRepository,
                                        date = selectedDate,
                                        conflictLocalId = editingConflictId
                                    )
                                )

                            val state by saleViewModel.uiState
                                .collectAsStateWithLifecycle()

                            SaleRecorderScreen(
                                state = state,
                                onTypeSelected =
                                    saleViewModel::onTypeSelected,
                                onCustomTypeChanged =
                                    saleViewModel::onCustomTypeChanged,
                                onBrandSelected =
                                    saleViewModel::onBrandSelected,
                                onModelSelected =
                                    saleViewModel::onModelSelected,
                                onCustomModelChanged =
                                    saleViewModel::onCustomModelChanged,
                                onSizeSelected =
                                    saleViewModel::onSizeSelected,
                                onCustomSizeChanged =
                                    saleViewModel::onCustomSizeChanged,
                                onColourSelected =
                                    saleViewModel::onColourSelected,
                                onCustomColourChanged =
                                    saleViewModel::onCustomColourChanged,
                                onSellingPriceChanged =
                                    saleViewModel::onSellingPriceChanged,
                                onSaveItem =
                                    saleViewModel::saveItem,
                                onFinishCustomer = {
                                    saleViewModel.finishCustomer {
                                        showSaleRecorder = false
                                        editingConflictId = null
                                        dismissedConflictId = null
                                    }
                                },
                                onClearError =
                                    saleViewModel::clearError,
                                onModelConflictDiscard =
                                    saleViewModel::onModelConflictDiscard,
                                onModelConflictCancel =
                                    saleViewModel::onModelConflictCancel
                            )
                        }

                        showReport -> {
                            val reportViewModel: ReportViewModel =
                                viewModel(
                                    key = "report-$selectedDate",
                                    factory = ReportViewModel.Factory(
                                        api = BetaNetwork.client.api,
                                        date = selectedDate
                                    )
                                )

                            val state by reportViewModel.uiState
                                .collectAsStateWithLifecycle()

                            ReportScreen(
                                state = state,
                                onReload = reportViewModel::reload,
                                onBack = {
                                    showReport = false
                                }
                            )
                        }

                        else -> {
                            val counterViewModel: HomeViewModel =
                                viewModel(
                                    key = "home-$selectedDate",
                                    factory = HomeViewModel.Factory(
                                        repository = salesRepository,
                                        date = selectedDate
                                    )
                                )

                            val state by counterViewModel.uiState
                                .collectAsStateWithLifecycle()

                            HomeScreen(
                                date = selectedDate,
                                walkIns = state.walkIns,
                                conversions = state.conversions,
                                onDateSelected = { newDate ->
                                    selectedDate = newDate
                                },
                                onAddWalkIn =
                                    counterViewModel::addWalkIn,
                                onRemoveWalkIn =
                                    counterViewModel::removeWalkIn,
                                onResetWalkIns =
                                    counterViewModel::resetWalkIns,
                                onRecordSale = {
                                    saleSessionId += 1
                                    showReport = false
                                    showSaleRecorder = true
                                },
                                onViewReport = {
                                    showSaleRecorder = false
                                    showReport = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConflictIntent(intent)
    }

    private fun handleConflictIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(
                MasterConflictNotificationManager.EXTRA_OPEN_MASTER_CONFLICT,
                false
            ) == true
        ) {
            dismissedConflictId = null
            MasterConflictNotificationManager.cancel(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

}
