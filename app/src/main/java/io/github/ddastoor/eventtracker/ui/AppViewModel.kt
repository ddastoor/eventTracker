package io.github.ddastoor.eventtracker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.AuthorizationResult
import io.github.ddastoor.eventtracker.EventTrackerApp
import io.github.ddastoor.eventtracker.data.Attachment
import io.github.ddastoor.eventtracker.data.Event
import io.github.ddastoor.eventtracker.data.EventType
import io.github.ddastoor.eventtracker.data.Op
import io.github.ddastoor.eventtracker.data.newId
import io.github.ddastoor.eventtracker.data.nowEpochSeconds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as EventTrackerApp).container
    private val repo = container.repository

    val auth = container.auth
    val attachments = container.attachments
    val account = container.account
    val snapshot = repo.snapshot
    val status = repo.status
    val pendingOps = repo.pendingOps

    /** Ids of events/types with changes still waiting to reach Google. */
    val pendingIds: StateFlow<Set<String>> = repo.pendingOps.map { ops ->
        ops.mapTo(mutableSetOf()) {
            when (it) {
                is Op.UpsertEvent -> it.event.id
                is Op.DeleteEvent -> it.eventId
                is Op.UpsertType -> it.type.id
                is Op.DeleteType -> it.typeId
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val spreadsheetUrl: String? get() = container.spreadsheetUrl

    /** Logs an event of [type] right now. Returns it so the UI can offer "Undo". */
    fun quickAdd(type: EventType): Event {
        val event = Event(id = newId(), timestamp = nowEpochSeconds(), typeId = type.id)
        repo.submit(Op.UpsertEvent(event))
        return event
    }

    fun saveEvent(event: Event) = repo.submit(Op.UpsertEvent(event))

    fun deleteEvent(id: String) {
        val shard = snapshot.value.events.firstOrNull { it.id == id }?.shard.orEmpty()
        repo.submit(Op.DeleteEvent(id, shard))
    }

    fun saveType(type: EventType) = repo.submit(Op.UpsertType(type))

    fun deleteType(id: String) = repo.submit(Op.DeleteType(id))

    fun sync() = repo.requestSync()

    fun discardPending() = repo.discardPending()

    suspend fun importAttachment(uri: Uri): Attachment =
        attachments.import(getApplication<Application>().contentResolver, uri)

    fun newPhotoTarget(): Pair<Attachment, File> = attachments.newPhotoTarget()

    fun discardLocalAttachment(a: Attachment?) {
        if (a != null && a.isLocal) attachments.discard(a.localKey)
    }

    suspend fun completeSignIn(result: AuthorizationResult) = container.completeSignIn(result)

    fun signOut() {
        viewModelScope.launch { container.signOut() }
    }
}
