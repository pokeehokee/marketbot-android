package com.marketbot.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

fun parseObject(text: String): JsonObject = JSON.parseToJsonElement(text) as? JsonObject
    ?: throw IllegalArgumentException("expected a JSON object")

/** The element at `key`, or null when absent or JSON null. */
fun JsonObject.el(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

fun JsonObject.obj(key: String): JsonObject? = el(key) as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = el(key) as? JsonArray
fun JsonObject.objects(key: String): List<JsonObject> = arr(key)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** A string field, or null. Numbers are rendered as their text. */
fun JsonObject.str(key: String): String? = (el(key) as? JsonPrimitive)?.contentOrNull

fun JsonObject.bool(key: String): Boolean? = (el(key) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.toBooleanStrictOrNull() }

fun JsonObject.long(key: String): Long? = (el(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

/**
 * A number field. Kalshi and Polymarket send prices as decimal strings
 * ("0.0300"), sizes as fixed-point strings ("212.00"), and some fields as bare
 * numbers; all three are accepted. Unparseable text or an empty string is null,
 * never 0 -- a missing number must stay missing.
 */
fun JsonObject.num(key: String): Double? {
    val p = el(key) as? JsonPrimitive ?: return null
    val v = p.doubleOrNull ?: p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    return v?.takeIf { it.isFinite() }
}

/** Polymarket amounts are `{"value": "0.0300", "currency": "USD"}`; also accepts a bare number. */
fun JsonObject.amount(key: String): Double? = when (val e = el(key)) {
    is JsonObject -> e.num("value")
    is JsonPrimitive -> num(key)
    else -> null
}

/** First present numeric field among [keys], with the key it came from. */
fun JsonObject.firstNum(vararg keys: String): Pair<String, Double>? {
    for (k in keys) num(k)?.let { return k to it }
    return null
}

fun JsonObject.has(key: String): Boolean = el(key) != null
