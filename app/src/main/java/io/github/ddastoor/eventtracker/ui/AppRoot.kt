package io.github.ddastoor.eventtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ddastoor.eventtracker.BuildConfig
import io.github.ddastoor.eventtracker.data.EventType
import io.github.ddastoor.eventtracker.data.SyncStatus
import kotlinx.coroutines.launch

enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    Home("Quick add", "Event Tracker", Icons.Filled.TouchApp),
    Events("Events", "Events", Icons.AutoMirrored.Filled.ViewList),
    Charts("Charts", "Charts", Icons.Filled.ShowChart),
    Types("Types", "Event types", Icons.Filled.Category),
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    if (account == null) SignInScreen(vm) else MainScreen(vm, account!!)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel, account: String) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val pendingOps by vm.pendingOps.collectAsStateWithLifecycle()
    val pendingIds by vm.pendingIds.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var eventDraft by remember { mutableStateOf<EventDraft?>(null) }
    var typeDraft by remember { mutableStateOf<EventType?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val reconnect = rememberGoogleAuthorizer(vm) { authError = it }

    fun newType() {
        typeDraft = EventType(id = "", name = "", color = EventType.DEFAULT_COLORS[snapshot.types.size % EventType.DEFAULT_COLORS.size])
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.title) },
                actions = {
                    if (tab == Tab.Events && snapshot.types.isNotEmpty()) {
                        IconButton(onClick = { eventDraft = EventDraft.new(null) }) { Icon(Icons.Filled.Add, "Add event") }
                    }
                    SyncButton(status, pendingOps.size) { vm.sync() }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            when (tab) {
                // No FAB on Events: it would cover the file viewer. "+" is in the top bar there.
                Tab.Home -> if (snapshot.types.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { eventDraft = EventDraft.new(null) },
                        icon = { Icon(Icons.Filled.Add, "New event") },
                        text = { Text("Event") },
                    )
                }
                Tab.Types -> ExtendedFloatingActionButton(
                    onClick = { newType() },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Type") },
                )
                Tab.Events, Tab.Charts -> Unit
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SyncBanner(status, authError, onReconnect = { authError = null; reconnect() }, onRetry = { vm.sync() })
            when (tab) {
                Tab.Home -> HomeScreen(
                    snapshot = snapshot,
                    status = status,
                    onRefresh = { vm.sync() },
                    onQuickAdd = { type ->
                        val event = vm.quickAdd(type)
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val r = snackbar.showSnackbar(
                                "Logged ${type.name} at ${Fmt.time(event.timestamp)}",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Short,
                            )
                            if (r == SnackbarResult.ActionPerformed) vm.deleteEvent(event.id)
                        }
                    },
                    onAddWithDetails = { type -> eventDraft = EventDraft.new(type.id) },
                    onOpenEvent = { selectedEventId = it; tab = Tab.Events },
                    onCreateType = { newType() },
                    onManageTypes = { tab = Tab.Types },
                )
                Tab.Events -> EventsScreen(
                    vm = vm,
                    snapshot = snapshot,
                    pendingIds = pendingIds,
                    status = status,
                    selectedId = selectedEventId,
                    onSelect = { selectedEventId = it },
                    onEdit = { eventDraft = EventDraft.from(it) },
                    onDelete = { vm.deleteEvent(it.id) },
                    onCreateType = { newType() },
                )
                Tab.Charts -> ChartsScreen(
                    snapshot = snapshot,
                    onOpenEvent = { selectedEventId = it; tab = Tab.Events },
                )
                Tab.Types -> TypesScreen(
                    snapshot = snapshot,
                    pendingIds = pendingIds,
                    onEdit = { typeDraft = it },
                    onTogglePreset = { vm.saveType(it.copy(isPreset = !it.isPreset)) },
                    onCreate = { newType() },
                )
            }
        }
    }

    eventDraft?.let { draft ->
        EventEditor(
            vm = vm,
            initial = draft,
            types = snapshot.types,
            onDismiss = { eventDraft = null },
            onSave = { event ->
                vm.saveEvent(event)
                selectedEventId = event.id
                eventDraft = null
            },
            onDelete = { id ->
                vm.deleteEvent(id)
                eventDraft = null
            },
        )
    }

    typeDraft?.let { draft ->
        TypeEditor(
            initial = draft,
            existing = snapshot.types,
            eventCount = snapshot.events.count { it.typeId == draft.id },
            onDismiss = { typeDraft = null },
            onSave = { vm.saveType(it); typeDraft = null },
            onDelete = { vm.deleteType(it); typeDraft = null },
        )
    }

    if (showSettings) {
        SettingsDialog(
            vm = vm,
            account = account,
            status = status,
            pendingCount = pendingOps.size,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun SyncButton(status: SyncStatus, pending: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when {
            status.running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            status.error != null -> BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) {
                Icon(Icons.Filled.CloudOff, "Sync problem — tap to retry", tint = MaterialTheme.colorScheme.error)
            }
            pending > 0 -> BadgedBox(badge = { Badge { Text("$pending") } }) {
                Icon(Icons.Filled.CloudUpload, "$pending changes waiting to sync")
            }
            else -> Icon(Icons.Filled.CloudDone, "Synced — tap to refresh")
        }
    }
}

@Composable
private fun SyncBanner(status: SyncStatus, authError: String?, onReconnect: () -> Unit, onRetry: () -> Unit) {
    val message = authError ?: status.error ?: return
    if (status.running && authError == null) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    message == io.github.ddastoor.eventtracker.data.GoogleApi.DRIVE_PERMISSION_MISSING -> message
                    status.needsAuth -> "Google access needs approval. $message"
                    else -> "Not synced: $message"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
            if (status.needsAuth || authError != null) {
                TextButton(onClick = onReconnect) { Text("Reconnect") }
            } else {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    vm: AppViewModel,
    account: String,
    status: SyncStatus,
    pendingCount: Int,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Signed in as", style = MaterialTheme.typography.labelMedium)
                Text(account, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                Text("Sync", style = MaterialTheme.typography.labelMedium)
                Text(
                    buildString {
                        append(status.lastSuccess?.let { "Last synced ${Fmt.dateTime(it / 1000)}" } ?: "Not synced yet")
                        append("\n")
                        append(if (pendingCount == 0) "All changes are saved to Google." else "$pendingCount change(s) waiting to upload.")
                        status.error?.let { append("\nLast error: $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.sync() }) { Text("Sync now") }
                    vm.spreadsheetUrl?.let { url ->
                        OutlinedButton(onClick = { ctx.openUrl(url) }) { Text("Open Sheet") }
                    }
                }
                if (pendingCount > 0) {
                    TextButton(onClick = { confirmDiscard = true }) { Text("Discard unsynced changes…") }
                }
                HorizontalDivider()
                Text("App", style = MaterialTheme.typography.labelMedium)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                SetupInfo()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") } },
    )
    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            text = (if (pendingCount > 0) "$pendingCount change(s) have not reached Google yet and will be lost. " else "") +
                "Your data stays in your Google Drive; this phone's copy is removed.",
            confirmLabel = "Sign out",
            onConfirm = { vm.signOut(); onDismiss() },
            onDismiss = { confirmSignOut = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard unsynced changes?",
            text = "The $pendingCount change(s) not yet saved to Google will be undone on this phone.",
            confirmLabel = "Discard",
            onConfirm = { vm.discardPending() },
            onDismiss = { confirmDiscard = false },
        )
    }
}
