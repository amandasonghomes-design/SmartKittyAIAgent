package com.myagent.app

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Signs in to the Flexmls MCP server (your MLS) with the standard MCP sign-in (OAuth + PKCE).
 *
 * 1. Finds the server's sign-in pages (".well-known" discovery).
 * 2. Registers this app once with the server (gets a client id).
 * 3. Opens the Flexmls login page in the browser; you log in with your normal Flexmls account.
 * 4. Flexmls sends the browser back to a tiny private address on the phone (http://127.0.0.1:47613),
 *    where the app is briefly listening, with a one-time code. (Flexmls only allows web addresses.)
 * 5. The app trades the code for an access pass (token), stored encrypted on the phone.
 *
 * Your Flexmls password is typed only on Flexmls' own page; the app never sees it.
 */
class MlsAuth(private val http: OkHttpClient, private val prefs: Prefs) {

    private val json = "application/json".toMediaType()

    /** Listens on the phone for Flexmls' redirect while a sign-in is in progress. */
    @Volatile private var listener: ServerSocket? = null
    private val redirect get() = prefs.mlsRedirect.ifBlank { REDIRECT_CANDIDATES.first() }

    /** Returns the Flexmls login page address to open in the browser. */
    suspend fun startUrl(): String = withContext(Dispatchers.IO) {
        listener?.close()
        listener = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5 * 60_000 }
        val server = Uri.parse(prefs.mlsUrl)
        val origin = "${server.scheme}://${server.authority}"

        // 1. Discovery
        val resourceInfo = getJson("$origin/.well-known/oauth-protected-resource")
        val authServer = resourceInfo?.optJSONArray("authorization_servers")?.optString(0)?.trimEnd('/') ?: origin
        val resource = resourceInfo?.optString("resource")?.ifBlank { null } ?: origin
        val scopes = resourceInfo?.optJSONArray("scopes_supported")?.let { a -> List(a.length()) { a.getString(it) } }
            ?.joinToString(" ").orEmpty()
        val meta = getJson("$authServer/.well-known/oauth-authorization-server")
            ?: getJson("$authServer/.well-known/openid-configuration")
            ?: throw IOException("Couldn't find the MLS sign-in page. Check the server address.")
        val authorize = meta.getString("authorization_endpoint")
        val tokenUrl = meta.getString("token_endpoint")

        // 2. Register this app once (the client id is kept for later sign-ins)
        if (prefs.mlsClientId.isBlank() || prefs.mlsTokenEndpoint != tokenUrl || prefs.mlsRedirect.isBlank()) {
            val register = meta.optString("registration_endpoint").ifBlank {
                throw IOException("This MLS server doesn't allow new apps to register.")
            }
            var lastError: Exception? = null
            for (candidate in REDIRECT_CANDIDATES) {
                val body = JSONObject()
                    .put("client_name", "猫猫小助手")
                    .put("redirect_uris", JSONArray().put(candidate))
                    .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                    .put("response_types", JSONArray().put("code"))
                    .put("token_endpoint_auth_method", "none")
                if (scopes.isNotBlank()) body.put("scope", scopes)
                try {
                    val reply = post(Request.Builder().url(register).post(body.toString().toRequestBody(json)).build())
                    prefs.mlsClientId = reply.getString("client_id")
                    prefs.mlsRedirect = candidate
                    lastError = null
                    break
                } catch (e: IOException) {
                    lastError = e // try the next kind of address
                }
            }
            lastError?.let { throw it }
        }
        prefs.mlsTokenEndpoint = tokenUrl
        prefs.mlsResource = resource

        // 3. PKCE: a secret only this phone knows, proving the code comes back to us
        val verifier = randomString(48)
        val state = randomString(16)
        prefs.mlsPending = "$state|$verifier"
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        Uri.parse(authorize).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", prefs.mlsClientId)
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("resource", resource)
            .apply { if (scopes.isNotBlank()) appendQueryParameter("scope", scopes) }
            .build().toString()
    }

    /**
     * Waits (up to 5 minutes) for the browser to arrive at http://127.0.0.1 after the Flexmls login,
     * shows a "you're signed in" page with a button back to the app, and returns the address it came to.
     */
    suspend fun waitForRedirect(): Uri = withContext(Dispatchers.IO) {
        val server = listener ?: throw IOException("Please tap Connect MLS again.")
        try {
            while (true) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val firstLine = socket.getInputStream().bufferedReader().readLine().orEmpty() // GET /callback?... HTTP/1.1
                    val path = firstLine.split(" ").getOrNull(1).orEmpty()
                    val out = socket.getOutputStream()
                    if (!path.startsWith("/callback")) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                    } else {
                        val ok = !path.contains("error=")
                        val page = """<!doctype html><html><head><meta charset="utf-8">
                            <meta name="viewport" content="width=device-width,initial-scale=1">
                            <style>body{font-family:sans-serif;background:#E6F5FF;color:#1B2B45;text-align:center;padding:60px 24px}
                            a{display:inline-block;margin-top:24px;padding:16px 28px;border-radius:28px;background:#2F7FD1;
                            color:#fff;text-decoration:none;font-size:18px;font-weight:bold}</style></head><body>
                            <h2>${if (ok) "MLS connected 🐱" else "MLS sign-in didn't finish"}</h2>
                            <p>You can go back to 猫猫小助手 now.</p>
                            <a href="com.smartkitty.app://oauth/done">Back to Smart Kitty</a>
                            <script>setTimeout(function(){location.href="com.smartkitty.app://oauth/done"},400)</script>
                            </body></html>""".trimIndent().toByteArray()
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${page.size}\r\nConnection: close\r\n\r\n").toByteArray()
                        )
                        out.write(page)
                        out.flush()
                        return@withContext Uri.parse("http://127.0.0.1:$PORT$path")
                    }
                }
            }
            throw IOException("unreachable")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("MLS sign-in timed out. Tap Connect MLS to try again.")
        } finally {
            server.close()
            if (listener === server) listener = null
        }
    }

    /** Called with the address Flexmls sent the browser back to. */
    suspend fun finish(returned: Uri) = withContext(Dispatchers.IO) {
        returned.getQueryParameter("error")?.let {
            throw IOException("MLS sign-in was cancelled or refused ($it).")
        }
        val code = returned.getQueryParameter("code") ?: throw IOException("MLS sign-in didn't return a code.")
        val (state, verifier) = prefs.mlsPending.split("|", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else throw IOException("Please tap Connect MLS again.")
        }
        if (returned.getQueryParameter("state") != state) throw IOException("Sign-in check failed. Please try again.")
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirect)
            .add("client_id", prefs.mlsClientId)
            .add("code_verifier", verifier)
            .add("resource", prefs.mlsResource)
            .build()
        saveTokens(post(Request.Builder().url(prefs.mlsTokenEndpoint).post(form).build()))
        prefs.mlsPending = ""
    }

    /** A valid access pass, refreshed automatically when it's about to expire. Null = not connected. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val token = prefs.mlsAccessToken.ifBlank { return@withContext null }
        if (System.currentTimeMillis() < prefs.mlsExpiresAt - 60_000) return@withContext token
        val refresh = prefs.mlsRefreshToken.ifBlank { return@withContext token } // let the server decide
        runCatching {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", prefs.mlsClientId)
                .add("resource", prefs.mlsResource)
                .build()
            saveTokens(post(Request.Builder().url(prefs.mlsTokenEndpoint).post(form).build()))
            prefs.mlsAccessToken
        }.getOrElse { token }
    }

    fun disconnect() {
        prefs.mlsAccessToken = ""
        prefs.mlsRefreshToken = ""
        prefs.mlsExpiresAt = 0
        prefs.mlsPending = ""
        listener?.close()
        listener = null
    }

    private fun saveTokens(t: JSONObject) {
        prefs.mlsAccessToken = t.getString("access_token")
        t.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.mlsRefreshToken = it }
        val seconds = t.optLong("expires_in", 3600)
        prefs.mlsExpiresAt = System.currentTimeMillis() + seconds * 1000
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null
        }
    }.getOrNull()

    private fun post(request: Request): JSONObject =
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching {
                    JSONObject(text).let { it.optString("error_description").ifBlank { it.optString("error") } }
                }.getOrNull().orEmpty().ifBlank { text.take(200) }
                throw IOException("MLS sign-in error ${r.code}: $msg")
            }
            JSONObject(text)
        }

    private fun randomString(bytes: Int): String =
        b64url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val PORT = 47613
        /** Flexmls only accepts web addresses; these point back to the phone itself. */
        val REDIRECT_CANDIDATES = listOf("http://127.0.0.1:$PORT/callback", "http://localhost:$PORT/callback")
        const val DEFAULT_URL = "https://mcp.flexmls.com/mcp"
    }
}
