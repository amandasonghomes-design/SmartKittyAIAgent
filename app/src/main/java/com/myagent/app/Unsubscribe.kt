package com.myagent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred

/**
 * Unsubscribing uses the standard "List-Unsubscribe" header that marketing emails carry
 * (the same thing behind Gmail's own Unsubscribe button). Three kinds:
 *  - ONE_CLICK: a small automatic web request the sender must honor (RFC 8058).
 *  - EMAIL: send a short "unsubscribe" email to the sender's list address.
 *  - LINK: only a web page; the user has to open it and click themselves.
 */
data class UnsubTarget(
    val key: String,          // sender email address, lowercase
    val name: String,         // display name
    val method: Method,
    val target: String,       // URL or mailto: link
    val pageUrl: String?,     // a web page to open by hand, if any
) {
    enum class Method { ONE_CLICK, EMAIL, LINK }

    val methodLabel
        get() = when (method) {
            Method.ONE_CLICK -> "One-click unsubscribe"
            Method.EMAIL -> "Sends an unsubscribe email"
            Method.LINK -> "You'll need to tap a link"
        }

    companion object {
        private val bracketed = Regex("<([^>]+)>")
        private val address = Regex("<([^<>@\\s]+@[^<>\\s]+)>")

        fun senderAddress(from: String): String =
            (address.find(from)?.groupValues?.get(1) ?: from).trim().lowercase()

        fun senderName(from: String): String =
            from.substringBefore("<").trim().trim('"').ifBlank { senderAddress(from) }

        /** Works out the best way to unsubscribe from this email, or null if it has no unsubscribe option. */
        fun from(e: EmailSummary): UnsubTarget? {
            val links = bracketed.findAll(e.listUnsubscribe).map { it.groupValues[1].trim() }.toList()
            val https = links.firstOrNull { it.startsWith("https://", ignoreCase = true) }
            val mailto = links.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
            val oneClick = e.listUnsubscribePost.contains("One-Click", ignoreCase = true)
            val key = senderAddress(e.from)
            val name = senderName(e.from)
            return when {
                https != null && oneClick -> UnsubTarget(key, name, Method.ONE_CLICK, https, https)
                mailto != null -> UnsubTarget(key, name, Method.EMAIL, mailto, https)
                https != null -> UnsubTarget(key, name, Method.LINK, https, https)
                else -> null
            }
        }
    }
}

/** An unsubscribe list waiting for the user's approval. */
class UnsubscribeApproval(val targets: List<UnsubTarget>, val reason: String) {
    val selected = mutableStateMapOf<String, Boolean>().apply {
        targets.forEach { put(it.key, it.method != UnsubTarget.Method.LINK) }
    }
    val results = mutableStateMapOf<String, String>()
    var status by mutableStateOf(TrashApproval.Status.PENDING)
    var resultText by mutableStateOf("")
    val decision = CompletableDeferred<Set<String>?>()

    val selectedCount get() = selected.count { it.value }
    fun approve() {
        if (status == TrashApproval.Status.PENDING) decision.complete(selected.filterValues { it }.keys.toSet())
    }
    fun cancel() { if (status == TrashApproval.Status.PENDING) decision.complete(null) }
}
