package com.myagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings and long-term memory, stored privately on the phone. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("agent", Context.MODE_PRIVATE)

    /**
     * The OpenAI key is stored encrypted. The lock lives in the phone's secure hardware
     * (Android Keystore) and can't be copied out, so the saved file only holds scrambled text.
     */
    var apiKey: String
        get() {
            // One-time move of a key saved by an older version (plain text) into encrypted storage.
            val old = sp.getString("api_key", null)
            if (!old.isNullOrBlank()) {
                apiKey = old
                return old.trim()
            }
            val enc = sp.getString("api_key_enc", null) ?: return ""
            return runCatching { KeyLock.decrypt(enc) }.getOrDefault("")
        }
        set(v) {
            val key = v.trim()
            val edit = sp.edit().remove("api_key")
            if (key.isEmpty()) edit.remove("api_key_enc") else edit.putString("api_key_enc", KeyLock.encrypt(key))
            edit.apply()
        }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifBlank { DEFAULT_MODEL }).apply()

    var agentName: String
        get() = sp.getString("agent_name", null).let { if (it.isNullOrBlank() || it == "My Agent") DEFAULT_NAME else it }
        set(v) = sp.edit().putString("agent_name", v.trim().ifBlank { DEFAULT_NAME }).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak_replies", false)
        set(v) = sp.edit().putBoolean("speak_replies", v).apply()

    /** "" = phone default, or a language tag like "en-US" / "zh-CN". */
    var voiceLanguage: String
        get() = sp.getString("voice_language", "") ?: ""
        set(v) = sp.edit().putString("voice_language", v).apply()

    /** Show the model, token use and OpenAI account under each answer. */
    var showDetails: Boolean
        get() = sp.getBoolean("show_details", true)
        set(v) = sp.edit().putBoolean("show_details", v).apply()

    /** Things the kitty should always know about you. */
    var aboutMe: String
        get() = sp.getString("about_me", null) ?: DEFAULT_ABOUT_ME
        set(v) = sp.edit().putString("about_me", v.trim()).apply()

    /** One-line notes about recent conversations, newest last: "chatId|date|note". */
    var recentActivity: List<String>
        get() {
            val a = JSONArray(sp.getString("recent_activity", "[]"))
            return List(a.length()) { a.getString(it) }
        }
        set(v) = sp.edit().putString("recent_activity", JSONArray(v.takeLast(20)).toString()).apply()

    /** Chosen background key (see Backgrounds.kt). */
    var background: String
        get() = sp.getString("background", "auto") ?: "auto"
        set(v) = sp.edit().putString("background", v).apply()

    /** Let the kitty add facts it notices to Memory automatically. */
    var learnFacts: Boolean
        get() = sp.getBoolean("learn_facts", true)
        set(v) = sp.edit().putBoolean("learn_facts", v).apply()

    var memories: List<String>
        get() {
            val a = JSONArray(sp.getString("memories", "[]"))
            return List(a.length()) { a.getString(it) }
        }
        set(v) = sp.edit().putString("memories", JSONArray(v).toString()).apply()

    // ---- Spoken voice ----
    /** Name of the phone voice for English replies ("" = phone default). */
    var voiceEn: String
        get() = sp.getString("voice_en", "") ?: ""
        set(v) = sp.edit().putString("voice_en", v).apply()

    /** Name of the phone voice for Chinese replies ("" = phone default). */
    var voiceZh: String
        get() = sp.getString("voice_zh", "") ?: ""
        set(v) = sp.edit().putString("voice_zh", v).apply()

    /** 1.0 = normal. Higher sounds younger / brighter, lower sounds deeper. */
    var ttsPitch: Float
        get() = sp.getFloat("tts_pitch", 1.0f)
        set(v) = sp.edit().putFloat("tts_pitch", v).apply()

    /** 1.0 = normal speed. */
    var ttsRate: Float
        get() = sp.getFloat("tts_rate", 1.0f)
        set(v) = sp.edit().putFloat("tts_rate", v).apply()

    // ---- Usage counter (tokens the kitty has used since the last reset) ----
    var usageIn: Long
        get() = sp.getLong("usage_in", 0)
        set(v) = sp.edit().putLong("usage_in", v).apply()

    var usageOut: Long
        get() = sp.getLong("usage_out", 0)
        set(v) = sp.edit().putLong("usage_out", v).apply()

    /** How many times the kitty asked the AI (each answer can take a few steps). */
    var usageCalls: Long
        get() = sp.getLong("usage_calls", 0)
        set(v) = sp.edit().putLong("usage_calls", v).apply()

    /** Estimated cost in millionths of a dollar. */
    var usageCostMicros: Long
        get() = sp.getLong("usage_cost_micros", 0)
        set(v) = sp.edit().putLong("usage_cost_micros", v).apply()

    /** Date counting started (yyyy-mm-dd). */
    var usageSince: String
        get() = sp.getString("usage_since", null) ?: java.time.LocalDate.now().toString().also { usageSince = it }
        set(v) = sp.edit().putString("usage_since", v).apply()

    fun resetUsage() {
        sp.edit().putLong("usage_in", 0).putLong("usage_out", 0).putLong("usage_calls", 0)
            .putLong("usage_cost_micros", 0).putString("usage_since", java.time.LocalDate.now().toString()).apply()
    }

    // ---- MLS (Flexmls MCP server) ----
    var mlsUrl: String
        get() = sp.getString("mls_url", null)?.ifBlank { null } ?: MlsAuth.DEFAULT_URL
        set(v) = sp.edit().putString("mls_url", v.trim()).apply()

    var mlsClientId: String
        get() = sp.getString("mls_client_id", "") ?: ""
        set(v) = sp.edit().putString("mls_client_id", v).apply()

    var mlsTokenEndpoint: String
        get() = sp.getString("mls_token_endpoint", "") ?: ""
        set(v) = sp.edit().putString("mls_token_endpoint", v).apply()

    var mlsResource: String
        get() = sp.getString("mls_resource", "") ?: ""
        set(v) = sp.edit().putString("mls_resource", v).apply()

    /** The return address Flexmls accepted when this app registered. */
    var mlsRedirect: String
        get() = sp.getString("mls_redirect", "") ?: ""
        set(v) = sp.edit().putString("mls_redirect", v).apply()

    var mlsExpiresAt: Long
        get() = sp.getLong("mls_expires_at", 0)
        set(v) = sp.edit().putLong("mls_expires_at", v).apply()

    /** "state|verifier" while a sign-in is in progress. */
    var mlsPending: String
        get() = secret("mls_pending")
        set(v) = setSecret("mls_pending", v)

    var mlsAccessToken: String
        get() = secret("mls_access")
        set(v) = setSecret("mls_access", v)

    var mlsRefreshToken: String
        get() = secret("mls_refresh")
        set(v) = setSecret("mls_refresh", v)

    private fun secret(name: String): String =
        sp.getString(name, null)?.let { runCatching { KeyLock.decrypt(it) }.getOrDefault("") } ?: ""

    private fun setSecret(name: String, v: String) {
        if (v.isEmpty()) sp.edit().remove(name).apply()
        else sp.edit().putString(name, KeyLock.encrypt(v)).apply()
    }

    companion object {
        // Change this in the app's Settings if your account uses a different model.
        const val DEFAULT_MODEL = "gpt-6-luna"
        const val DEFAULT_NAME = "猫猫小助手"
        const val DEFAULT_ABOUT_ME = "" // the main profile lives in assets/about_me.md
    }
}

/** AES-GCM encryption with a key that stays inside the phone's Android Keystore. */
private object KeyLock {
    private const val ALIAS = "myagent_api_key_lock"
    private const val STORE = "AndroidKeyStore"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(data)
    }

    fun decrypt(stored: String): String {
        val (iv, data) = stored.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
