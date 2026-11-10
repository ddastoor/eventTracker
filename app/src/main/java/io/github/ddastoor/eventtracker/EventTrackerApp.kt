package io.github.ddastoor.eventtracker

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.AuthorizationResult
import io.github.ddastoor.eventtracker.auth.GoogleAuth
import io.github.ddastoor.eventtracker.data.AttachmentStore
import io.github.ddastoor.eventtracker.data.AuthRequiredException
import io.github.ddastoor.eventtracker.data.GoogleApi
import io.github.ddastoor.eventtracker.data.LocalStore
import io.github.ddastoor.eventtracker.data.Repository
import io.github.ddastoor.eventtracker.data.SheetsBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

open class EventTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
    }

    /** Overridden by UI tests to run without contacting Google. */
    protected open fun createContainer() = AppContainer(this)
}

/** App-wide singletons. Lives as long as the process, so syncing survives screen rotations. */
class AppContainer(private val app: Application, private val connectToGoogle: Boolean = true) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("eventtracker", Context.MODE_PRIVATE)

    val auth = GoogleAuth(app)
    val api = GoogleApi(auth)
    val attachments = AttachmentStore(
        pendingDir = File(app.filesDir, "pending"),
        cacheDir = File(app.cacheDir, "attachments"),
        api = api,
    )
    val repository = Repository(LocalStore(File(app.filesDir, "data")), attachments, scope)

    private val _account = MutableStateFlow(prefs.getString(KEY_ACCOUNT, null))
    /** Email of the signed-in Google account, or null when signed out. */
    val account: StateFlow<String?> = _account.asStateFlow()

    private var backend: SheetsBackend? = null
    val spreadsheetUrl: String? get() = backend?.spreadsheetUrl

    init {
        _account.value?.let { email ->
            auth.accountEmail = email.takeIf { it.contains('@') }
            connect()
        }
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = repository.requestSync()
            },
        )
    }

    private fun connect() {
        if (!connectToGoogle) return
        backend = SheetsBackend(api).also { repository.backend = it }
        repository.requestSync()
    }

    /** Called after the user approved access in Google's consent screen (or silently). */
    suspend fun completeSignIn(result: AuthorizationResult) {
        if (!auth.hasDriveAccess(result)) throw AuthRequiredException(GoogleApi.DRIVE_PERMISSION_MISSING)
        auth.accept(result)
        if (_account.value != null && backend != null) {
            // Re-approval for the account that is already signed in: keep local data, just sync.
            repository.requestSync()
            return
        }
        val email = auth.emailFrom(result) ?: runCatching { api.userEmail() }.getOrNull()
        auth.accountEmail = email
        val label = email ?: "Google account"
        prefs.edit { putString(KEY_ACCOUNT, label) }
        _account.value = label
        connect()
    }

    suspend fun signOut() {
        auth.signOut()
        repository.clearLocal()
        backend = null
        prefs.edit { remove(KEY_ACCOUNT) }
        _account.value = null
    }

    private companion object {
        const val KEY_ACCOUNT = "account"
    }
}
