package com.myagent.app

import android.net.Uri
import android.text.Html
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

data class EmailSummary(
    val id: String,
    val from: String,
    val subject: String,
    val date: String,
    val snippet: String,
    val labels: List<String>,
    val listUnsubscribe: String = "",
    val listUnsubscribePost: String = "",
) {
    val starred get() = "STARRED" in labels
}

class GmailException(val code: Int, message: String) : IOException(message) {
    val rateLimited get() = code == 429 || (code == 403 && message?.contains("rate", ignoreCase = true) == true)
}

/** Minimal Gmail REST client: search, read headers, move to Trash. Never deletes permanently. */
class GmailClient(private val http: OkHttpClient) {
    private val base = "https://gmail.googleapis.com/gmail/v1/users/me/messages"

    // Gmail allows only a few requests at the same time per person, so we queue them.
    private val limiter = Semaphore(MAX_PARALLEL)

    suspend fun search(token: String, query: String, max: Int): List<EmailSummary> =
        withContext(Dispatchers.IO) {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("maxResults", max.coerceIn(1, 100).toString())
                .build()
            val list = call { getJson(token, url) }
            val arr = list.optJSONArray("messages") ?: return@withContext emptyList()
            val ids = List(arr.length()) { arr.getJSONObject(it).getString("id") }
            coroutineScope { ids.map { id -> async { call { summary(token, id) } } }.awaitAll() }
        }

    /** Moves messages to Trash (recoverable in Gmail for 30 days). Returns how many succeeded. */
    suspend fun trash(token: String, ids: Collection<String>): Int = withContext(Dispatchers.IO) {
        coroutineScope {
            ids.map { id ->
                async {
                    runCatching {
                        call {
                            val req = Request.Builder()
                                .url("$base/$id/trash")
                                .header("Authorization", "Bearer $token")
                                .post(ByteArray(0).toRequestBody(null))
                                .build()
                            http.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) throw error(resp.code, resp.body?.string().orEmpty())
                            }
                        }
                    }.isSuccess
                }
            }.awaitAll().count { it }
        }
    }

    /**
     * One-click unsubscribe (RFC 8058): a plain POST to the sender's own unsubscribe URL.
     * Deliberately sends NO Google login token, since this goes to the sender, not Google.
     */
    suspend fun oneClickUnsubscribe(url: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .post("List-Unsubscribe=One-Click".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        runCatching { http.newCall(req).execute().use { it.code in 200..399 } }.getOrDefault(false)
    }

    /** Sends the short unsubscribe email that a mailto: unsubscribe link asks for. */
    suspend fun sendUnsubscribeEmail(token: String, mailto: String): Boolean = withContext(Dispatchers.IO) {
        val to = Uri.decode(mailto.removePrefix("mailto:").removePrefix("MAILTO:").substringBefore("?"))
        if (to.isBlank()) return@withContext false
        val query = mailto.substringAfter("?", "")
        val params = query.split("&").filter { "=" in it }.associate {
            it.substringBefore("=").lowercase() to Uri.decode(it.substringAfter("="))
        }
        val subject = params["subject"].orEmpty().ifBlank { "unsubscribe" }
        val body = params["body"].orEmpty().ifBlank { "unsubscribe" }
        val clean = { v: String -> v.replace(Regex("[\\r\\n]+"), " ") } // no header tricks
        val mime = "To: ${clean(to)}\r\nSubject: ${clean(subject)}\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$body"
        val raw = Base64.encodeToString(mime.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        runCatching {
            call {
                val req = Request.Builder()
                    .url("$base/send")
                    .header("Authorization", "Bearer $token")
                    .post(JSONObject().put("raw", raw).toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw error(resp.code, resp.body?.string().orEmpty())
                }
            }
        }.isSuccess
    }

    /** Runs one request with the parallel limit, retrying with a pause if Gmail says "slow down". */
    private suspend fun <T> call(block: () -> T): T = limiter.withPermit {
        var wait = 1000L
        repeat(RETRIES) {
            try {
                return@withPermit block()
            } catch (e: GmailException) {
                if (!e.rateLimited) throw e
                delay(wait)
                wait *= 2
            }
        }
        block()
    }

    private fun summary(token: String, id: String): EmailSummary {
        val url = "$base/$id".toHttpUrl().newBuilder()
            .addQueryParameter("format", "metadata")
            .addQueryParameter("metadataHeaders", "From")
            .addQueryParameter("metadataHeaders", "Subject")
            .addQueryParameter("metadataHeaders", "Date")
            .addQueryParameter("metadataHeaders", "List-Unsubscribe")
            .addQueryParameter("metadataHeaders", "List-Unsubscribe-Post")
            .build()
        val m = getJson(token, url)
        val headers = m.optJSONObject("payload")?.optJSONArray("headers")
        val h = mutableMapOf<String, String>()
        if (headers != null) for (i in 0 until headers.length()) {
            val o = headers.getJSONObject(i)
            h[o.getString("name").lowercase()] = o.optString("value")
        }
        val labelsArr = m.optJSONArray("labelIds")
        return EmailSummary(
            id = id,
            from = h["from"].orEmpty(),
            subject = h["subject"].orEmpty().ifBlank { "(no subject)" },
            date = h["date"].orEmpty(),
            snippet = Html.fromHtml(m.optString("snippet"), Html.FROM_HTML_MODE_LEGACY).toString(),
            labels = if (labelsArr == null) emptyList() else List(labelsArr.length()) { labelsArr.getString(it) },
            listUnsubscribe = h["list-unsubscribe"].orEmpty(),
            listUnsubscribePost = h["list-unsubscribe-post"].orEmpty(),
        )
    }

    private fun getJson(token: String, url: HttpUrl): JSONObject {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw error(resp.code, text)
            return JSONObject(text)
        }
    }

    /** Turns Gmail's long JSON error into one short line. */
    private fun error(code: Int, body: String): GmailException {
        val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }
            .getOrDefault(body.take(200))
        return GmailException(code, "Gmail error $code: $msg")
    }

    companion object {
        private const val MAX_PARALLEL = 4
        private const val RETRIES = 4
    }
}
