package io.github.ddastoor.eventtracker.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.github.ddastoor.eventtracker.data.Attachment
import io.github.ddastoor.eventtracker.data.Event
import io.github.ddastoor.eventtracker.data.EventType
import io.github.ddastoor.eventtracker.data.newId
import io.github.ddastoor.eventtracker.data.nowEpochSeconds
import kotlinx.coroutines.launch
import java.io.File

/** What the event editor starts from: a new event (id == null) or an existing one. */
data class EventDraft(
    val id: String?,
    val typeId: String?,
    val timestamp: Long,
    val note: String,
    val attachment: Attachment?,
    val shard: String = "",
) {
    companion object {
        fun new(typeId: String?) = EventDraft(null, typeId, nowEpochSeconds(), "", null)
        fun from(e: Event) = EventDraft(e.id, e.typeId, e.timestamp, e.note, e.attachment, e.shard)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditor(
    vm: AppViewModel,
    initial: EventDraft,
    types: List<EventType>,
    onDismiss: () -> Unit,
    onSave: (Event) -> Unit,
    onDelete: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var typeId by remember { mutableStateOf(initial.typeId ?: types.singleOrNull()?.id) }
    var dateTime by remember { mutableStateOf(Fmt.local(initial.timestamp)) }
    var note by remember { mutableStateOf(initial.note) }
    var attachment by remember { mutableStateOf(initial.attachment) }
    var importing by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var photoTarget by remember { mutableStateOf<Pair<Attachment, File>?>(null) }

    /** Replace the current attachment, cleaning up a not-yet-saved file we created here. */
    fun setAttachment(a: Attachment?) {
        if (attachment != initial.attachment) vm.discardLocalAttachment(attachment)
        attachment = a
    }

    fun cancel() {
        if (attachment != initial.attachment) vm.discardLocalAttachment(attachment)
        onDismiss()
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                runCatching { vm.importAttachment(uri) }
                    .onSuccess { setAttachment(it) }
                    .onFailure { Toast.makeText(ctx, it.message ?: "Could not attach file", Toast.LENGTH_LONG).show() }
                importing = false
            }
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = photoTarget ?: return@rememberLauncherForActivityResult
        photoTarget = null
        if (ok && target.second.length() > 0) setAttachment(target.first) else vm.discardLocalAttachment(target.first)
    }

    Dialog(onDismissRequest = ::cancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = ::cancel) { Icon(Icons.Filled.Close, "Cancel") } },
                    title = { Text(if (initial.id == null) "New event" else "Edit event") },
                    actions = {
                        TextButton(
                            enabled = typeId != null && !importing,
                            onClick = {
                                onSave(
                                    Event(
                                        id = initial.id ?: newId(),
                                        timestamp = Fmt.toEpoch(dateTime),
                                        typeId = typeId!!,
                                        note = note.trim(),
                                        attachment = attachment,
                                        shard = initial.shard,
                                    ),
                                )
                            },
                        ) { Text("Save") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Type", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        types.forEach { t ->
                            FilterChip(
                                selected = t.id == typeId,
                                onClick = { typeId = t.id },
                                label = { Text(t.name) },
                                leadingIcon = { TypeAvatar(t, 18.dp) },
                            )
                        }
                    }

                    Text("When", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pickDate = true }) {
                            Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp))
                            Text(Fmt.date(dateTime.toLocalDate()), Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = { pickTime = true }) {
                            Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp))
                            Text(Fmt.time(Fmt.toEpoch(dateTime)), Modifier.padding(start = 6.dp))
                        }
                        TextButton(onClick = { dateTime = Fmt.local(nowEpochSeconds()) }) { Text("Now") }
                    }

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note (optional)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text("File (optional)", style = MaterialTheme.typography.labelLarge)
                    val a = attachment
                    when {
                        importing -> CircularProgressIndicator(Modifier.size(24.dp))
                        a != null -> Card(Modifier.fillMaxWidth()) {
                            Column {
                                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.AttachFile, null, Modifier.size(18.dp))
                                    Text(a.name, Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { setAttachment(null) }) { Text("Remove") }
                                }
                                if (a.isImage || a.isPdf) {
                                    AttachmentViewer(a, vm.attachments, Modifier.fillMaxWidth().height(220.dp).padding(8.dp))
                                }
                            }
                        }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Filled.AttachFile, null, Modifier.size(18.dp))
                                Text("Attach file", Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(onClick = {
                                val target = vm.newPhotoTarget()
                                photoTarget = target
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", target.second)
                                runCatching { takePhoto.launch(uri) }.onFailure {
                                    Toast.makeText(ctx, "No camera app available", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                                Text("Take photo", Modifier.padding(start = 6.dp))
                            }
                        }
                    }

                    if (initial.id != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Delete this event", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (pickDate) {
        DatePickDialog(dateTime.toLocalDate(), onPicked = { d -> dateTime = dateTime.with(d) }, onDismiss = { pickDate = false })
    }
    if (pickTime) {
        TimePickDialog(dateTime.hour, dateTime.minute, onPicked = { h, m -> dateTime = dateTime.withHour(h).withMinute(m).withSecond(0) }, onDismiss = { pickTime = false })
    }
    if (confirmDelete && initial.id != null) {
        ConfirmDialog(
            title = "Delete event?",
            text = "This removes the event from your Google Sheet." +
                if (initial.attachment != null) " The attached file is moved to your Drive trash." else "",
            confirmLabel = "Delete",
            onConfirm = { onDelete(initial.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}
