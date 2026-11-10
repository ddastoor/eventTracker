package io.github.ddastoor.eventtracker.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Layout of the two tables in the Google Sheet and conversion between rows and models.
 *
 * EventTypes: id | name | color | emoji | preset | createdAt
 * Events:     id | timestamp | typeId | note | fileLink | fileId | fileName | fileMimeType
 * Shards:     seq | spreadsheetId | link | createdAt     (index of the spreadsheets holding Events)
 */
object SheetSchema {
    const val TYPES_SHEET = "EventTypes"
    const val EVENTS_SHEET = "Events"
    const val SHARDS_SHEET = "Shards"

    val TYPE_HEADERS = listOf("id", "name", "color", "emoji", "preset", "createdAt")
    val EVENT_HEADERS = listOf(
        "id", "timestamp", "typeId", "note", "fileLink", "fileId", "fileName", "fileMimeType",
    )

    val SHARD_HEADERS = listOf("seq", "spreadsheetId", "link", "createdAt")

    const val TYPES_LAST_COL = "F"
    const val SHARDS_LAST_COL = "D"
    const val EVENTS_LAST_COL = "H"
    /** Column letters used for targeted reads. */
    const val EVENTS_TYPE_COL = "C"
    const val EVENTS_FILE_ID_COL = "F"

    fun typeToRow(t: EventType): List<JsonElement> = listOf(
        JsonPrimitive(t.id),
        JsonPrimitive(t.name),
        JsonPrimitive(colorToHex(t.color)),
        JsonPrimitive(t.emoji),
        JsonPrimitive(t.isPreset),
        JsonPrimitive(t.createdAt),
    )

    fun eventToRow(e: Event): List<JsonElement> {
        val a = e.attachment
        return listOf(
            JsonPrimitive(e.id),
            JsonPrimitive(e.timestamp),
            JsonPrimitive(e.typeId),
            JsonPrimitive(e.note),
            JsonPrimitive(a?.driveLink ?: ""),
            JsonPrimitive(a?.fileId ?: ""),
            JsonPrimitive(a?.name ?: ""),
            JsonPrimitive(a?.mimeType ?: ""),
        )
    }

    /** Returns null for rows that can't be used (blank id or name). */
    fun rowToType(row: List<JsonElement>, index: Int): EventType? {
        val id = row.str(0).trim()
        val name = row.str(1).trim()
        if (id.isEmpty() || name.isEmpty()) return null
        return EventType(
            id = id,
            name = name,
            color = hexToColor(row.str(2)) ?: EventType.DEFAULT_COLORS[index % EventType.DEFAULT_COLORS.size],
            emoji = row.str(3).trim(),
            isPreset = row.bool(4) ?: true,
            createdAt = row.long(5) ?: 0,
        )
    }

    /**
     * Returns null for rows that can't be used. [typeIdByName] lets rows that were typed into the
     * sheet by hand reference a type by its name instead of its id.
     */
    fun rowToEvent(
        row: List<JsonElement>,
        typeIds: Set<String>,
        typeIdByName: Map<String, String>,
        shard: String = "",
    ): Event? {
        val id = row.str(0).trim()
        val ts = row.long(1) ?: return null
        val rawType = row.str(2).trim()
        if (id.isEmpty() || rawType.isEmpty()) return null
        val typeId = if (rawType in typeIds) rawType else typeIdByName[rawType.lowercase()] ?: rawType
        val fileId = row.str(5).trim().ifEmpty { fileIdFromLink(row.str(4)) ?: "" }
        val attachment = if (fileId.isEmpty()) null else Attachment(
            fileId = fileId,
            name = row.str(6).ifBlank { "attachment" },
            mimeType = row.str(7).ifBlank { "application/octet-stream" },
        )
        return Event(id = id, timestamp = ts, typeId = typeId, note = row.str(3), attachment = attachment, shard = shard)
    }

    fun parseTypes(typeRows: List<List<JsonElement>>): List<EventType> =
        typeRows.mapIndexedNotNull { i, r -> rowToType(r, i) }.distinctBy { it.id }

    fun parseEvents(eventRows: List<List<JsonElement>>, types: List<EventType>, shard: String = ""): List<Event> {
        val ids = types.map { it.id }.toSet()
        val byName = types.associate { it.name.lowercase() to it.id }
        return eventRows.mapNotNull { rowToEvent(it, ids, byName, shard) }
    }

    fun shardToRow(seq: Int, spreadsheetId: String, createdAt: Long): List<JsonElement> = listOf(
        JsonPrimitive(seq),
        JsonPrimitive(spreadsheetId),
        JsonPrimitive("https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"),
        JsonPrimitive(createdAt),
    )

    /** Parses the shard index into (seq, spreadsheetId) pairs ordered by seq. */
    fun parseShards(rows: List<List<JsonElement>>): List<Pair<Int, String>> =
        rows.mapNotNull { r ->
            val seq = r.long(0)?.toInt() ?: return@mapNotNull null
            val id = r.str(1).trim().ifEmpty { return@mapNotNull null }
            seq to id
        }.distinctBy { it.second }.sortedBy { it.first }

    fun colorToHex(color: Long): String = "#%06X".format(color and 0xFFFFFF)

    fun hexToColor(s: String): Long? {
        val h = s.trim().removePrefix("#")
        if (h.length != 6) return null
        return h.toLongOrNull(16)?.let { it or 0xFF000000 }
    }

    private val linkPattern = Regex("""/d/([A-Za-z0-9_-]{10,})|[?&]id=([A-Za-z0-9_-]{10,})""")

    fun fileIdFromLink(link: String): String? {
        val m = linkPattern.find(link) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { null }
    }

    fun rows(values: JsonElement?): List<List<JsonElement>> =
        (values as? JsonArray)?.map { (it as? JsonArray)?.toList() ?: emptyList() } ?: emptyList()

    private fun List<JsonElement>.cell(i: Int): JsonPrimitive? =
        getOrNull(i)?.takeUnless { it is JsonNull } as? JsonPrimitive

    private fun List<JsonElement>.str(i: Int): String = cell(i)?.contentOrNull ?: ""

    private fun List<JsonElement>.long(i: Int): Long? =
        cell(i)?.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()

    private fun List<JsonElement>.bool(i: Int): Boolean? {
        val c = cell(i) ?: return null
        c.booleanOrNull?.let { return it }
        return when (c.contentOrNull?.trim()?.lowercase()) {
            "true", "yes", "y", "1" -> true
            "false", "no", "n", "0" -> false
            else -> null
        }
    }
}
