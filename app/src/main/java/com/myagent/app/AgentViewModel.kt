package com.myagent.app

import android.app.Application
import android.graphics.Bitmap
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A request waiting for the user to tap Approve or Cancel. */
class TrashApproval(val emails: List<EmailSummary>, val reason: String) {
    enum class Status { PENDING, WORKING, DONE }

    val selected = mutableStateMapOf<String, Boolean>().apply { emails.forEach { put(it.id, true) } }
    var status by mutableStateOf(Status.PENDING)
    var resultText by mutableStateOf("")
    val decision = CompletableDeferred<Set<String>?>()

    val selectedCount get() = selected.count { it.value }
    fun approve() { if (status == Status.PENDING) decision.complete(selected.filterValues { it }.keys.toSet()) }
    fun cancel() { if (status == Status.PENDING) decision.complete(null) }
}

sealed class ChatItem {
    class User(val text: String, val image: Bitmap? = null) : ChatItem()
    data class Agent(val text: String) : ChatItem()
    class AgentImage(val image: Bitmap) : ChatItem()
    data class Note(val text: String) : ChatItem()
    data class Error(val text: String) : ChatItem()
    class Approval(val approval: TrashApproval) : ChatItem()
    class Unsubscribe(val approval: UnsubscribeApproval) : ChatItem()
}

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val items = mutableStateListOf<ChatItem>()
    var busy by mutableStateOf(false)
        private set
    var waitingForApproval by mutableStateOf(false)
        private set
    val canSend get() = !busy && !waitingForApproval

    /** Set by MainActivity; needed to show Google's sign-in screen. */
    var auth: GoogleAuth? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val openAi = OpenAiClient(http)
    private val gmail = GmailClient(http)

    private var lastResponseId: String? = null

    // ---- MLS (Flexmls) connection ----
    private val mls = MlsAuth(http, prefs)
    var mlsConnected by mutableStateOf(prefs.mlsAccessToken.isNotBlank())
        private set
    var mlsStatus by mutableStateOf("")
        private set

    /** Step 1: get the Flexmls login page; [open] shows it in the browser. */
    private var mlsJob: kotlinx.coroutines.Job? = null

    fun connectMls(open: (String) -> Unit) {
        mlsStatus = "Opening Flexmls sign-in…"
        mlsJob?.cancel()
        mlsJob = viewModelScope.launch {
            val url = runCatching { mls.startUrl() }.getOrElse {
                mlsStatus = it.message ?: "Couldn't start MLS sign-in."
                return@launch
            }
            mlsStatus = "Log in on the Flexmls page, then come back."
            open(url)
            runCatching { mls.waitForRedirect() }
                .onSuccess { finishMls(it) }
                .onFailure {
                    // A closed listener just means a newer Connect tap replaced this one.
                    if (it !is java.net.SocketException) mlsStatus = it.message ?: "MLS sign-in failed."
                }
        }
    }

    /** Step 2: Flexmls sent the browser back to the app. */
    fun finishMls(redirect: android.net.Uri) {
        viewModelScope.launch {
            mlsStatus = "Finishing MLS sign-in… (come back to the app if you're still in the browser)"
            runCatching {
                // The phone blocks internet for apps in the background (behind the browser),
                // so keep retrying for up to 2 minutes until the app is back on screen.
                var attempt = 0
                while (true) {
                    try {
                        mls.finish(redirect)
                        break
                    } catch (e: java.io.IOException) {
                        val networkProblem = e is java.net.UnknownHostException ||
                            e is java.net.ConnectException || e is java.net.SocketTimeoutException
                        if (!networkProblem || ++attempt >= 40) throw e
                        kotlinx.coroutines.delay(3000)
                    }
                }
            }
                .onSuccess {
                    mlsConnected = true
                    mlsStatus = "Connected ✓"
                    items += ChatItem.Note("MLS connected ✓")
                }
                .onFailure {
                    mlsStatus = it.message ?: "MLS sign-in failed."
                    items += ChatItem.Error(mlsStatus)
                }
        }
    }

    fun disconnectMls() {
        mls.disconnect()
        mlsConnected = false
        mlsStatus = "Disconnected."
    }

    // ---- Long-term storage: chats, messages and searchable pieces (kitty.db) ----
    private val store = ChatStore(app)
    /** One database thread, so saves happen in order. */
    private val dbThread = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private fun persist(role: String, text: String) {
        val id = chatId
        viewModelScope.launch(dbThread) { runCatching { store.addMessage(id, role, text) } }
    }

    private fun rememberResponse(responseId: String) {
        lastResponseId = responseId
        val id = chatId
        viewModelScope.launch(dbThread) { runCatching { store.setLastResponseId(id, responseId) } }
    }

    suspend fun historyList(): List<ChatStore.ChatInfo> = withContext(dbThread) { store.chats() }

    /** Reopens a saved conversation; you can keep chatting where you left off. */
    fun openChat(info: ChatStore.ChatInfo) {
        if (!canSend) return
        saveRecentActivity()
        tts.stop()
        viewModelScope.launch {
            val msgs = withContext(dbThread) { store.messages(info.id) }
            items.clear()
            seenEmails.clear()
            msgs.forEach { m -> items += if (m.role == "user") ChatItem.User(m.text) else ChatItem.Agent(m.text) }
            chatId = info.id
            lastResponseId = info.lastResponseId
            notedResponseId = info.lastResponseId
        }
    }

    fun deleteChat(id: Long) {
        viewModelScope.launch(dbThread) { runCatching { store.deleteChat(id) } }
        prefs.recentActivity = prefs.recentActivity.filterNot { it.startsWith("$id|") }
        if (id == chatId) {
            items.clear(); seenEmails.clear(); lastResponseId = null; notedResponseId = null
            chatId = System.currentTimeMillis()
        }
    }

    /** Turns the newest messages of a chat into searchable pieces with embeddings. */
    private suspend fun indexChat(id: Long) {
        val msgs = withContext(dbThread) { store.unindexed(id) }
        if (msgs.isEmpty()) return
        val chunks = ChatStore.makeChunks(msgs, prefs.agentName)
        val embeddings = openAi.embed(prefs.apiKey, chunks)
        withContext(dbThread) { store.addChunks(id, chunks, embeddings, msgs.last().id) }
    }
    /** Emails the agent has seen this chat. The agent can only propose trashing these. */
    private val seenEmails = mutableMapOf<String, EmailSummary>()

    // ---- Spoken replies (Android's built-in text-to-speech) ----
    var speakReplies by mutableStateOf(prefs.speakReplies)
        private set
    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(app) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    private val main = Handler(Looper.getMainLooper())
    private var pendingUtterances = 0
    private var utteranceCounter = 0

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { utteranceFinished() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { utteranceFinished() } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { utteranceFinished() } }
        })
    }

    private fun utteranceFinished() {
        pendingUtterances = (pendingUtterances - 1).coerceAtLeast(0)
        if (pendingUtterances == 0 && voiceState == VoiceState.SPEAKING) {
            if (busy) voiceState = VoiceState.THINKING else listen()
        }
    }

    // ---- Hands-free voice conversation mode ----
    enum class VoiceState { OFF, LISTENING, THINKING, SPEAKING }

    var voiceState by mutableStateOf(VoiceState.OFF)
        private set
    /** 0..1 loudness of your voice, for the orb animation. */
    var voiceLevel by mutableStateOf(0f)
        private set
    /** What you're saying so far, shown live under the orb. */
    var voicePartial by mutableStateOf("")
        private set
    private var recognizer: SpeechRecognizer? = null
    private var quietRounds = 0

    fun startVoiceMode() {
        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            showError("Voice recognition isn't available. Install or update the Google app.")
            return
        }
        if (prefs.apiKey.isBlank()) {
            showError("Add your OpenAI API key in Settings first.")
            return
        }
        quietRounds = 0
        voiceState = VoiceState.LISTENING
        listen()
    }

    fun stopVoiceMode() {
        if (voiceState == VoiceState.OFF) return
        voiceState = VoiceState.OFF
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        pendingUtterances = 0
        voiceLevel = 0f
        voicePartial = ""
    }

    /** Tapping the orb interrupts the kitty (talking or thinking) so you can speak again. */
    fun tapOrb() {
        when {
            voiceState == VoiceState.SPEAKING && !busy -> {
                tts.stop()
                pendingUtterances = 0
                listen()
            }
            busy || waitingForApproval || voiceState == VoiceState.SPEAKING -> stopAgent()
        }
    }

    // ---- Stop button: cancel whatever the kitty is doing right now ----
    private var agentJob: kotlinx.coroutines.Job? = null
    @Volatile private var stopRequested = false

    fun stopAgent() {
        if (agentJob == null && !busy && !waitingForApproval) return
        stopRequested = true
        tts.stop()
        pendingUtterances = 0
        // Close any approval card that was waiting, so nothing gets deleted or sent.
        items.forEach {
            when (it) {
                is ChatItem.Approval -> if (it.approval.status == TrashApproval.Status.PENDING) {
                    it.approval.status = TrashApproval.Status.DONE
                    it.approval.resultText = "Stopped. Nothing was deleted."
                }
                is ChatItem.Unsubscribe -> if (it.approval.status == TrashApproval.Status.PENDING) {
                    it.approval.status = TrashApproval.Status.DONE
                    it.approval.resultText = "Stopped. Nothing was sent."
                }
                else -> {}
            }
        }
        http.dispatcher.cancelAll() // abort the request that's on its way to OpenAI
        agentJob?.cancel()
    }

    private fun listen() {
        if (voiceState == VoiceState.OFF) return
        if (busy || waitingForApproval) {
            voiceState = VoiceState.THINKING
            return
        }
        voiceState = VoiceState.LISTENING
        voicePartial = ""
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(getApplication()).also {
            it.setRecognitionListener(voiceListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        prefs.voiceLanguage.takeIf { it.isNotBlank() }?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        r.startListening(intent)
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { voiceLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { voiceLevel = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            voicePartial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        }

        override fun onResults(results: Bundle?) {
            voiceLevel = 0f
            if (voiceState != VoiceState.LISTENING) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) {
                main.postDelayed({ listen() }, 200)
                return
            }
            quietRounds = 0
            voicePartial = ""
            voiceState = VoiceState.THINKING
            send(text, null, viaVoice = true)
        }

        override fun onError(error: Int) {
            voiceLevel = 0f
            if (voiceState != VoiceState.LISTENING) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopVoiceMode()
                showError("Voice mode needs microphone permission.")
                return
            }
            // Silence or a hiccup: keep listening, but rest after about a minute of quiet.
            if (++quietRounds > 12) {
                stopVoiceMode()
                showNote("Voice mode paused after a quiet minute. Tap the voice button to talk again.")
                return
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                recognizer?.destroy()
                recognizer = null
            }
            main.postDelayed({ listen() }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600L else 250L)
        }
    }

    fun toggleSpeakReplies() {
        speakReplies = !speakReplies
        prefs.speakReplies = speakReplies
        if (!speakReplies) tts.stop()
    }

    fun stopSpeaking() { tts.stop() }

    private fun speak(text: String) {
        if (!ttsReady) return
        val clean = text
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1") // [label](link) -> label
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*#`_>|]"), "")
            // never read emoji aloud (😊, ✨, 🐱, flags, hearts…)
            .replace(Regex("[\\p{So}\\x{1F000}-\\x{1FAFF}\\x{1F1E6}-\\x{1F1FF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}\\x{20E3}]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (clean.isEmpty()) return
        val chinese = clean.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        tts.setLanguage(if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.getDefault())
        applyVoice(if (chinese) prefs.voiceZh else prefs.voiceEn, prefs.ttsPitch, prefs.ttsRate)
        clean.chunked(3000).forEach {
            pendingUtterances++
            tts.speak(it, TextToSpeech.QUEUE_ADD, null, "u${utteranceCounter++}")
        }
        if (voiceState != VoiceState.OFF) voiceState = VoiceState.SPEAKING
    }

    /** A voice installed on the phone that the kitty can speak with. */
    data class VoiceOption(val name: String, val label: String, val chinese: Boolean)

    /** English and Chinese voices installed on the phone (Google / Samsung text-to-speech). */
    fun voiceOptions(): List<VoiceOption> {
        if (!ttsReady) return emptyList()
        val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        return voices
            .filter { it.locale.language == "en" || it.locale.language == "zh" }
            .filterNot { it.features?.contains("notInstalled") == true }
            .sortedWith(compareBy({ it.locale.language != "en" }, { it.locale.toString() }, { it.name }))
            .map { v ->
                val zh = v.locale.language == "zh"
                // Engine voice names look like "en-us-x-iol-local"; keep the short code to tell them apart.
                val code = v.name.split("-").let { parts -> parts.getOrNull(parts.size - 2) ?: v.name }
                val online = if (v.isNetworkConnectionRequired) " · online" else ""
                VoiceOption(v.name, "${v.locale.displayName} · $code$online", zh)
            }
    }

    private fun applyVoice(name: String, pitch: Float, rate: Float) {
        if (name.isNotBlank()) {
            runCatching { tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
        }
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
    }

    /** Plays a short sample so you can hear a voice before choosing it. */
    fun previewVoice(name: String, chinese: Boolean, pitch: Float, rate: Float) {
        if (!ttsReady) return
        tts.stop()
        tts.setLanguage(if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.getDefault())
        applyVoice(name, pitch, rate)
        val sample = if (chinese) "你好！我是${prefs.agentName}，很高兴为你服务。"
        else "Hi! I'm your kitty assistant. How can I help you today?"
        tts.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    override fun onCleared() {
        stopVoiceMode()
        dbThread.close()
        tts.shutdown()
        super.onCleared()
    }

    fun showError(message: String) { items += ChatItem.Error(message) }
    fun showNote(message: String) { items += ChatItem.Note(message) }

    private suspend fun decodeImage(b64: String): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    fun newChat() {
        if (!canSend) return
        saveRecentActivity()
        tts.stop()
        items.clear()
        seenEmails.clear()
        lastResponseId = null
        chatId = System.currentTimeMillis()
    }

    // ---- Recent-activity memory: a one-line note per conversation ----
    private var chatId = System.currentTimeMillis()
    private var notedResponseId: String? = null

    /** Adds one AI call to the usage counter shown in Settings. */
    private fun recordUsage(reply: OpenAiClient.Reply) {
        prefs.usageIn = prefs.usageIn + reply.inputTokens
        prefs.usageOut = prefs.usageOut + reply.outputTokens
        prefs.usageCalls = prefs.usageCalls + 1
        val price = pricePerMillion(reply.model.ifBlank { prefs.model })
        if (price != null) {
            // dollars per million tokens × tokens = millionths of a dollar
            val micros = price.first * reply.inputTokens + price.second * reply.outputTokens
            prefs.usageCostMicros = prefs.usageCostMicros + micros.toLong()
        }
    }

    /** Writes (or updates) a short note about this chat. Called on New chat and when the app goes to the background. */
    fun saveRecentActivity() {
        val respId = lastResponseId ?: return
        if (respId == notedResponseId || prefs.apiKey.isBlank()) return
        notedResponseId = respId
        val id = chatId
        viewModelScope.launch {
            // 1) A one-line note about the chat, plus any new lasting facts about you.
            runCatching {
                val existing = prefs.memories
                val ask = "Reply with JSON only, no other text, in this shape: " +
                    "{\"note\": \"ONE short line (max 20 words) noting what the user wanted or did in this conversation, " +
                    "same language as the user\", \"facts\": [\"lasting facts the user stated about themselves, their " +
                    "work, people in their life, preferences or plans\"]}. Only include facts that will still be true for " +
                    "weeks, written as short sentences. Skip anything already known: ${JSONArray(existing)}. " +
                    "Use an empty list when there is nothing new."
                val noteReply = openAi.respond(
                    prefs.apiKey, prefs.model,
                    "You keep short private notes about the user's conversations.",
                    JSONArray().put(JSONObject().put("role", "user").put("content", ask)),
                    JSONArray(), respId,
                )
                recordUsage(noteReply)
                val raw = noteReply.texts.joinToString(" ")
                val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
                val note = json.optString("note").trim().replace("\n", " ")
                if (note.isNotBlank()) {
                    val others = prefs.recentActivity.filterNot { it.startsWith("$id|") }
                    prefs.recentActivity = others + "$id|${LocalDate.now()}|$note"
                }
                if (prefs.learnFacts) {
                    val factsArr = json.optJSONArray("facts") ?: JSONArray()
                    val known = existing.map { it.lowercase().trim() }.toSet()
                    val fresh = List(factsArr.length()) { factsArr.optString(it).trim() }
                        .filter { it.isNotBlank() && it.lowercase() !in known }
                        .take(5)
                    if (fresh.isNotEmpty()) prefs.memories = existing + fresh
                }
            }
            // 2) Make this chat searchable later (RAG).
            runCatching { indexChat(id) }
        }
    }

    /** [viaVoice] = the user spoke this message, so the reply is read aloud too. */
    fun send(text: String, image: Bitmap? = null, viaVoice: Boolean = false) {
        if ((text.isBlank() && image == null) || !canSend) return
        tts.stop()
        items += ChatItem.User(text, image)
        persist("user", if (image != null) "[photo] $text".trim() else text)
        if (prefs.apiKey.isBlank()) {
            items += ChatItem.Error("Add your OpenAI API key in Settings first.")
            return
        }
        stopRequested = false
        agentJob = viewModelScope.launch { runAgent(text, image, viaVoice || speakReplies) }
    }

    private suspend fun jpegDataUrl(image: Bitmap): String = withContext(Dispatchers.Default) {
        val out = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 85, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun runAgent(userText: String, image: Bitmap?, speakAloud: Boolean) {
        busy = true
        try {
            val content: Any = if (image == null) userText else JSONArray()
                .put(JSONObject().put("type", "input_text").put("text", userText.ifBlank { "What's in this photo?" }))
                .put(JSONObject().put("type", "input_image").put("image_url", jpegDataUrl(image)))
            var input = JSONArray().put(JSONObject().put("role", "user").put("content", content))
            var previousId = lastResponseId
            var tokensIn = 0
            var tokensOut = 0
            val mlsToken = if (mlsConnected) mls.accessToken() else null
            repeat(MAX_STEPS) {
                val reply = try {
                    openAi.respond(prefs.apiKey, prefs.model, instructions(), input, tools(mlsToken), previousId)
                } catch (e: java.io.IOException) {
                    // An old chat's server-side context can expire; carry on as a fresh thread.
                    if (previousId != null && e.message?.contains("previous", ignoreCase = true) == true) {
                        previousId = null
                        lastResponseId = null
                        openAi.respond(prefs.apiKey, prefs.model, instructions(), input, tools(mlsToken), null)
                    } else throw e
                }
                previousId = reply.id
                tokensIn += reply.inputTokens
                tokensOut += reply.outputTokens
                recordUsage(reply)
                reply.texts.forEach {
                    items += ChatItem.Agent(it)
                    persist("agent", it)
                    if (speakAloud) speak(it)
                }
                reply.images.forEach { b64 ->
                    decodeImage(b64)?.let { items += ChatItem.AgentImage(it) }
                }
                if (reply.calls.isEmpty()) {
                    // Only remember finished turns, so a failed step never breaks the next message.
                    rememberResponse(reply.id)
                    if (prefs.showDetails) {
                        items += ChatItem.Note(
                            "Model: ${reply.model.ifBlank { prefs.model }} · " +
                                "Tokens: %,d in / %,d out".format(tokensIn, tokensOut)
                        )
                    }
                    return
                }

                input = JSONArray()
                for (call in reply.calls) {
                    val output = try {
                        runTool(call.name, JSONObject(call.arguments.ifBlank { "{}" }))
                    } catch (e: Exception) {
                        items += ChatItem.Error("${call.name} failed: ${e.message}")
                        "Error: ${e.message}"
                    }
                    input.put(
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", call.callId)
                            .put("output", output)
                    )
                }
            }
            items += ChatItem.Note("Stopped after $MAX_STEPS steps.")
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            if (stopRequested) items += ChatItem.Note("Stopped.")
            else if (mlsConnected && msg.contains("mcp", ignoreCase = true)) {
                items += ChatItem.Error(
                    "Couldn't reach your MLS. The sign-in may have expired: open Settings, tap Disconnect, " +
                        "then Connect MLS again.\n($msg)"
                )
            } else items += ChatItem.Error(msg)
        } finally {
            busy = false
            waitingForApproval = false
            agentJob = null
            // In voice mode, listen again once the kitty has finished talking.
            main.post { if (voiceState != VoiceState.OFF && pendingUtterances == 0) listen() }
        }
    }

    private suspend fun gmailToken(): String {
        val a = auth ?: throw IllegalStateException("The app isn't ready for Google sign-in. Reopen the app.")
        return try {
            a.accessToken()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Gmail sign-in failed (${e.message}). Check the Google Cloud setup in SETUP.md, step 3."
            )
        }
    }

    private suspend fun runTool(name: String, args: JSONObject): String = when (name) {
        "remember" -> {
            val fact = args.optString("fact").trim()
            if (fact.isNotEmpty()) {
                prefs.memories = prefs.memories + fact
                items += ChatItem.Note("Remembered: $fact")
            }
            "Saved."
        }

        "search_gmail" -> {
            val query = args.optString("query").ifBlank { "category:promotions" }
            val max = args.optInt("max_results", 30)
            val found = gmail.search(gmailToken(), query, max)
            found.forEach { seenEmails[it.id] = it }
            items += ChatItem.Note("Searched Gmail for \"$query\": ${found.size} emails")
            val arr = JSONArray()
            found.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("from", it.from).put("subject", it.subject)
                        .put("date", it.date).put("snippet", it.snippet.take(140))
                        .put("starred", it.starred)
                        .put("can_unsubscribe", it.listUnsubscribe.isNotBlank())
                )
            }
            arr.toString()
        }

        "propose_trash" -> proposeTrash(args)

        "propose_unsubscribe" -> proposeUnsubscribe(args)

        "search_past_chats" -> {
            val query = args.optString("query").trim()
            val k = args.optInt("max_results", 5).coerceIn(1, 10)
            if (query.isEmpty()) "Give a query to search for." else {
                val qe = openAi.embed(prefs.apiKey, listOf(query)).first()
                val hits = withContext(dbThread) { store.search(qe, k, excludeChat = chatId) }.filter { it.score > 0.2f }
                items += ChatItem.Note("Searched past chats for \"$query\": ${hits.size} found")
                val arr = JSONArray()
                hits.forEach { h ->
                    val day = java.time.Instant.ofEpochMilli(h.created).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    arr.put(JSONObject().put("date", day.toString()).put("chat", h.title).put("text", h.text))
                }
                if (hits.isEmpty()) "No matching past conversations found." else arr.toString()
            }
        }

        else -> "Unknown tool: $name"
    }

    private suspend fun proposeTrash(args: JSONObject): String {
        val idsArr = args.optJSONArray("message_ids") ?: JSONArray()
        val ids = List(idsArr.length()) { idsArr.getString(it) }
        // Safety: only emails found by search_gmail in this chat, never starred ones, at most 100.
        val emails = ids.distinct().mapNotNull { seenEmails[it] }.filterNot { it.starred }.take(100)
        if (emails.isEmpty()) {
            return "Nothing to propose. Only ids returned by search_gmail can be used, and starred emails are never trashed."
        }

        val approval = TrashApproval(emails, args.optString("reason"))
        items += ChatItem.Approval(approval)
        busy = false
        waitingForApproval = true
        val chosen = approval.decision.await()
        waitingForApproval = false
        busy = true

        if (chosen.isNullOrEmpty()) {
            approval.status = TrashApproval.Status.DONE
            approval.resultText = "Cancelled. Nothing was deleted."
            return "The user declined. Nothing was moved to Trash."
        }
        approval.status = TrashApproval.Status.WORKING
        val moved = try {
            gmail.trash(gmailToken(), chosen)
        } catch (e: Exception) {
            approval.status = TrashApproval.Status.DONE
            approval.resultText = "Couldn't move emails: ${e.message}"
            throw e
        }
        approval.status = TrashApproval.Status.DONE
        approval.resultText = "Moved $moved emails to Trash. You can restore them from Gmail's Trash for 30 days."
        val kept = emails.size - chosen.size
        return "The user approved ${chosen.size} emails (kept $kept). Moved $moved to Trash."
    }

    private suspend fun proposeUnsubscribe(args: JSONObject): String {
        val idsArr = args.optJSONArray("message_ids") ?: JSONArray()
        val ids = List(idsArr.length()) { idsArr.getString(it) }
        val emails = ids.distinct().mapNotNull { seenEmails[it] }.filterNot { it.starred }
        if (emails.isEmpty()) return "Nothing to propose. Use ids returned by search_gmail in this chat."

        // One entry per sender, using the best unsubscribe method any of their emails offers.
        val order = listOf(UnsubTarget.Method.ONE_CLICK, UnsubTarget.Method.EMAIL, UnsubTarget.Method.LINK)
        val targets = emails.mapNotNull { UnsubTarget.from(it) }
            .groupBy { it.key }
            .map { (_, options) -> options.minBy { order.indexOf(it.method) } }
            .take(50)
        if (targets.isEmpty()) return "None of these emails include an unsubscribe option."

        val approval = UnsubscribeApproval(targets, args.optString("reason"))
        items += ChatItem.Unsubscribe(approval)
        busy = false
        waitingForApproval = true
        val chosen = approval.decision.await()
        waitingForApproval = false
        busy = true

        if (chosen.isNullOrEmpty()) {
            approval.status = TrashApproval.Status.DONE
            approval.resultText = "Cancelled. No unsubscribe requests were sent."
            return "The user declined. Nothing was unsubscribed."
        }
        approval.status = TrashApproval.Status.WORKING
        var ok = 0
        val failed = mutableListOf<String>()
        val needToken = targets.any { it.key in chosen && it.method == UnsubTarget.Method.EMAIL }
        val token = if (needToken) gmailToken() else ""
        for (t in targets.filter { it.key in chosen }) {
            val success = when (t.method) {
                UnsubTarget.Method.ONE_CLICK -> gmail.oneClickUnsubscribe(t.target)
                UnsubTarget.Method.EMAIL -> gmail.sendUnsubscribeEmail(token, t.target)
                UnsubTarget.Method.LINK -> false
            }
            approval.results[t.key] = if (success) "Unsubscribed ✓" else "Didn't go through"
            if (success) ok++ else failed += t.name
        }
        val manual = targets.count { it.method == UnsubTarget.Method.LINK }
        approval.status = TrashApproval.Status.DONE
        approval.resultText = buildString {
            append("Unsubscribed from $ok sender${if (ok == 1) "" else "s"}.")
            if (failed.isNotEmpty()) append(" ${failed.size} didn't go through; use their Open page link.")
            append(" It can take a few days for emails to stop.")
        }
        return "Unsubscribed from $ok senders. Failed: ${failed.joinToString().ifBlank { "none" }}. " +
            "$manual senders only offer a web link the user must open themselves (shown in the card)."
    }

    /** Your profile file, built into the app from app/src/main/assets/about_me.md. */
    private val aboutMeFile: String by lazy {
        runCatching {
            getApplication<Application>().assets.open("about_me.md").bufferedReader().use { it.readText() }
        }.getOrDefault("")
            .lines()
            .filterNot { it.trim().startsWith("- (Example") } // unfilled example lines are ignored
            .joinToString("\n").trim()
    }

    private fun instructions(): String {
        val memories = prefs.memories
        val memoryText = if (memories.isEmpty()) "(nothing yet)" else memories.joinToString("\n") { "- $it" }
        val recent = prefs.recentActivity.takeLast(10).mapNotNull {
            val parts = it.split("|", limit = 3)
            if (parts.size == 3) "- ${parts[1]}: ${parts[2]}" else null
        }
        val recentText = if (recent.isEmpty()) "(nothing yet)" else recent.joinToString("\n")
        val now = java.time.ZonedDateTime.now()
        val clock = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a z"))
        val mlsText = if (mlsConnected) listOf(
            "- The user's Flexmls MLS is connected (the flexmls tools). Use it for their",
            "  listings, property searches, open houses, photos and market statistics.",
            "- For \"check my MLS\", summarize their own listings: address, price, status, days on market,",
            "  plus upcoming open houses. Keep it short; offer details on request.",
            "- Never make up listing data; only report what the MLS returns.",
        ).joinToString("\n") else
            "- The MLS isn't connected. If the user asks about the MLS, tell them to tap Connect MLS in Settings."
        return """
            You are ${prefs.agentName}, a personal assistant running in an app on the user's Android phone.
            It is now $clock. Reply briefly and plainly; this is read on a phone screen
            and may be read aloud, so avoid tables and long lists. Reply in the user's language.
            The user can send photos; describe or answer questions about them when they do.
            You can create pictures with the image_generation tool when the user asks you to draw, make,
            or edit an image. Keep the text with a picture to one short sentence.

            Tools:
            - web_search for anything current (news, prices, hours, facts that change).
            - remember: save a lasting fact or preference the user tells you about themselves.
            - search_past_chats: search the user's earlier conversations saved on the phone. Use it when they
              refer to something from before ("last week", "what did I say about…", "that restaurant you suggested").
            - search_gmail: search the user's Gmail with Gmail search syntax.
            - propose_trash: show the user a list of emails to move to Trash. You can NOT delete
              anything yourself; the user approves or edits the list in the app.

            Cleaning up email:
            1. Search first, e.g. "category:promotions older_than:7d" or "unsubscribe older_than:30d".
            2. Pick only real junk: ads, promotions, marketing newsletters, sales.
            3. Never propose: starred mail, receipts, orders and shipping, bank/bills, security codes,
               account or password notices, travel bookings, or mail from real people.
            4. Call propose_trash once with the ids and a one-sentence reason. Then report the result.

            Unsubscribing:
            - propose_unsubscribe shows the user a list of senders to unsubscribe from; nothing is sent
              until they approve. Use ids from search_gmail where can_unsubscribe is true.
            - Emails already moved to Trash can still be found with a search like "in:trash category:promotions".
            - Only suggest real stores, brands and newsletters. Never unsubscribe from obvious spam or scams
              (it confirms the address is real), banks, schools, government, or anything that looks personal.

            MLS (real estate):
            $mlsText

            About the user (always true; use it, e.g. their home city for weather, without asking):
            $aboutMeFile
            ${prefs.aboutMe}

            Things the user asked you to remember:
            $memoryText

            Recent conversations (your own notes, oldest first). Use them for context when relevant:
            $recentText
        """.trimIndent()
    }

    private fun tools(mlsToken: String?): JSONArray = JSONArray()
        .put(JSONObject().put("type", "web_search"))
        .apply {
            // The user's MLS: OpenAI connects to the Flexmls MCP server with the user's access pass.
            if (mlsToken != null) put(
                JSONObject()
                    .put("type", "mcp")
                    .put("server_label", "flexmls")
                    .put("server_description", "The user's Flexmls MLS: listings, photos, open houses and market statistics.")
                    .put("server_url", prefs.mlsUrl)
                    .put("headers", JSONObject().put("Authorization", "Bearer $mlsToken"))
                    .put("require_approval", "never")
            )
        }
        .put(
            JSONObject()
                .put("type", "image_generation")
                .put("size", "1024x1024")
                .put("quality", "medium")
        )
        .put(
            function(
                "remember", "Save a lasting fact about the user for future chats.",
                JSONObject().put("fact", str("The fact, in one short sentence.")),
                listOf("fact"),
            )
        )
        .put(
            function(
                "search_gmail", "Search the user's Gmail. Returns id, sender, subject, date and a snippet for each email.",
                JSONObject()
                    .put("query", str("Gmail search query, e.g. category:promotions older_than:14d"))
                    .put("max_results", JSONObject().put("type", "integer").put("description", "1-100, default 30")),
                listOf("query"),
            )
        )
        .put(
            function(
                "propose_trash",
                "Ask the user to approve moving emails to Trash. Shows an approval card; waits for their answer.",
                JSONObject()
                    .put("message_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                    .put("reason", str("One sentence: why these emails are junk.")),
                listOf("message_ids", "reason"),
            )
        )
        .put(unsubscribeTool())
        .put(
            function(
                "search_past_chats",
                "Search the user's earlier conversations by meaning. Returns matching pieces with their dates.",
                JSONObject()
                    .put("query", str("What to look for, e.g. 'Johnson house offer' or 'restaurant recommendation'"))
                    .put("max_results", JSONObject().put("type", "integer").put("description", "1-10, default 5")),
                listOf("query"),
            )
        )

    private fun unsubscribeTool() = function(
        "propose_unsubscribe",
        "Ask the user to approve unsubscribing from the senders of these emails. Shows an approval card; waits for their answer.",
        JSONObject()
            .put("message_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("reason", str("One sentence: why these senders are worth unsubscribing from.")),
        listOf("message_ids", "reason"),
    )

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    private fun function(name: String, desc: String, props: JSONObject, required: List<String>) =
        JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", desc)
            .put("strict", false)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
            )

    companion object {
        /**
         * OpenAI list prices in dollars per 1 million tokens (input, output), standard tier, Oct 2026.
         * Used only for the rough cost estimate in Settings. Unknown models aren't priced.
         */
        fun pricePerMillion(model: String): Pair<Double, Double>? = when {
            model.startsWith("gpt-6-luna") -> 0.10 to 0.50
            model.startsWith("gpt-6.1-sol") -> 2.00 to 10.00
            model.startsWith("gpt-6-astra") -> 10.00 to 50.00
            model.startsWith("gpt-5.5") -> 5.00 to 30.00
            else -> null
        }

        private const val MAX_STEPS = 10
    }
}
