package io.github.ddastoor.eventtracker.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * In-memory stand-in for Google Drive + Sheets, faithful to the behaviors the backend relies on:
 * empty trailing cells/rows are omitted from reads, appends go after the last non-empty row,
 * deleting rows shifts the rows below, and every change bumps the file's modifiedTime.
 */
class FakeGoogle : GoogleService {
    data class FakeFile(
        val id: String,
        val name: String,
        val mimeType: String,
        val parent: String?,
        val props: Map<String, String>,
        var modified: Long,
        var trashed: Boolean = false,
        val content: ByteArray? = null,
    )

    class Tab(val sheetId: Int, var title: String) {
        val rows: MutableList<MutableList<JsonElement>> = mutableListOf()
    }

    val files = linkedMapOf<String, FakeFile>()
    val spreadsheets = mutableMapOf<String, MutableList<Tab>>()
    /** Number of value reads per spreadsheet id. */
    val reads = mutableMapOf<String, Int>()
    private var clock = 0L
    private var nextId = 0
    private var nextSheetId = 100

    private fun touch(id: String) {
        files[id]?.modified = ++clock
    }

    override suspend fun findByAppProperty(key: String, value: String): List<DriveFile> =
        files.values.filter { !it.trashed && it.props[key] == value }.map { DriveFile(it.id, it.name, it.mimeType) }

    override suspend fun modifiedTimes(key: String, values: List<String>): Map<String, String> =
        files.values.filter { !it.trashed && it.props[key] in values }.associate { it.id to it.modified.toString() }

    override suspend fun createFile(name: String, mimeType: String, parentId: String?, appProperties: Map<String, String>): DriveFile {
        val id = "file${++nextId}"
        files[id] = FakeFile(id, name, mimeType, parentId, appProperties, ++clock)
        if (mimeType == SheetsBackend.SHEET_MIME) spreadsheets[id] = mutableListOf(Tab(0, "Sheet1"))
        return DriveFile(id, name, mimeType)
    }

    override suspend fun upload(file: File, name: String, mimeType: String, parentId: String): DriveFile {
        val id = "upload${++nextId}"
        files[id] = FakeFile(id, name, mimeType, parentId, mapOf(GoogleApi.APP_KEY to "attachment"), ++clock, content = file.readBytes())
        return DriveFile(id, name, mimeType)
    }

    override suspend fun trash(fileId: String) {
        files[fileId]?.let { it.trashed = true; touch(fileId) }
    }

    override suspend fun sheetIds(spreadsheetId: String): Map<String, Int> =
        tabs(spreadsheetId).associate { it.title to it.sheetId }

    override suspend fun batchUpdate(spreadsheetId: String, requests: List<JsonObject>): JsonObject {
        val tabs = tabs(spreadsheetId)
        for (r in requests) {
            r["addSheet"]?.let { a ->
                val title = a.jsonObject["properties"]!!.jsonObject["title"]!!.jsonPrimitive.content
                require(tabs.none { it.title == title }) { "duplicate tab $title" }
                tabs += Tab(nextSheetId++, title)
            }
            r["updateSheetProperties"]?.let { u ->
                val p = u.jsonObject["properties"]!!.jsonObject
                val tab = tabs.first { it.sheetId == p["sheetId"]!!.jsonPrimitive.int }
                p["title"]?.let { tab.title = it.jsonPrimitive.content }
            }
            r["deleteDimension"]?.let { d ->
                val range = d.jsonObject["range"]!!.jsonObject
                val tab = tabs.first { it.sheetId == range["sheetId"]!!.jsonPrimitive.int }
                val start = range["startIndex"]!!.jsonPrimitive.int
                val end = range["endIndex"]!!.jsonPrimitive.int
                repeat(end - start) { if (start < tab.rows.size) tab.rows.removeAt(start) }
            }
        }
        touch(spreadsheetId)
        return JsonObject(emptyMap())
    }

    override suspend fun batchGet(spreadsheetId: String, ranges: List<String>): List<List<List<JsonElement>>> {
        reads[spreadsheetId] = (reads[spreadsheetId] ?: 0) + 1
        return ranges.map { read(spreadsheetId, it) }
    }

    override suspend fun updateValues(spreadsheetId: String, range: String, rows: List<List<JsonElement>>) {
        val r = parse(range)
        val tab = tab(spreadsheetId, r.tab)
        rows.forEachIndexed { i, values ->
            values.forEachIndexed { j, v -> set(tab, r.startRow - 1 + i, r.startCol + j, v) }
        }
        touch(spreadsheetId)
    }

    override suspend fun appendValues(spreadsheetId: String, range: String, rows: List<List<JsonElement>>) {
        val r = parse(range)
        val tab = tab(spreadsheetId, r.tab)
        var at = tab.rows.indexOfLast { row -> row.any { !it.isEmpty() } } + 1
        rows.forEach { values ->
            values.forEachIndexed { j, v -> set(tab, at, r.startCol + j, v) }
            at++
        }
        touch(spreadsheetId)
    }

    /** Values of a whole tab, for assertions. */
    fun dump(spreadsheetId: String, title: String): List<List<String>> =
        tab(spreadsheetId, title).rows.map { row -> row.map { (it as JsonPrimitive).content } }

    private fun read(spreadsheetId: String, range: String): List<List<JsonElement>> {
        val r = parse(range)
        val tab = tab(spreadsheetId, r.tab)
        val last = r.endRow?.let { minOf(it, tab.rows.size) } ?: tab.rows.size
        val out = ((r.startRow - 1) until last).map { i ->
            val row = tab.rows[i]
            val endCol = r.endCol ?: (row.size - 1)
            val cells = (r.startCol..endCol).map { row.getOrNull(it) ?: EMPTY }
            cells.dropLastWhile { it.isEmpty() }
        }
        return out.dropLastWhile { it.isEmpty() }
    }

    private fun set(tab: Tab, row: Int, col: Int, v: JsonElement) {
        while (tab.rows.size <= row) tab.rows += mutableListOf<JsonElement>()
        val cells = tab.rows[row]
        while (cells.size <= col) cells += EMPTY
        cells[col] = v
    }

    private fun tabs(id: String) = spreadsheets[id] ?: throw ApiException(404, "No spreadsheet $id")

    private fun tab(id: String, title: String) =
        tabs(id).firstOrNull { it.title == title } ?: throw ApiException(400, "Unable to parse range: $title")

    private data class Range(val tab: String, val startCol: Int, val startRow: Int, val endCol: Int?, val endRow: Int?)

    private fun parse(range: String): Range {
        val m = Regex("""^(\w+)!([A-Z]+)(\d+)?(?::([A-Z]+)(\d+)?)?$""").matchEntire(range) ?: error("bad range $range")
        val (tab, c1, r1, c2, r2) = m.destructured
        return Range(tab, col(c1), r1.ifEmpty { "1" }.toInt(), c2.ifEmpty { null }?.let(::col), r2.ifEmpty { null }?.toInt())
    }

    private fun col(letters: String) = letters.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1

    private fun JsonElement.isEmpty() = (this as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()

    private companion object {
        val EMPTY = JsonPrimitive("")
    }
}
