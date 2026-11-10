package io.github.ddastoor.eventtracker.data

import io.github.ddastoor.eventtracker.data.GoogleApi.Companion.APP_KEY
import io.github.ddastoor.eventtracker.data.SheetSchema.EVENTS_LAST_COL
import io.github.ddastoor.eventtracker.data.SheetSchema.EVENTS_SHEET
import io.github.ddastoor.eventtracker.data.SheetSchema.EVENT_HEADERS
import io.github.ddastoor.eventtracker.data.SheetSchema.SHARDS_LAST_COL
import io.github.ddastoor.eventtracker.data.SheetSchema.SHARDS_SHEET
import io.github.ddastoor.eventtracker.data.SheetSchema.SHARD_HEADERS
import io.github.ddastoor.eventtracker.data.SheetSchema.TYPES_LAST_COL
import io.github.ddastoor.eventtracker.data.SheetSchema.TYPES_SHEET
import io.github.ddastoor.eventtracker.data.SheetSchema.TYPE_HEADERS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores the data in Google Sheets in the user's Drive:
 *
 *  My Drive / Event Tracker /
 *      Event Tracker Data        spreadsheet: EventTypes, Events (shard 1), Shards (the chain index)
 *      Event Tracker Events 2    spreadsheet: Events (shard 2), created automatically when shard 1 is full
 *      Event Tracker Events 3    ...
 *      Attachments/              uploaded receipts, photos, ...
 *
 * Events are split over a chain of spreadsheets ("shards") so no single sheet grows unmanageably
 * large. New events always go to the newest shard; when it holds [maxRowsPerShard] rows a new
 * spreadsheet is created and linked in the `Shards` index. Reads walk the whole chain but skip
 * shards whose Drive modifiedTime is unchanged since the last sync, so syncing stays fast as the
 * history grows. Files are found by a private `appProperties` tag, so they can be renamed or
 * moved in Drive and are found again after a reinstall.
 */
class SheetsBackend(
    private val api: GoogleService,
    private val maxRowsPerShard: Int = MAX_ROWS_PER_SHARD,
    private val clock: () -> Long = ::nowEpochSeconds,
) : RemoteBackend {

    data class Shard(val seq: Int, val spreadsheetId: String)

    private class Layout(
        val rootId: String,
        val mainId: String,
        val filesFolderId: String,
        val typesSheetId: Int,
    ) {
        @Volatile var shards: List<Shard> = emptyList()
        val active: Shard get() = shards.maxBy { it.seq }
    }

    private val lock = Mutex()
    @Volatile private var layout: Layout? = null
    /** Shard spreadsheet id -> sheetId of its Events tab (also marks the tab as verified). */
    private val eventSheetIds = ConcurrentHashMap<String, Int>()
    /** Shards written since the last fetch; always re-read even if Drive reports no change yet. */
    private val dirty: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    val spreadsheetUrl: String? get() = layout?.let { sheetUrl(it.mainId) }

    /** The current shard chain, oldest first (empty until the first sync). */
    val shards: List<Shard> get() = layout?.shards.orEmpty()

    private suspend fun ensureReady(): Layout = lock.withLock {
        layout?.let { return it }
        val rootId = findOrCreate(ROOT) { api.createFile("Event Tracker", FOLDER_MIME, null, tag(ROOT)).id }
        val filesId = findOrCreate(FILES) { api.createFile("Attachments", FOLDER_MIME, rootId, tag(FILES)).id }
        val mainId = findOrCreate(DATA) { api.createFile("Event Tracker Data", SHEET_MIME, rootId, tag(DATA)).id }
        val tabs = ensureTabs(
            mainId,
            listOf(TYPES_SHEET to TYPE_HEADERS, EVENTS_SHEET to EVENT_HEADERS, SHARDS_SHEET to SHARD_HEADERS),
        )
        eventSheetIds[mainId] = tabs.getValue(EVENTS_SHEET)
        val l = Layout(rootId, mainId, filesId, tabs.getValue(TYPES_SHEET))
        l.shards = loadChain(l)
        layout = l
        l
    }

    private suspend fun findOrCreate(tag: String, create: suspend () -> String): String =
        api.findByAppProperty(APP_KEY, tag).firstOrNull()?.id ?: create()

    /**
     * Reads the shard index. Repairs it if needed: the main spreadsheet is always shard 1, and a
     * shard file that exists in Drive but was never linked (app killed mid-rollover) is appended.
     */
    private suspend fun loadChain(l: Layout): List<Shard> {
        val rows = api.batchGet(l.mainId, listOf(shardsRange()))[0]
        val index = SheetSchema.parseShards(rows).map { Shard(it.first, it.second) }
        val added = mutableListOf<Shard>()
        if (index.isEmpty()) added += Shard(1, l.mainId)
        val known = (index + added).map { it.spreadsheetId }.toMutableSet()
        var next = (index + added).maxOf { it.seq }
        api.findByAppProperty(APP_KEY, SHARD).filter { it.id !in known }.forEach {
            added += Shard(++next, it.id)
            known += it.id
        }
        if (added.isNotEmpty()) {
            api.appendValues(l.mainId, "$SHARDS_SHEET!A1:${SHARDS_LAST_COL}1", added.map { SheetSchema.shardToRow(it.seq, it.spreadsheetId, clock()) })
        }
        return (index + added).sortedBy { it.seq }
    }

    /** Makes sure the listed tabs exist with a frozen header row. Returns title -> sheetId. */
    private suspend fun ensureTabs(spreadsheetId: String, tabs: List<Pair<String, List<String>>>): Map<String, Int> {
        var existing = api.sheetIds(spreadsheetId)
        val missing = tabs.filter { it.first !in existing }
        if (missing.isNotEmpty()) {
            // A brand-new spreadsheet has one empty default sheet: reuse it for the first table.
            val spare = existing.entries.singleOrNull()?.takeIf { e -> tabs.none { it.first == e.key } }
            val requests = missing.mapIndexed { i, (title, _) ->
                if (i == 0 && spare != null) renameSheet(spare.value, title) else addSheet(title)
            }
            api.batchUpdate(spreadsheetId, requests)
            existing = api.sheetIds(spreadsheetId)
        }
        val headerRanges = tabs.map { (title, headers) -> "$title!A1:${column(headers.size)}1" }
        val current = api.batchGet(spreadsheetId, headerRanges)
        tabs.forEachIndexed { i, (_, headers) ->
            if (current[i].firstOrNull().isNullOrEmpty()) {
                api.updateValues(spreadsheetId, headerRanges[i], listOf(headers.map(::JsonPrimitive)))
            }
        }
        return existing
    }

    private suspend fun eventsSheetId(shard: Shard): Int =
        eventSheetIds[shard.spreadsheetId]
            ?: ensureTabs(shard.spreadsheetId, listOf(EVENTS_SHEET to EVENT_HEADERS)).getValue(EVENTS_SHEET)
                .also { eventSheetIds[shard.spreadsheetId] = it }

    private suspend fun createShard(l: Layout): Shard {
        val seq = l.active.seq + 1
        val file = api.createFile("Event Tracker Events $seq", SHEET_MIME, l.rootId, tag(SHARD))
        val shard = Shard(seq, file.id)
        eventsSheetId(shard)
        api.appendValues(l.mainId, "$SHARDS_SHEET!A1:${SHARDS_LAST_COL}1", listOf(SheetSchema.shardToRow(seq, file.id, clock())))
        l.shards = l.shards + shard
        return shard
    }

    // ------------------------------------------------------------------ reading

    override suspend fun fetch(previous: Snapshot): Snapshot {
        val l = ensureReady()
        // Versions first: anything modified after this point gets a newer version next time.
        val versions = api.modifiedTimes(APP_KEY, listOf(DATA, SHARD))
        val (typeRows, shardRows) = api.batchGet(l.mainId, listOf("$TYPES_SHEET!A2:$TYPES_LAST_COL", shardsRange()))
        val indexed = SheetSchema.parseShards(shardRows).map { Shard(it.first, it.second) }
        // Another device may have extended the chain.
        l.shards = (l.shards + indexed).distinctBy { it.spreadsheetId }.sortedBy { it.seq }

        val types = SheetSchema.parseTypes(typeRows)
        val events = ArrayList<Event>()
        val read = mutableListOf<String>()
        for (shard in l.shards) {
            val id = shard.spreadsheetId
            val version = versions[id]
            if (version != null && previous.shardVersions[id] == version && id !in dirty) {
                previous.events.filterTo(events) { it.shard == id }
                continue
            }
            val rows = try {
                eventsSheetId(shard)
                api.batchGet(id, listOf("$EVENTS_SHEET!A2:$EVENTS_LAST_COL"))[0]
            } catch (e: ApiException) {
                if (e.code == 404) continue // shard file was deleted
                throw e
            }
            events += SheetSchema.parseEvents(rows, types, id)
            read += id
        }
        dirty.removeAll(read.toSet())
        val shardIds = l.shards.map { it.spreadsheetId }.toSet()
        return Snapshot(types, events.distinctBy { it.id }, versions.filterKeys { it in shardIds })
    }

    override suspend fun upload(file: File, name: String, mimeType: String): DriveFile =
        api.upload(file, name, mimeType, ensureReady().filesFolderId)

    // ------------------------------------------------------------------ writing

    /** Writes one queued change. Safe to repeat: every operation is an idempotent upsert/delete. */
    override suspend fun apply(op: Op) {
        val l = ensureReady()
        when (op) {
            is Op.UpsertType -> {
                val ids = api.batchGet(l.mainId, listOf("$TYPES_SHEET!A2:A"))[0]
                val index = ids.indexOfFirst { it.str(0) == op.type.id }
                writeRow(l.mainId, TYPES_SHEET, TYPES_LAST_COL, index.takeIf { it >= 0 }, SheetSchema.typeToRow(op.type))
            }
            is Op.DeleteType -> {
                for (shard in l.shards) {
                    val sheetId = eventsSheetId(shard)
                    val rows = api.batchGet(shard.spreadsheetId, listOf("$EVENTS_SHEET!A2:$EVENTS_LAST_COL"))[0]
                    val doomed = rows.indices.filter { rows[it].str(2) == op.typeId }
                    if (doomed.isEmpty()) continue
                    api.batchUpdate(shard.spreadsheetId, doomed.sortedDescending().map { deleteRow(sheetId, it) })
                    dirty += shard.spreadsheetId
                    doomed.mapNotNull { rows[it].str(5).ifBlank { null } }.forEach { api.trash(it) }
                }
                val ids = api.batchGet(l.mainId, listOf("$TYPES_SHEET!A2:A"))[0]
                val index = ids.indexOfFirst { it.str(0) == op.typeId }
                if (index >= 0) api.batchUpdate(l.mainId, listOf(deleteRow(l.typesSheetId, index)))
            }
            is Op.UpsertEvent -> {
                val e = op.event
                // Unwritten events can only be in the newest shard (a retried append); known ones
                // are looked up in their shard first and then along the rest of the chain.
                val candidates = if (e.shard.isEmpty()) listOf(l.active) else searchOrder(l, e.shard)
                var activeRows: Int? = null
                for (shard in candidates) {
                    val found = locate(shard, e.id)
                    if (shard == l.active) activeRows = found.rowCount
                    val index = found.index ?: continue
                    writeRow(shard.spreadsheetId, EVENTS_SHEET, EVENTS_LAST_COL, index, SheetSchema.eventToRow(e))
                    dirty += shard.spreadsheetId
                    val oldFile = found.fileId
                    if (!oldFile.isNullOrBlank() && oldFile != e.attachment?.fileId) api.trash(oldFile)
                    return
                }
                val rows = activeRows ?: locate(l.active, e.id).rowCount
                val target = if (rows >= maxRowsPerShard) createShard(l) else l.active
                writeRow(target.spreadsheetId, EVENTS_SHEET, EVENTS_LAST_COL, null, SheetSchema.eventToRow(e))
                dirty += target.spreadsheetId
            }
            is Op.DeleteEvent -> {
                for (shard in searchOrder(l, op.shard)) {
                    val found = locate(shard, op.eventId)
                    val index = found.index ?: continue
                    api.batchUpdate(shard.spreadsheetId, listOf(deleteRow(eventsSheetId(shard), index)))
                    dirty += shard.spreadsheetId
                    if (!found.fileId.isNullOrBlank()) api.trash(found.fileId)
                    return
                }
            }
        }
    }

    /** The hinted shard first, then the rest of the chain newest first. */
    private fun searchOrder(l: Layout, hint: String): List<Shard> {
        val newestFirst = l.shards.sortedByDescending { it.seq }
        val hinted = newestFirst.firstOrNull { it.spreadsheetId == hint } ?: return newestFirst
        return listOf(hinted) + (newestFirst - hinted)
    }

    /** [index] is the 0-based data row (null if absent); [rowCount] the number of data rows. */
    private data class Located(val index: Int?, val fileId: String?, val rowCount: Int)

    private suspend fun locate(shard: Shard, eventId: String): Located {
        eventsSheetId(shard)
        val col = SheetSchema.EVENTS_FILE_ID_COL
        val (ids, files) = api.batchGet(shard.spreadsheetId, listOf("$EVENTS_SHEET!A2:A", "$EVENTS_SHEET!${col}2:$col"))
        val index = ids.indexOfFirst { it.str(0) == eventId }.takeIf { it >= 0 }
        return Located(index, index?.let { files.getOrNull(it)?.str(0) }, ids.size)
    }

    /** Overwrites data row [index], or appends a row when [index] is null. */
    private suspend fun writeRow(spreadsheetId: String, sheet: String, lastCol: String, index: Int?, values: List<JsonElement>) {
        if (index != null) {
            val n = index + 2
            api.updateValues(spreadsheetId, "$sheet!A$n:$lastCol$n", listOf(values))
        } else {
            api.appendValues(spreadsheetId, "$sheet!A1:${lastCol}1", listOf(values))
        }
    }

    private fun deleteRow(sheetId: Int, dataIndex: Int) = buildJsonObject {
        putJsonObject("deleteDimension") {
            putJsonObject("range") {
                put("sheetId", sheetId)
                put("dimension", "ROWS")
                put("startIndex", dataIndex + 1)
                put("endIndex", dataIndex + 2)
            }
        }
    }

    private fun addSheet(title: String): JsonObject = buildJsonObject {
        putJsonObject("addSheet") {
            putJsonObject("properties") {
                put("title", title)
                putJsonObject("gridProperties") { put("frozenRowCount", 1) }
            }
        }
    }

    private fun renameSheet(sheetId: Int, title: String): JsonObject = buildJsonObject {
        putJsonObject("updateSheetProperties") {
            putJsonObject("properties") {
                put("sheetId", sheetId)
                put("title", title)
                putJsonObject("gridProperties") { put("frozenRowCount", 1) }
            }
            put("fields", "title,gridProperties.frozenRowCount")
        }
    }

    private fun List<JsonElement>.str(i: Int): String =
        (getOrNull(i) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    companion object {
        /** Rows per events spreadsheet before a new one is started (~80k cells, quick to load). */
        const val MAX_ROWS_PER_SHARD = 10_000

        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val SHEET_MIME = "application/vnd.google-apps.spreadsheet"

        private const val ROOT = "root"
        private const val FILES = "files"
        private const val DATA = "data"
        private const val SHARD = "shard"

        private fun tag(value: String) = mapOf(APP_KEY to value)
        private fun shardsRange() = "$SHARDS_SHEET!A2:$SHARDS_LAST_COL"
        private fun column(n: Int): String = ('A' + n - 1).toString()
        fun sheetUrl(id: String) = "https://docs.google.com/spreadsheets/d/$id/edit"
    }
}
