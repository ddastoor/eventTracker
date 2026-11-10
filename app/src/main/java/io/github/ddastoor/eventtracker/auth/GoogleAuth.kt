package io.github.ddastoor.eventtracker.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import io.github.ddastoor.eventtracker.data.AuthRequiredException
import io.github.ddastoor.eventtracker.data.GoogleApi
import io.github.ddastoor.eventtracker.data.TokenProvider
import kotlinx.coroutines.tasks.await
import java.io.IOException

/**
 * Google OAuth via Play services' AuthorizationClient.
 *
 * Only two scopes are requested:
 *  - `drive.file`: the app can create and use its own files (the spreadsheet and attachments) and
 *    nothing else in your Drive. This is a non-sensitive scope, so no Google app review is needed.
 *  - `userinfo.email`: to show which account is signed in.
 */
class GoogleAuth(context: Context) : TokenProvider {

    private val client: AuthorizationClient = Identity.getAuthorizationClient(context.applicationContext)

    /** The signed-in account's email, once known. Pins silent authorizations to that account. */
    @Volatile
    var accountEmail: String? = null

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAtMillis: Long = 0

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES)
        .apply { accountEmail?.let { setAccount(Account(it, "com.google")) } }
        .build()

    /** Starts authorization. If [AuthorizationResult.hasResolution] the UI must show its intent. */
    suspend fun authorize(): AuthorizationResult = client.authorize(request()).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(data)

    /**
     * Google's consent screen lets people untick individual permissions. True unless the result
     * says Drive access was left out.
     */
    fun hasDriveAccess(result: AuthorizationResult): Boolean {
        val granted = result.grantedScopes
        return granted.isNullOrEmpty() || DRIVE_FILE_SCOPE in granted
    }

    fun accept(result: AuthorizationResult) {
        if (!hasDriveAccess(result)) return
        result.accessToken?.let {
            cachedToken = it
            cachedAtMillis = System.currentTimeMillis()
        }
    }

    fun emailFrom(result: AuthorizationResult): String? = result.toGoogleSignInAccount()?.email

    override suspend fun token(): String {
        cachedToken?.takeIf { System.currentTimeMillis() - cachedAtMillis < TOKEN_REUSE_MILLIS }?.let { return it }
        val result = try {
            authorize()
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) throw IOException(describe(e))
            throw AuthRequiredException(describe(e))
        }
        if (!result.hasResolution() && !hasDriveAccess(result)) {
            throw AuthRequiredException(GoogleApi.DRIVE_PERMISSION_MISSING)
        }
        if (result.hasResolution() || result.accessToken == null) {
            throw AuthRequiredException("Google needs you to approve access again.")
        }
        accept(result)
        return result.accessToken!!
    }

    override suspend fun invalidate(token: String) {
        cachedToken = null
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    suspend fun signOut() {
        val email = accountEmail
        cachedToken?.let { invalidate(it) }
        cachedToken = null
        if (email != null) {
            runCatching {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(email, "com.google"))
                        .setScopes(SCOPES)
                        .build(),
                ).await()
            }
        }
        accountEmail = null
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
        val SCOPES = listOf(Scope(DRIVE_FILE_SCOPE), Scope(EMAIL_SCOPE))
        private const val TOKEN_REUSE_MILLIS = 40 * 60 * 1000L

        fun describe(e: Throwable): String {
            if (e !is ApiException) return e.message ?: e.toString()
            return when (e.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR ->
                    "Google rejected this app (error 10). In Google Cloud, create an OAuth client of type " +
                        "Android with this app's package name and SHA-1 (see Setup below)."
                CommonStatusCodes.NETWORK_ERROR -> "No connection to Google. Check your internet connection."
                CommonStatusCodes.CANCELED, 12501 -> "Sign-in was cancelled."
                CommonStatusCodes.API_NOT_CONNECTED, CommonStatusCodes.SERVICE_DISABLED ->
                    "Google Play services is not available on this device."
                else -> "Google sign-in failed (code ${e.statusCode}): ${e.message}"
            }
        }
    }
}
