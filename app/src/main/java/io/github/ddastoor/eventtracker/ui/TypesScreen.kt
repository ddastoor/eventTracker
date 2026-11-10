package io.github.ddastoor.eventtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ddastoor.eventtracker.data.EventType
import io.github.ddastoor.eventtracker.data.Snapshot
import io.github.ddastoor.eventtracker.data.newId
import io.github.ddastoor.eventtracker.data.nowEpochSeconds

@Composable
fun TypesScreen(
    snapshot: Snapshot,
    pendingIds: Set<String>,
    onEdit: (EventType) -> Unit,
    onTogglePreset: (EventType) -> Unit,
    onCreate: () -> Unit,
) {
    if (snapshot.types.isEmpty()) {
        EmptyState("No event types", "Event types are the things you track, e.g. Haircut, Oil change, New fridge.") {
            Button(onClick = onCreate) { Text("Create an event type") }
        }
        return
    }
    val counts = remember(snapshot.events) { snapshot.events.groupingBy { it.typeId }.eachCount() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(snapshot.types.sortedBy { it.name.lowercase() }, key = { it.id }) { t ->
            ListItem(
                modifier = Modifier.clickable { onEdit(t) },
                leadingContent = { TypeAvatar(t, 40.dp) },
                headlineContent = { Text(t.name) },
                supportingContent = {
                    val n = counts[t.id] ?: 0
                    Text(if (n == 1) "1 event" else "$n events")
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (t.id in pendingIds) Icon(Icons.Filled.CloudUpload, "Not synced yet", Modifier.size(16.dp).padding(end = 4.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(checked = t.isPreset, onCheckedChange = { onTogglePreset(t) })
                            Text("Preset", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypeEditor(
    initial: EventType,
    existing: List<EventType>,
    eventCount: Int,
    onDismiss: () -> Unit,
    onSave: (EventType) -> Unit,
    onDelete: (String) -> Unit,
) {
    val isNew = initial.id.isEmpty()
    var name by remember { mutableStateOf(initial.name) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    var color by remember { mutableStateOf(initial.color) }
    var preset by remember { mutableStateOf(initial.isPreset) }
    var confirmDelete by remember { mutableStateOf(false) }

    val trimmed = name.trim()
    val duplicate = existing.any { it.id != initial.id && it.name.equals(trimmed, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New event type" else "Edit event type") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Haircut") },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = { if (duplicate) Text("A type with this name already exists") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(8) },
                    label = { Text("Emoji (optional)") },
                    placeholder = { Text("✂️") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EventType.DEFAULT_COLORS.forEach { c ->
                        val selected = c == color
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(Color(c), CircleShape)
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = c }
                                .semantics { contentDescription = "Color ${io.github.ddastoor.eventtracker.data.SheetSchema.colorToHex(c)}" },
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Filled.Check, null, tint = contentColorOn(Color(c))) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Preset button", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Show a one-tap button on the Quick add screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = preset, onCheckedChange = { preset = it })
                }
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Delete this type…", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = trimmed.isNotEmpty() && !duplicate,
                onClick = {
                    onSave(
                        initial.copy(
                            id = initial.id.ifEmpty { newId() },
                            name = trimmed,
                            emoji = emoji.trim(),
                            color = color,
                            isPreset = preset,
                            createdAt = if (isNew) nowEpochSeconds() else initial.createdAt,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete “${initial.name}”?",
            text = if (eventCount == 0) "This type has no events." else
                "This also deletes its $eventCount event(s) from your Google Sheet, and moves their attached files to your Drive trash.",
            confirmLabel = "Delete",
            onConfirm = { onDelete(initial.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}
