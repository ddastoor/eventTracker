package io.github.ddastoor.eventtracker.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Where the data lives (Google Sheets in the app; a fake in tests). */
interface RemoteBackend {
    /** Loads both tables; [previous] lets the backend reuse data that hasn't changed. */
    suspend fun fetch(previous: Snapshot): Snapshot
    suspend fun apply(op: Op)
    suspend fun upload(file: File, name: String, mimeType: String): DriveFile
}

data class SyncStatus(
    val running: Boolean = false,
    /** Epoch millis of the last successful sync. */
    val lastSuccess: Long? = null,
    val error: String? = null,
    val needsAuth: Boolean = false,
)

/**
 * Single source of truth for the UI. Every change is applied to the local view immediately,
 * persisted in a queue, and written to Google Sheets in order in the background. If the phone is
 * offline the queue simply waits and is retried on the next sync.
 */
class Repository(
    private val local: LocalStore,
    private val attachments: AttachmentStore,
    private val scope: CoroutineScope,
) {
    private val remote = MutableStateFlow(local.loadSnapshot())
    private val pending = MutableStateFlow(local.loadOps())
    private val _status = MutableStateFlow(SyncStatus())
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private val syncMutex = Mutex()

    /** Local attachments that were uploaded during this session: local id -> Drive attachment. */
    private val uploaded = mutableMapOf<String, Attachment>()

    @Volatile
    var backend: RemoteBackend? = null

    val pendingOps: StateFlow<List<Op>> = pending.asStateFlow()
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    val snapshot: StateFlow<Snapshot> = combine(remote, pending) { s, ops -> s.applyAll(ops) }
        .stateIn(scope, SharingStarted.Eagerly, remote.value.applyAll(pending.value))

    init {
        scope.launch {
            for (request in syncRequests) syncNow()
        }
    }

    fun submit(op: Op) {
        synchronized(lock) {
            val fixed = if (op is Op.UpsertEvent) op.copy(event = op.event.withUploadedAttachment()) else op
            pending.value = pending.value + fixed
            local.saveOps(pending.value)
        }
        requestSync()
    }

    fun requestSync() {
        syncRequests.trySend(Unit)
    }

    /** Drops all changes that haven't reached Google yet. */
    fun discardPending() {
        synchronized(lock) {
            pending.value.forEach { op ->
                (op as? Op.UpsertEvent)?.event?.attachment?.takeIf { it.isLocal }?.let { attachments.discard(it.localKey) }
            }
            pending.value = emptyList()
            local.saveOps(emptyList())
        }
        _status.update { it.copy(error = null) }
    }

    /** Forgets everything stored on the device (used on sign-out). */
    fun clearLocal() {
        synchronized(lock) {
            backend = null
            pending.value = emptyList()
            remote.value = Snapshot()
            uploaded.clear()
            local.clear()
            attachments.clearAll()
        }
        _status.value = SyncStatus()
    }

    /** Writes queued changes to Google, then reloads both tables. */
    suspend fun syncNow() = syncMutex.withLock {
        val b = backend ?: return@withLock
        _status.update { it.copy(running = true) }
        try {
            while (true) {
                var op = pending.value.firstOrNull() ?: break
                if (op is Op.UpsertEvent) op = uploadAttachmentIfNeeded(b, op)
                b.apply(op)
                synchronized(lock) {
                    // Keep showing the change until the fresh copy arrives from Google.
                    remote.value = remote.value.apply(op)
                    pending.value = pending.value.filterNot { it.opId == op.opId }
                    local.saveOps(pending.value)
                }
            }
            val fresh = b.fetch(remote.value)
            synchronized(lock) {
                if (backend === b) {
                    remote.value = fresh
                    local.saveSnapshot(fresh)
                }
            }
            _status.value = SyncStatus(lastSuccess = System.currentTimeMillis())
        } catch (e: CancellationException) {
            _status.update { it.copy(running = false) }
            throw e
        } catch (e: AuthRequiredException) {
            _status.update { it.copy(running = false, needsAuth = true, error = e.message) }
        } catch (e: Exception) {
            _status.update { it.copy(running = false, error = describe(e)) }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "No connection. Changes are saved on this phone and will sync automatically."
        else -> e.message ?: e.toString()
    }

    private suspend fun uploadAttachmentIfNeeded(b: RemoteBackend, op: Op.UpsertEvent): Op.UpsertEvent {
        val att = op.event.attachment ?: return op
        if (!att.isLocal) return op
        val resolved: Attachment? = synchronized(lock) { uploaded[att.fileId] } ?: run {
            val file = attachments.pendingFile(att.localKey)
            if (!file.exists()) {
                null
            } else {
                val driveFile = b.upload(file, att.name, att.mimeType)
                Attachment(driveFile.id, att.name, att.mimeType)
            }
        }
        synchronized(lock) {
            if (resolved != null) uploaded[att.fileId] = resolved
            pending.value = pending.value.map { o ->
                if (o is Op.UpsertEvent && o.event.attachment?.fileId == att.fileId) {
                    o.copy(event = o.event.copy(attachment = resolved))
                } else {
                    o
                }
            }
            local.saveOps(pending.value)
        }
        if (resolved != null) attachments.adopt(att.localKey, resolved.fileId)
        return pending.value.first { it.opId == op.opId } as Op.UpsertEvent
    }

    private fun Event.withUploadedAttachment(): Event {
        val a = attachment ?: return this
        return uploaded[a.fileId]?.let { copy(attachment = it) } ?: this
    }
}
