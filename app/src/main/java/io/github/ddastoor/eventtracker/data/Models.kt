package io.github.ddastoor.eventtracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** A kind of event, e.g. "Haircut" or "New car". Stored in the `EventTypes` sheet. */
@Serializable
data class EventType(
    val id: String,
    val name: String,
    /** ARGB color, used for the preset button and chart line. */
    val color: Long = DEFAULT_COLORS.first(),
    /** Optional emoji shown on the preset button. */
    val emoji: String = "",
    /** When true a one-tap button for this type is shown on the home screen. */
    val isPreset: Boolean = true,
    /** Epoch seconds. */
    val createdAt: Long = 0,
) {
    companion object {
        val DEFAULT_COLORS: List<Long> = listOf(
            0xFF1E5AA8, 0xFFD3455B, 0xFF2E9E6A, 0xFFE08A1E, 0xFF7B4FC9,
            0xFF0F9BB4, 0xFFC2489C, 0xFF6B7A12, 0xFF8C5A3C, 0xFF546E7A,
        )
    }
}

/**
 * A file attached to an event. [fileId] is a Google Drive file id, or `local:<uuid>` for a file
 * that is still waiting to be uploaded (see [isLocal]).
 */
@Serializable
data class Attachment(
    val fileId: String,
    val name: String,
    val mimeType: String,
) {
    val isLocal: Boolean get() = fileId.startsWith(LOCAL_PREFIX)
    val localKey: String get() = fileId.removePrefix(LOCAL_PREFIX)
    val driveLink: String? get() = if (isLocal) null else "https://drive.google.com/file/d/$fileId/view"
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isPdf: Boolean get() = mimeType == "application/pdf"

    companion object {
        const val LOCAL_PREFIX = "local:"
    }
}

/** One occurrence of an event type at a point in time. Stored in the `Events` sheet. */
@Serializable
data class Event(
    val id: String,
    /** Epoch seconds. */
    val timestamp: Long,
    val typeId: String,
    val note: String = "",
    val attachment: Attachment? = null,
    /**
     * Spreadsheet id of the shard (see [SheetsBackend]) the row was read from; empty for events
     * that have not been written yet. Only a lookup hint, never written to the sheet.
     */
    val shard: String = "",
)

/** Everything the app knows about: both tables. */
@Serializable
data class Snapshot(
    val types: List<EventType> = emptyList(),
    val events: List<Event> = emptyList(),
    /** Shard spreadsheet id -> Drive modifiedTime when it was last read (lets sync skip unchanged shards). */
    val shardVersions: Map<String, String> = emptyMap(),
) {
    fun type(id: String): EventType? = types.firstOrNull { it.id == id }

    fun apply(op: Op): Snapshot = when (op) {
        is Op.UpsertType -> copy(types = types.upsert(op.type) { it.id })
        is Op.DeleteType -> copy(
            types = types.filterNot { it.id == op.typeId },
            events = events.filterNot { it.typeId == op.typeId },
        )
        is Op.UpsertEvent -> copy(events = events.upsert(op.event) { it.id })
        is Op.DeleteEvent -> copy(events = events.filterNot { it.id == op.eventId })
    }

    fun applyAll(ops: List<Op>): Snapshot = ops.fold(this) { acc, op -> acc.apply(op) }
}

private inline fun <T> List<T>.upsert(item: T, key: (T) -> String): List<T> {
    val k = key(item)
    val i = indexOfFirst { key(it) == k }
    return if (i < 0) this + item else toMutableList().also { it[i] = item }
}

/**
 * A change made in the app that has not been written to Google Sheets yet. Changes are applied
 * to the local view immediately and queued, so the app works offline and feels instant.
 */
@Serializable
sealed interface Op {
    val opId: String

    @Serializable
    @SerialName("upsertType")
    data class UpsertType(val type: EventType, override val opId: String = newId()) : Op

    @Serializable
    @SerialName("deleteType")
    data class DeleteType(val typeId: String, override val opId: String = newId()) : Op

    @Serializable
    @SerialName("upsertEvent")
    data class UpsertEvent(val event: Event, override val opId: String = newId()) : Op

    @Serializable
    @SerialName("deleteEvent")
    data class DeleteEvent(
        val eventId: String,
        /** Shard hint, see [Event.shard]. */
        val shard: String = "",
        override val opId: String = newId(),
    ) : Op
}

fun newId(): String = UUID.randomUUID().toString()

fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000
