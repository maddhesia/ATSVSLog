package com.sma.atsvslog.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sma.atsvslog.network.AtSvsApi

sealed interface CloudModelOwnershipResult {
    data object Unavailable : CloudModelOwnershipResult
    data object NoConflict : CloudModelOwnershipResult

    data class Conflict(
        val model: String,
        val canonicalType: String,
        val canonicalBrand: String
    ) : CloudModelOwnershipResult
}

/**
 * Performs an authoritative online ownership check for a newly entered Model.
 * The cloud Masters dataset is authoritative when reachable; an unavailable
 * network is deliberately returned as Unavailable so the caller can preserve
 * the app's offline-first behaviour.
 */
class CloudMasterRepository(
    private val api: AtSvsApi
) {
    suspend fun checkModelOwnership(
        model: String,
        requestedType: String,
        requestedBrand: String
    ): CloudModelOwnershipResult {
        return try {
            val response = api.masters()
            if (!response.isSuccessful) {
                return CloudModelOwnershipResult.Unavailable
            }

            val body = response.body()
                ?: return CloudModelOwnershipResult.Unavailable

            if (!body.success) {
                return CloudModelOwnershipResult.Unavailable
            }

            val masters = body.payload
                ?.getAsJsonArray("masters")
                ?: return CloudModelOwnershipResult.Unavailable

            val matchingOwners = parseOwners(masters, model)
            val requestedTypeKey = normalize(requestedType)
            val requestedBrandKey = normalize(requestedBrand)

            val conflict = matchingOwners.firstOrNull { (type, brand) ->
                normalize(type) != requestedTypeKey ||
                    normalize(brand) != requestedBrandKey
            }

            if (conflict != null) {
                CloudModelOwnershipResult.Conflict(
                    model = model,
                    canonicalType = conflict.first,
                    canonicalBrand = conflict.second
                )
            } else {
                CloudModelOwnershipResult.NoConflict
            }
        } catch (_: Exception) {
            CloudModelOwnershipResult.Unavailable
        }
    }

    private fun parseOwners(
        masters: JsonArray,
        model: String
    ): List<Pair<String, String>> {
        val modelKey = normalize(model)
        val owners = linkedSetOf<Pair<String, String>>()

        masters.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val obj: JsonObject = element.asJsonObject
            val rowModel = obj.get("model")?.asString?.trim().orEmpty()
            if (normalize(rowModel) == modelKey) {
                val type = obj.get("type")?.asString?.trim().orEmpty()
                val brand = obj.get("brand")?.asString?.trim().orEmpty()
                if (type.isNotBlank() && brand.isNotBlank()) {
                    owners += type to brand
                }
            }
        }

        return owners.toList()
    }

    private fun normalize(value: String): String =
        value.trim().lowercase()
}
