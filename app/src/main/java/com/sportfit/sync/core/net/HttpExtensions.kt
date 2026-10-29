package com.sportfit.sync.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Response
import java.io.IOException

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

fun Response.bodyBytesOrThrow(): ByteArray {
    val body = body ?: throw IOException("Empty response body (HTTP $code)")
    return body.bytes()
}

fun Response.bodyTextOrThrow(): String {
    val body = body ?: throw IOException("Empty response body (HTTP $code)")
    return body.string()
}

fun Response.requireSuccess(): Response {
    if (!isSuccessful) {
        val snippet = runCatching { peekBody(2048).string() }.getOrDefault("")
        throw IOException("HTTP $code ${request.url.encodedPath} — $snippet")
    }
    return this
}

fun Response.parseJsonOrThrow(): JsonElement = AppJson.parseToJsonElement(bodyTextOrThrow())

// ---------------------------------------------------------------------------
// Lenient JSON navigation.
//
// Every platform returns hand-rolled JSON whose shape drifts (numeric ids as
// strings, nulls where objects are expected, `data` sometimes an object and
// sometimes an array). Navigating `JsonElement` directly keeps the parsers
// honest and avoids a brittle strict-model layer.
// ---------------------------------------------------------------------------

fun String.parseJsonLoose(): JsonElement =
    AppJson.parseToJsonElement(this)

fun JsonElement?.orNull(): JsonElement? = if (this == null || this is JsonNull) null else this

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

fun JsonElement?.str(key: String): String? {
    val value = (this as? JsonObject)?.get(key) ?: return null
    return (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNullSafe()
}

fun JsonElement?.long(key: String): Long? =
    (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.longOrNull }

fun JsonElement?.int(key: String): Int? = long(key)?.toInt()

fun JsonElement?.double(key: String): Double? =
    (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.doubleOrNull }

fun JsonElement?.bool(key: String): Boolean? =
    (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.booleanOrNull }

private fun JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()

/** Reads a numeric field that some endpoints send as a string. */
fun JsonElement?.number(key: String): Double? {
    val value = (this as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
    value.doubleOrNull?.let { return it }
    return value.contentOrNullSafe()?.toDoubleOrNull()
}
