package io.github.ddastoor.eventtracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ddastoor.eventtracker.data.Event
import io.github.ddastoor.eventtracker.data.Snapshot
import io.github.ddastoor.eventtracker.data.SyncStatus
import kotlinx.coroutines.launch

private sealed interface ListRow {
    data class Header(val title: String) : ListRow
    data class Item(val event: Event) : ListRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    vm: AppViewModel,
    snapshot: Snapshot,
    pendingIds: Set<String>,
    status: SyncStatus,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onEdit: (Event) -> Unit,
    onDelete: (Event) -> Unit,
    onCreateType: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<Event?>(null) }

    val events = remember(snapshot.events, filter) {
        snapshot.events
            .filter { filter.isEmpty() || it.typeId in filter }
            .sortedWith(compareByDescending<Event> { it.timestamp }.thenBy { it.id })
    }
    val rows = remember(events) {
        buildList<ListRow> {
            var month: String? = null
            events.forEach { e ->
                val m = Fmt.month(e.timestamp)
                if (m != month) { add(ListRow.Header(m)); month = m }
                add(ListRow.Item(e))
            }
        }
    }
    val selectedIndex = events.indexOfFirst { it.id == selectedId }
    val selected = events.getOrNull(selectedIndex)

    // Keep a valid selection: default to the newest event.
    LaunchedEffect(events, selectedId) {
        if (selected == null && events.isNotEmpty()) onSelect(events.first().id)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun move(delta: Int) {
        if (events.isEmpty()) return
        val next = (selectedIndex + delta).coerceIn(0, events.lastIndex)
        onSelect(events[next].id)
    }
    // Scroll so the selected row is visible (arrow buttons, keyboard, or opened from elsewhere).
    LaunchedEffect(selectedId, rows) {
        val rowIndex = rows.indexOfFirst { it is ListRow.Item && it.event.id == selectedId }
        if (rowIndex < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fully = visible.filter { it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (fully.none { it.index == rowIndex }) {
            listState.animateScrollToItem((rowIndex - 1).coerceAtLeast(0))
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.K -> { move(-1); true }
                    Key.DirectionDown, Key.J -> { move(1); true }
                    Key.Enter -> { selected?.let(onEdit); selected != null }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (snapshot.types.isEmpty()) {
            EmptyState("No events yet", "Create an event type first, then log events.") {
                Button(onClick = onCreateType) { Text("Create an event type") }
            }
            return@Column
        }
        TypeFilterRow(snapshot, filter) { filter = it }
        if (events.isEmpty()) {
            PullToRefreshBox(isRefreshing = status.running, onRefresh = { vm.sync() }, modifier = Modifier.fillMaxSize()) {
                EmptyState(
                    "No events",
                    if (filter.isEmpty()) "Use the buttons on the Quick add tab or the + button to log one." else "No events of the selected types.",
                )
            }
            return@Column
        }

        val list: @Composable (Modifier) -> Unit = { m ->
            PullToRefreshBox(isRefreshing = status.running, onRefresh = { vm.sync() }, modifier = m) {
                EventList(rows, snapshot, pendingIds, selectedId, listState, onSelect = { e ->
                    if (e.id == selectedId) onEdit(e) else onSelect(e.id)
                }, onLongPress = onEdit)
            }
        }
        val detail: @Composable (Modifier) -> Unit = { m ->
            DetailPane(
                vm = vm,
                event = selected,
                snapshot = snapshot,
                position = selectedIndex + 1,
                total = events.size,
                onUp = { move(-1) },
                onDown = { move(1) },
                onEdit = { selected?.let(onEdit) },
                onDelete = { confirmDelete = selected },
                modifier = m,
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 640.dp) {
                Row(Modifier.fillMaxSize()) {
                    list(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    detail(Modifier.weight(1.2f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    list(Modifier.weight(0.45f).fillMaxWidth())
                    HorizontalDivider()
                    detail(Modifier.weight(0.55f).fillMaxWidth())
                }
            }
        }
    }

    confirmDelete?.let { e ->
        ConfirmDialog(
            title = "Delete event?",
            text = "${snapshot.type(e.typeId)?.name ?: "Event"} on ${Fmt.dateTime(e.timestamp)}" +
                if (e.attachment != null) "\nThe attached file will be moved to your Drive trash." else "",
            confirmLabel = "Delete",
            onConfirm = {
                val idx = events.indexOf(e)
                val neighbor = events.getOrNull(idx + 1) ?: events.getOrNull(idx - 1)
                onDelete(e)
                onSelect(neighbor?.id)
                scope.launch { runCatching { focus.requestFocus() } }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun TypeFilterRow(snapshot: Snapshot, filter: List<String>, onChange: (List<String>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = filter.isEmpty(), onClick = { onChange(emptyList()) }, label = { Text("All") })
        snapshot.types.forEach { t ->
            val on = t.id in filter
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) filter - t.id else filter + t.id) },
                label = { Text(t.name) },
                leadingIcon = { TypeAvatar(t, 18.dp) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventList(
    rows: List<ListRow>,
    snapshot: Snapshot,
    pendingIds: Set<String>,
    selectedId: String?,
    state: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (Event) -> Unit,
    onLongPress: (Event) -> Unit,
) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(rows, key = { r -> if (r is ListRow.Item) r.event.id else "h-" + (r as ListRow.Header).title }) { r ->
            when (r) {
                is ListRow.Header -> Text(
                    r.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
                is ListRow.Item -> {
                    val e = r.event
                    val type = snapshot.type(e.typeId)
                    val isSelected = e.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .combinedClickable(onClick = { onSelect(e) }, onLongClick = { onLongPress(e) })
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeAvatar(type, 32.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                type?.name ?: "Unknown type",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Fmt.dateTime(e.timestamp) + if (e.note.isNotBlank()) " · ${e.note}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (e.id in pendingIds) Icon(Icons.Filled.CloudUpload, "Not synced yet", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                        if (e.attachment != null) Icon(Icons.Filled.AttachFile, "Has file", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailPane(
    vm: AppViewModel,
    event: Event?,
    snapshot: Snapshot,
    position: Int,
    total: Int,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullScreen by remember { mutableStateOf(false) }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            // Navigation + actions
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onUp, enabled = position > 1) { Icon(Icons.Filled.KeyboardArrowUp, "Previous (newer) event") }
                FilledTonalIconButton(onClick = onDown, enabled = position in 1 until total) { Icon(Icons.Filled.KeyboardArrowDown, "Next (older) event") }
                Text(
                    if (position > 0) "$position / $total" else "",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, enabled = event != null) { Icon(Icons.Filled.Edit, "Edit event") }
                IconButton(onClick = onDelete, enabled = event != null) { Icon(Icons.Filled.Delete, "Delete event") }
            }
            if (event == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select an event") }
                return@Column
            }
            val type = snapshot.type(event.typeId)
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TypeAvatar(type, 40.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(type?.name ?: "Unknown type", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Fmt.dateTime(event.timestamp)} · ${Fmt.relative(event.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (event.note.isNotBlank()) {
                Text(
                    event.note,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            val att = event.attachment
            if (att == null) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("No file attached", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AttachFile, null, Modifier.size(16.dp))
                    Text(
                        att.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    IconButton(onClick = { fullScreen = true }) { Icon(Icons.Filled.OpenInFull, "Full screen") }
                    IconButton(onClick = {
                        scope.launch {
                            runCatching { vm.attachments.open(att) }.onSuccess { openExternally(ctx, it, att) }
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") }
                }
                AttachmentViewer(att, vm.attachments, Modifier.fillMaxSize().padding(horizontal = 8.dp).padding(bottom = 8.dp))
                if (fullScreen) {
                    Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                                    Text(att.name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    IconButton(onClick = { fullScreen = false }) {
                                        Icon(Icons.Filled.Close, "Close")
                                    }
                                }
                                AttachmentViewer(att, vm.attachments, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}
