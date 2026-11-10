package io.github.ddastoor.eventtracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Supplies OAuth access tokens for the signed-in Google account. */
interface TokenProvider {
    /** Returns a valid access token, or throws [AuthRequiredException]. */
    suspend fun token(): String

    /** Called when Google rejected [token]; the next [token] call must return a fresh one. */
    suspend fun invalidate(token: String)
}

/** The user has to (re-)approve access in the Google account UI. */
class AuthRequiredException(message: String) : IOException(message)

/** A Google API returned an error response. */
class ApiException(val code: Int, message: String) : IOException(message)

data class Endpoints(
    val drive: String = "https://www.googleapis.com/drive/v3",
    val upload: String = "https://www.googleapis.com/upload/drive/v3",
    val sheets: String = "https://sheets.googleapis.com/v4",
    val userInfo: String = "https://www.googleapis.com/oauth2/v3/userinfo",
)

data class DriveFile(val id: String, val name: String, val mimeType: String)

/** The Drive and Sheets operations the backend needs (an in-memory fake implements it in tests). */
interface GoogleService {
    suspend fun findByAppProperty(key: String, value: String): List<DriveFile>
    /** File id -> modifiedTime for all files tagged with [key] = one of [values]. */
    suspend fun modifiedTimes(key: String, values: List<String>): Map<String, String>
    suspend fun createFile(name: String, mimeType: String, parentId: String?, appProperties: Map<String, String>): DriveFile
    suspend fun upload(file: File, name: String, mimeType: String, parentId: String): DriveFile
    suspend fun trash(fileId: String)
    suspend fun sheetIds(spreadsheetId: String): Map<String, Int>
    suspend fun batchUpdate(spreadsheetId: String, requests: List<JsonObject>): JsonObject
    suspend fun batchGet(spreadsheetId: String, ranges: List<String>): List<List<List<JsonElement>>>
    suspend fun updateValues(spreadsheetId: String, range: String, rows: List<List<JsonElement>>)
    suspend fun appendValues(spreadsheetId: String, range: String, rows: List<List<JsonElement>>)
}

/**
 * Thin REST client for the parts of Google Drive and Google Sheets the app uses. Only the
 * `drive.file` scope is needed: the app can see the files it created and nothing else.
 */
class GoogleApi(
    private val tokens: TokenProvider,
    private val endpoints: Endpoints = Endpoints(),
    private val http: OkHttpClient = defaultClient(),
) : GoogleService {
    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- account

    suspend fun userEmail(): String? =
        getJson(endpoints.userInfo.toHttpUrl())["email"]?.jsonPrimitive?.contentOrNull

    // ---------------------------------------------------------------- Drive

    override suspend fun findByAppProperty(key: String, value: String): List<DriveFile> {
        val url = "${endpoints.drive}/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "appProperties has { key='$key' and value='$value' } and trashed=false")
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("orderBy", "createdTime")
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .build()
        return getJson(url)["files"]?.jsonArray.orEmpty().map { it.jsonObject.toDriveFile() }
    }

    override suspend fun modifiedTimes(key: String, values: List<String>): Map<String, String> {
        val q = values.joinToString(" or ", "(", ")") { "appProperties has { key='$key' and value='$it' }" }
        val url = "${endpoints.drive}/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$q and trashed=false")
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("fields", "files(id,modifiedTime)")
            .build()
        return getJson(url)["files"]?.jsonArray.orEmpty().associate {
            val o = it.jsonObject
            o["id"]!!.jsonPrimitive.content to o["modifiedTime"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun createFile(
        name: String,
        mimeType: String,
        parentId: String?,
        appProperties: Map<String, String>,
    ): DriveFile {
        val url = "${endpoints.drive}/files".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,name,mimeType").build()
        val body = buildJsonObject {
            put("name", name)
            put("mimeType", mimeType)
            if (parentId != null) putJsonArray("parents") { add(JsonPrimitive(parentId)) }
            putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
        }
        return sendJson("POST", url, body).toDriveFile()
    }

    /** Uploads a local file with a resumable upload session (works for any size). */
    override suspend fun upload(file: File, name: String, mimeType: String, parentId: String): DriveFile {
        val startUrl = "${endpoints.upload}/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,name,mimeType").build()
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", mimeType)
            putJsonArray("parents") { add(JsonPrimitive(parentId)) }
            putJsonObject("appProperties") { put(APP_KEY, "attachment") }
        }
        val sessionUrl = execute {
            it.url(startUrl)
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", file.length().toString())
                .post(meta.toString().toRequestBody(JSON))
        }.use { resp -> resp.header("Location") ?: throw ApiException(resp.code, "Upload session not created") }
        return execute { it.url(sessionUrl).put(file.asRequestBody(mimeType.toMediaType())) }
            .use { resp -> json.parseToJsonElement(resp.body!!.string()).jsonObject.toDriveFile() }
    }

    /** Moves a file to the Drive trash (recoverable for 30 days). Missing files are ignored. */
    override suspend fun trash(fileId: String) {
        val url = "${endpoints.drive}/files/$fileId".toHttpUrl()
        try {
            sendJson("PATCH", url, buildJsonObject { put("trashed", true) })
        } catch (e: ApiException) {
            if (e.code != 404 && e.code != 403) throw e
        }
    }

    suspend fun download(fileId: String, dest: File) {
        val url = "${endpoints.drive}/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media").build()
        execute { it.url(url).get() }.use { resp ->
            val tmp = File(dest.parentFile, dest.name + ".part")
            withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                if (!tmp.renameTo(dest)) throw IOException("Could not save ${dest.name}")
            }
        }
    }

    // ---------------------------------------------------------------- Sheets

    /** Returns sheet title -> sheetId. */
    override suspend fun sheetIds(spreadsheetId: String): Map<String, Int> {
        val url = "${endpoints.sheets}/spreadsheets/$spreadsheetId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "sheets.properties(sheetId,title)").build()
        return getJson(url)["sheets"]?.jsonArray.orEmpty().associate {
            val p = it.jsonObject["properties"]!!.jsonObject
            p["title"]!!.jsonPrimitive.content to p["sheetId"]!!.jsonPrimitive.content.toInt()
        }
    }

    override suspend fun batchUpdate(spreadsheetId: String, requests: List<JsonObject>): JsonObject {
        if (requests.isEmpty()) return JsonObject(emptyMap())
        val url = "${endpoints.sheets}/spreadsheets/$spreadsheetId:batchUpdate".toHttpUrl()
        return sendJson("POST", url, buildJsonObject { put("requests", JsonArray(requests)) })
    }

    /** Reads several ranges; each result is a list of rows (trailing empty cells are omitted). */
    override suspend fun batchGet(spreadsheetId: String, ranges: List<String>): List<List<List<JsonElement>>> {
        val b = "${endpoints.sheets}/spreadsheets/$spreadsheetId/values:batchGet".toHttpUrl().newBuilder()
        ranges.forEach { b.addQueryParameter("ranges", it) }
        b.addQueryParameter("valueRenderOption", "UNFORMATTED_VALUE")
        b.addQueryParameter("majorDimension", "ROWS")
        val valueRanges = getJson(b.build())["valueRanges"]?.jsonArray.orEmpty()
        return ranges.indices.map { i -> SheetSchema.rows(valueRanges.getOrNull(i)?.jsonObject?.get("values")) }
    }

    override suspend fun updateValues(spreadsheetId: String, range: String, rows: List<List<JsonElement>>) {
        val url = "${endpoints.sheets}/spreadsheets/$spreadsheetId/values".toHttpUrl().newBuilder()
            .addPathSegment(range)
            .addQueryParameter("valueInputOption", "RAW").build()
        sendJson("PUT", url, valuesBody(range, rows))
    }

    override suspend fun appendValues(spreadsheetId: String, range: String, rows: List<List<JsonElement>>) {
        val url = "${endpoints.sheets}/spreadsheets/$spreadsheetId/values".toHttpUrl().newBuilder()
            .addPathSegment("$range:append")
            .addQueryParameter("valueInputOption", "RAW")
            .addQueryParameter("insertDataOption", "INSERT_ROWS").build()
        sendJson("POST", url, valuesBody(range, rows))
    }

    private fun valuesBody(range: String, rows: List<List<JsonElement>>) = buildJsonObject {
        put("range", range)
        put("majorDimension", "ROWS")
        put("values", JsonArray(rows.map { JsonArray(it) }))
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun getJson(url: HttpUrl): JsonObject =
        execute { it.url(url).get() }.use { parseObject(it) }

    private suspend fun sendJson(method: String, url: HttpUrl, body: JsonElement): JsonObject =
        execute { it.url(url).method(method, body.toString().toRequestBody(JSON)) }.use { parseObject(it) }

    private fun parseObject(resp: Response): JsonObject {
        val text = resp.body?.string().orEmpty()
        if (text.isBlank()) return JsonObject(emptyMap())
        return json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
    }

    /**
     * Runs a request with a bearer token. Retries once with a fresh token on 401 and a few times
     * with backoff on rate limiting / server errors. Returns only successful responses.
     */
    private suspend fun execute(build: (Request.Builder) -> Request.Builder): Response {
        var attempt = 0
        var refreshedToken = false
        while (true) {
            val token = tokens.token()
            val request = build(Request.Builder()).header("Authorization", "Bearer $token").build()
            val resp = withContext(Dispatchers.IO) { http.newCall(request).execute() }
            if (resp.isSuccessful) return resp
            val code = resp.code
            val errorText = resp.body?.string().orEmpty()
            resp.close()
            when {
                code == 401 && !refreshedToken -> {
                    refreshedToken = true
                    tokens.invalidate(token)
                }
                (code == 429 || code >= 500 || isRateLimit(code, errorText)) && attempt < 3 -> {
                    delay(1000L shl attempt)
                    attempt++
                }
                code == 401 -> throw AuthRequiredException("Google rejected the sign-in. Please sign in again.")
                code == 403 && isMissingScope(errorText) -> {
                    // The user didn't tick the Google Drive permission. Drop the token so the next
                    // authorization asks again.
                    tokens.invalidate(token)
                    throw AuthRequiredException(DRIVE_PERMISSION_MISSING)
                }
                else -> throw ApiException(code, describeError(code, errorText))
            }
        }
    }

    private fun isMissingScope(body: String) =
        body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT") || body.contains("insufficient authentication scopes", ignoreCase = true)

    private fun isRateLimit(code: Int, body: String) =
        code == 403 && (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded"))

    private fun describeError(code: Int, body: String): String {
        val message = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(200)
        val hint = when {
            message.contains("has not been used in project") || message.contains("is disabled") ->
                " — enable the Google Drive API and Google Sheets API in your Google Cloud project."
            else -> ""
        }
        return "Google API error $code: $message$hint"
    }

    private fun JsonObject.toDriveFile() = DriveFile(
        id = this["id"]!!.jsonPrimitive.content,
        name = this["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        mimeType = this["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    companion object {
        const val APP_KEY = "eventtracker"
        const val DRIVE_PERMISSION_MISSING =
            "Event Tracker needs permission to use its own files in your Google Drive. Tap Reconnect " +
                "and make sure the Google Drive box is ticked on Google's screen."
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
