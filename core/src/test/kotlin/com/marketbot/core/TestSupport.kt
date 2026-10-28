package com.marketbot.core

import com.marketbot.core.util.JSON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

fun resource(path: String): String =
    (Thread.currentThread().contextClassLoader.getResource(path) ?: error("missing test resource $path")).readText()

fun golden(name: String): JsonElement = JSON.parseToJsonElement(resource("golden/$name.json"))
fun goldenCases(name: String): List<JsonObject> = (golden(name) as JsonArray).map { it as JsonObject }
fun payload(name: String): JsonObject = JSON.parseToJsonElement(resource("payloads/$name.json")) as JsonObject

val JsonElement?.d: Double? get() = (this as? JsonPrimitive)?.takeUnless { this is JsonNull }?.doubleOrNull
val JsonElement?.dd: Double get() = d ?: fail("expected a number, got $this")
val JsonElement?.s: String? get() = (this as? JsonPrimitive)?.takeUnless { this is JsonNull }?.content
val JsonElement?.b: Boolean? get() = (this as? JsonPrimitive)?.takeUnless { this is JsonNull }?.booleanOrNull
val JsonElement?.i: Int get() = dd.toInt()
val JsonElement?.isNull: Boolean get() = this == null || this is JsonNull
fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
fun JsonElement?.o(): JsonObject = this as JsonObject

fun assertClose(expected: Double?, actual: Double?, tol: Double = 1e-9, what: () -> String = { "" }) {
    if (expected == null || actual == null) {
        if (expected != actual) fail("${what()}: expected $expected, got $actual")
        return
    }
    assertTrue(abs(expected - actual) <= tol * maxOf(1.0, abs(expected)), "${what()}: expected $expected, got $actual")
}
