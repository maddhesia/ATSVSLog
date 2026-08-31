package com.sma.atsvslog.repository

import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.sma.atsvslog.database.ATSVSLogDatabase
import com.sma.atsvslog.database.entity.MasterEntity
import com.sma.atsvslog.network.AtSvsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Synchronizes the shared cloud catalogue into the device-local Room cache.
 *
 * M11 is intentionally bootstrap/merge only. It never deletes local master
 * rows because a local row may represent a master mutation that has not yet
 * been accepted by the cloud. Master writes are carried by the durable
 * SyncQueue and are handled by the normal SyncEngine.
 */
data class MasterBootstrapResult(
    val fetched: Int,
    val inserted: Int,
    val alreadyPresent: Int,
    val preservedLocalConflicts: Int
)

class MasterBootstrapRepository(
    private val database: ATSVSLogDatabase,
    private val api: AtSvsApi
) {
    private val masterDao = database.masterDao()

    suspend fun bootstrap(): MasterBootstrapResult = withContext(Dispatchers.IO) {
        val response = api.masters()

        if (!response.isSuccessful) {
            throw IllegalStateException("MASTERS HTTP ${response.code()}")
        }

        val body = response.body()
            ?: throw IllegalStateException("MASTERS empty response")

        if (!body.success) {
            throw IllegalStateException(
                body.statusCode.ifBlank { "MASTERS_FAILED" }
            )
        }

        val masters = parseMasters(
            body.payload
                ?.getAsJsonArray("masters")
                ?: throw IllegalStateException("MASTERS payload missing")
        )

        validateCanonicalMasters(masters)

        database.withTransaction {
            val local = masterDao.getAllMasters()
            val localOwners = local
                .groupBy { normalize(it.model) }
                .mapValues { (_, rows) ->
                    rows.map { normalize(it.type) to normalize(it.brand) }.toMutableSet()
                }
                .toMutableMap()

            var inserted = 0
            var alreadyPresent = 0
            var preservedConflicts = 0

            masters.forEach { cloud ->
                val modelKey = normalize(cloud.model)
                val owner = normalize(cloud.type) to normalize(cloud.brand)
                val owners = localOwners.getOrPut(modelKey) { mutableSetOf() }

                if (owners.isNotEmpty() && owner !in owners) {
                    // Never delete or rewrite a local model assignment during
                    // bootstrap. It may be an unsynchronized local mutation.
                    preservedConflicts++
                    return@forEach
                }

                val existing = masterDao.findCombination(
                    type = cloud.type,
                    brand = cloud.brand,
                    model = cloud.model,
                    size = cloud.size,
                    colour = cloud.colour
                )

                if (existing != null) {
                    alreadyPresent++
                } else {
                    masterDao.insert(
                        MasterEntity(
                            type = cloud.type,
                            brand = cloud.brand,
                            model = cloud.model,
                            size = cloud.size,
                            colour = cloud.colour,
                            lastSellingPrice = null,
                            lastSoldAt = null
                        )
                    )
                    inserted++
                }

                owners += owner
            }

            MasterBootstrapResult(
                fetched = masters.size,
                inserted = inserted,
                alreadyPresent = alreadyPresent,
                preservedLocalConflicts = preservedConflicts
            )
        }
    }

    private fun validateCanonicalMasters(
        masters: List<CloudMaster>
    ) {
        val ownersByModel = mutableMapOf<String, Pair<String, String>>()

        masters.forEach { cloud ->
            val modelKey = normalize(cloud.model)
            val owner = normalize(cloud.type) to normalize(cloud.brand)
            val previous = ownersByModel[modelKey]

            if (previous != null && previous != owner) {
                throw IllegalStateException(
                    "MASTERS invalid: conflicting ownership for model ${cloud.model}"
                )
            }

            ownersByModel[modelKey] = owner
        }
    }

    private fun parseMasters(
        array: com.google.gson.JsonArray
    ): List<CloudMaster> {
        return array.mapIndexed { index, element ->
            if (!element.isJsonObject) {
                throw IllegalStateException("MASTERS invalid row at index $index")
            }

            val obj = element.asJsonObject
            CloudMaster(
                type = requiredString(obj, "type", index),
                brand = requiredString(obj, "brand", index),
                model = requiredString(obj, "model", index),
                size = requiredString(obj, "size", index),
                colour = requiredString(obj, "colour", index)
            )
        }
    }

    private fun requiredString(
        obj: JsonObject,
        field: String,
        index: Int
    ): String {
        val value = obj.get(field)?.takeIf { !it.isJsonNull }?.asString?.trim()
        if (value.isNullOrBlank()) {
            throw IllegalStateException("MASTERS invalid $field at index $index")
        }
        return value
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private data class CloudMaster(
        val type: String,
        val brand: String,
        val model: String,
        val size: String,
        val colour: String
    )
}
