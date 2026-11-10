package io.github.ddastoor.eventtracker.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ddastoor.eventtracker.auth.GoogleAuth
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Returns a function that runs Google authorization, showing Google's account picker / consent
 * screen when needed. [onBusy] reports progress, [onError] a readable message.
 */
@Composable
fun rememberGoogleAuthorizer(
    vm: AppViewModel,
    onBusy: (Boolean) -> Unit = {},
    onError: (String) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            try {
                if (res.resultCode != Activity.RESULT_OK) {
                    onError("Sign-in was cancelled.")
                } else {
                    vm.completeSignIn(vm.auth.resultFromIntent(res.data))
                }
            } catch (e: Exception) {
                onError(GoogleAuth.describe(e))
            } finally {
                onBusy(false)
            }
        }
    }
    return {
        scope.launch {
            onBusy(true)
            try {
                val result = vm.auth.authorize()
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    return@launch
                }
                vm.completeSignIn(result)
            } catch (e: Exception) {
                onError(GoogleAuth.describe(e))
            }
            onBusy(false)
        }
    }
}

@Composable
fun SignInScreen(vm: AppViewModel) {
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    val signIn = rememberGoogleAuthorizer(vm, onBusy = { busy = it }) { error = it; showSetup = true }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Icon(Icons.Filled.Insights, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Event Tracker", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
            Text(
                "Log life events with one tap and see how often they happen. Your data is kept on this " +
                    "phone and synced to a Google Sheet in your own Google Drive.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp).widthIn(max = 480.dp),
            )
            Button(onClick = { error = null; signIn() }, enabled = !busy, modifier = Modifier.widthIn(min = 240.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Sign in with Google")
            }
            error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.padding(top = 16.dp).widthIn(max = 480.dp),
                ) { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
            }
            TextButton(onClick = { showSetup = !showSetup }, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (showSetup) "Hide setup info" else "First-time setup info")
            }
            if (showSetup) SetupInfo(Modifier.widthIn(max = 480.dp))
        }
    }
}

@Composable
fun SetupInfo(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val sha1 = remember { signingSha1(ctx) ?: "unknown" }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google Cloud setup (one time)", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. In Google Cloud Console create a project and enable the Google Drive API and Google Sheets API.\n" +
                    "2. Configure the OAuth consent screen (External) and add your Google account as a test user, " +
                    "or publish the app.\n" +
                    "3. Create an OAuth client ID of type Android with the values below.\n" +
                    "See README.md in the repository for step-by-step instructions.",
                style = MaterialTheme.typography.bodyMedium,
            )
            CopyRow(ctx, "Package name", ctx.packageName)
            CopyRow(ctx, "SHA-1 certificate fingerprint", sha1)
        }
    }
}

@Composable
private fun CopyRow(ctx: Context, label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Filled.ContentCopy, "Copy $label") }
        }
    }
}

/** SHA-1 of the certificate this APK is signed with, as Google Cloud expects it. */
@Suppress("DEPRECATION")
fun signingSha1(ctx: Context): String? = runCatching {
    val pm = ctx.packageManager
    val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    } ?: return null
    MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
}.getOrNull()
