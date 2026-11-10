package io.github.ddastoor.eventtracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ddastoor.eventtracker.data.Event
import io.github.ddastoor.eventtracker.data.EventType
import io.github.ddastoor.eventtracker.data.Snapshot
import io.github.ddastoor.eventtracker.data.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    snapshot: Snapshot,
    status: SyncStatus,
    onRefresh: () -> Unit,
    onQuickAdd: (EventType) -> Unit,
    onAddWithDetails: (EventType) -> Unit,
    onOpenEvent: (String) -> Unit,
    onCreateType: () -> Unit,
    onManageTypes: () -> Unit,
) {
    if (snapshot.types.isEmpty()) {
        PullToRefreshBox(isRefreshing = status.running, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = if (status.running && status.lastSuccess == null) "Loading your data…" else "No event types yet",
                body = "Create a type such as “Haircut” or “New car”. Each type gets a one-tap button here.",
            ) { Button(onClick = onCreateType) { Text("Create an event type") } }
        }
        return
    }
    val presets = snapshot.types.filter { it.isPreset }
    val stats = remember(snapshot.events) {
        snapshot.events.groupBy { it.typeId }.mapValues { (_, list) -> list.size to list.maxOf { it.timestamp } }
    }
    val recent = remember(snapshot.events) { snapshot.events.sortedByDescending { it.timestamp }.take(10) }

    PullToRefreshBox(isRefreshing = status.running, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (presets.isEmpty()) "No preset buttons yet." else "Tap to log now · long-press to add date, note or file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (presets.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = onManageTypes) { Text("Turn on “Preset button” for a type") }
                }
            }
            items(presets, key = { it.id }) { type ->
                val (count, last) = stats[type.id] ?: (0 to null)
                PresetButton(type, count, last, onClick = { onQuickAdd(type) }, onLongClick = { onAddWithDetails(type) })
            }
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                    )
                }
                items(recent, key = { "r-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { e ->
                    RecentRow(e, snapshot.type(e.typeId)) { onOpenEvent(e.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetButton(type: EventType, count: Int, last: Long?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val bg = type.composeColor
    val fg = contentColorOn(bg)
    Card(
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
                onLongClick = onLongClick,
                onClickLabel = "Log ${type.name} now",
                onLongClickLabel = "Add ${type.name} with details",
            ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (type.emoji.isNotBlank()) Text(type.emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 6.dp))
                Text(
                    type.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (last == null) "Never logged" else "$count× · last ${Fmt.relative(last)}",
                style = MaterialTheme.typography.bodySmall,
                color = fg.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun RecentRow(e: Event, type: EventType?, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        ListItem(
            leadingContent = { TypeAvatar(type, 32.dp) },
            headlineContent = { Text(type?.name ?: "Unknown type", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    Fmt.dateTime(e.timestamp) + if (e.note.isNotBlank()) " · ${e.note}" else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { if (e.attachment != null) Icon(Icons.Filled.AttachFile, "Has file", Modifier.size(18.dp)) },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}
