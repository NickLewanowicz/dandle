package app.dandle.data.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin PocketBase REST client (brief 05 §5.3): hand-rolled on the project's OkHttp in the
 * LoginFlow style — no Ktor SDK, same debug BB/API logging story, MockWebServer-testable.
 * Only the surface the cloud backend needs: password auth + record CRUD + filtered lists.
 */
class PocketBaseClient(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json,
) {

    /**
     * P3 (QA 2026-10-31): the configured base ends in "/" (CLOUD_BASE_URL), so raw concatenation
     * produced double-slash URLs ("…/​/api/…"). Normalize ONCE here; every URL below builds
     * from [base]. The HttpUrl.Builder in listRecords gets the pre-trimmed value too, so
     * host() can never receive a trailing slash.
     */
    private val base = baseUrl.trimEnd('/')

    // -- Wire shapes ----------------------------------------------------------

    data class AuthToken(val token: String, val userId: String)

    data class Record(
        val id: String,
        val updated: String,
        val fields: JsonObject,
    )

    data class RecordPage(val items: List<Record>, val cursorUpdated: String?)

    // -- Auth -----------------------------------------------------------------

    /** POST /api/collections/users/auth-with-password (identity = username). */
    fun authWithPassword(username: String, password: String): AuthToken {
        val body = buildJsonObject {
            put("identity", username)
            put("password", password)
        }
        val response = post("api/collections/users/auth-with-password", body)
        val obj = parse(response, allow404 = false)
        return AuthToken(
            token = obj.str("token"),
            userId = obj.obj("record").str("id"),
        )
    }

    /** POST /api/collections/users/records (account creation; extra fields ride `fields`). */
    fun createUser(username: String, password: String, fields: JsonObject): String {
        val body = JsonObject(
            fields.toMutableMap().apply {
                put("username", kotlinx.serialization.json.JsonPrimitive(username))
                put("password", kotlinx.serialization.json.JsonPrimitive(password))
                put("passwordConfirm", kotlinx.serialization.json.JsonPrimitive(password))
            }
        )
        return parse(post("api/collections/users/records", body), allow404 = false).str("id")
    }

    /** GET a single users record (envelope fields). */
    fun getUserRecord(token: String, userId: String): JsonObject {
        val request = Request.Builder()
            .url("$base/api/collections/users/records/$userId")
            .header("Authorization", token)
            .get()
            .build()
        return parse(send(request), allow404 = false)
    }

    /** PATCH a users record (envelope fields after passphrase change; superuser-free). */
    fun patchUser(token: String, userId: String, fields: JsonObject) {
        val request = Request.Builder()
            .url("$base/api/collections/users/records/$userId")
            .header("Authorization", token)
            .patch(fields.toString().toRequestBody(JSON))
            .build()
        parse(send(request), allow404 = false)
    }

    // -- Records --------------------------------------------------------------

    fun createRecord(token: String, collection: String, fields: JsonObject): String {
        val request = Request.Builder()
            .url("$base/api/collections/$collection/records")
            .header("Authorization", token)
            .post(fields.toString().toRequestBody(JSON))
            .build()
        return parse(send(request), allow404 = false).str("id")
    }

    fun updateRecord(token: String, collection: String, recordId: String, fields: JsonObject) {
        val request = Request.Builder()
            .url("$base/api/collections/$collection/records/$recordId")
            .header("Authorization", token)
            .patch(fields.toString().toRequestBody(JSON))
            .build()
        parse(send(request), allow404 = false)
    }

    /**
     * Delta pull: records for [userId] newer than [sinceUpdated] (PocketBase autodate string),
     * oldest first so the cursor always advances. The filter is percent-encoded via URL query
     * building — the MockWebServer contract test pins the encoding (classic hand-rolled bug).
     */
    fun listRecords(token: String, collection: String, userId: String, sinceUpdated: String?): RecordPage {
        val filter = if (sinceUpdated.isNullOrBlank()) {
            "(user='$userId')"
        } else {
            "(user='$userId' && updated>'$sinceUpdated')"
        }
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(base.removePrefix("https://").removePrefix("http://"))
            .addPathSegments("api/collections/$collection/records")
            .addQueryParameter("filter", filter)
            .addQueryParameter("sort", "updated")
            .addQueryParameter("perPage", "200")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", token)
            .get()
            .build()
        val obj = parse(send(request), allow404 = false)
        val items = (obj["items"] as? kotlinx.serialization.json.JsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.toRecord() }
        return RecordPage(items, items.lastOrNull()?.updated)
    }

    // -- Plumbing ---------------------------------------------------------------

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun post(path: String, body: JsonObject): okhttp3.Response {
        val request = Request.Builder()
            .url("$base/$path")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return send(request)
    }

    private fun send(request: Request): okhttp3.Response =
        client.newCall(request).execute()

    private fun parse(response: okhttp3.Response, allow404: Boolean): JsonObject {
        response.use {
            val text = it.body?.string()
            when {
                it.isSuccessful -> return json.parseToJsonElement(text ?: "{}").jsonObject
                it.code == 404 && allow404 -> return JsonObject(emptyMap())
                else -> throw PocketBaseException(it.code, text)
            }
        }
    }

    private fun JsonObject.toRecord(): Record? {
        val id = (this["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val updated = (this["updated"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return Record(id, updated, this)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: error("PocketBase response missing '$key'")

    private fun JsonObject.obj(key: String): JsonObject =
        (this[key] as? JsonObject) ?: error("PocketBase response missing object '$key'")
}

/** Typed transport failure for the cloud boundary (mapped to BackendError by the adapter). */
class PocketBaseException(val status: Int, val body: String?) :
    Exception("PocketBase HTTP $status${body?.take(200)?.let { ": $it" } ?: ""}")
