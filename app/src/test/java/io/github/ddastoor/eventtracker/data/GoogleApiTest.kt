package io.github.ddastoor.eventtracker.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GoogleApiTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val tokens = object : TokenProvider {
        var current = "token-1"
        val invalidated = mutableListOf<String>()
        override suspend fun token() = current
        override suspend fun invalidate(token: String) {
            invalidated += token
            current = "token-2"
        }
    }
    private val api = GoogleApi(
        tokens,
        Endpoints(drive = "$base/drive/v3", upload = "$base/upload/drive/v3", sheets = "$base/v4", userInfo = "$base/userinfo"),
    )

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `batchGet sends ranges and parses rows`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"valueRanges":[{"values":[["a",1],["b"]]},{}]}"""))
        val result = api.batchGet("sheet1", listOf("Events!A2:H", "Shards!A2:D"))
        assertEquals(listOf(listOf(JsonPrimitive("a"), JsonPrimitive(1)), listOf(JsonPrimitive("b"))), result[0])
        assertEquals(emptyList<Any>(), result[1])
        val req = server.takeRequest()
        assertEquals("Bearer token-1", req.getHeader("Authorization"))
        assertEquals("/v4/spreadsheets/sheet1/values:batchGet", req.requestUrl!!.encodedPath)
        assertEquals(listOf("Events!A2:H", "Shards!A2:D"), req.requestUrl!!.queryParameterValues("ranges"))
        assertEquals("UNFORMATTED_VALUE", req.requestUrl!!.queryParameter("valueRenderOption"))
    }

    @Test
    fun `append and update use RAW input`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        api.appendValues("s", "Events!A1:H1", listOf(listOf(JsonPrimitive("x"), JsonPrimitive(5))))
        api.updateValues("s", "Events!A7:H7", listOf(listOf(JsonPrimitive("y"))))
        val append = server.takeRequest()
        assertEquals("POST", append.method)
        assertEquals("/v4/spreadsheets/s/values/Events!A1:H1:append", append.requestUrl!!.encodedPath)
        assertEquals("RAW", append.requestUrl!!.queryParameter("valueInputOption"))
        assertEquals("INSERT_ROWS", append.requestUrl!!.queryParameter("insertDataOption"))
        assertTrue(append.body.readUtf8().contains(""""values":[["x",5]]"""))
        val update = server.takeRequest()
        assertEquals("PUT", update.method)
        assertEquals("/v4/spreadsheets/s/values/Events!A7:H7", update.requestUrl!!.encodedPath)
    }

    @Test
    fun `expired token is refreshed once and the request retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired"}}"""))
        server.enqueue(MockResponse().setBody("""{"email":"me@example.com"}"""))
        assertEquals("me@example.com", api.userEmail())
        assertEquals(listOf("token-1"), tokens.invalidated)
        server.takeRequest()
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `missing Drive permission asks the user to reconnect`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"message":"Request had insufficient authentication scopes.","status":"PERMISSION_DENIED","details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}""",
            ),
        )
        val e = runCatching { api.findByAppProperty("eventtracker", "root") }.exceptionOrNull()
        assertTrue(e is AuthRequiredException)
        assertEquals(GoogleApi.DRIVE_PERMISSION_MISSING, e!!.message)
        assertEquals(listOf("token-1"), tokens.invalidated)
    }

    @Test
    fun `api errors carry a helpful message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"message":"Google Sheets API has not been used in project 123 before or it is disabled."}}""",
            ),
        )
        val e = runCatching { api.sheetIds("s") }.exceptionOrNull() as ApiException
        assertEquals(403, e.code)
        assertTrue(e.message!!.contains("enable the Google Drive API and Google Sheets API"))
    }

    @Test
    fun `uploads use a resumable session`() = runBlocking {
        val file = Files.createTempFile("receipt", ".pdf").toFile().apply { writeText("%PDF-1.4") }
        server.enqueue(MockResponse().setHeader("Location", "$base/session/abc").setBody(""))
        server.enqueue(MockResponse().setBody("""{"id":"f1","name":"receipt.pdf","mimeType":"application/pdf"}"""))
        val uploaded = api.upload(file, "receipt.pdf", "application/pdf", "folder9")
        assertEquals(DriveFile("f1", "receipt.pdf", "application/pdf"), uploaded)
        val start = server.takeRequest()
        assertEquals("resumable", start.requestUrl!!.queryParameter("uploadType"))
        assertTrue(start.body.readUtf8().contains(""""parents":["folder9"]"""))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/session/abc", put.requestUrl!!.encodedPath)
        assertEquals("%PDF-1.4", put.body.readUtf8())
    }

    @Test
    fun `drive queries by app tag`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"a","modifiedTime":"2026-01-01T00:00:00Z"}]}"""))
        assertEquals(mapOf("a" to "2026-01-01T00:00:00Z"), api.modifiedTimes("eventtracker", listOf("data", "shard")))
        val q = server.takeRequest().requestUrl!!.queryParameter("q")!!
        assertEquals(
            "(appProperties has { key='eventtracker' and value='data' } or appProperties has { key='eventtracker' and value='shard' }) and trashed=false",
            q,
        )
    }
}
