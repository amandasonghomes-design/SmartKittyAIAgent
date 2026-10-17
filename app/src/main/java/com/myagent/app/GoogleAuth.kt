package com.myagent.app

import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await

/**
 * Asks Google for permission to read and trash Gmail, and returns a short-lived access token.
 * The first time, Android shows the Google account picker and consent screen.
 * Must be created in Activity.onCreate (before the screen starts).
 */
class GoogleAuth(private val activity: ComponentActivity) {
    private var pending: CompletableDeferred<String>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val waiting = pending ?: return@registerForActivityResult
        pending = null
        try {
            val auth = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(result.data)
            val token = auth.accessToken
            if (token != null) waiting.complete(token)
            else waiting.completeExceptionally(IllegalStateException("Google sign-in was cancelled."))
        } catch (e: Exception) {
            waiting.completeExceptionally(e)
        }
    }

    suspend fun accessToken(): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_MODIFY)))
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        if (result.hasResolution()) {
            val waiting = CompletableDeferred<String>()
            pending = waiting
            val sender = result.pendingIntent!!.intentSender
            launcher.launch(IntentSenderRequest.Builder(sender).build())
            return waiting.await()
        }
        return result.accessToken ?: throw IllegalStateException("Google returned no access token.")
    }

    companion object {
        // Read, label and trash mail. Does NOT allow permanent deletion.
        const val GMAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
    }
}
