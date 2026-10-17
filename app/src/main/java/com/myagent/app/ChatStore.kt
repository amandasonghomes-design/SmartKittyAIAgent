package com.myagent.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The kitty's long-term storage: one SQLite file (kitty.db) in the app's private folder.
 *
 *  chats    - one row per conversation (title, dates, OpenAI response id to continue it)
 *  messages - every message you and the kitty sent
 *  chunks   - pieces of past chats + their embeddings ("meaning numbers") for RAG search
 */
class ChatStore(context: Context) : SQLiteOpenHelper(context, "kitty.db", null, 1) {

    data class ChatInfo(val id: Long, val title: String, val updated: Long, val lastResponseId: String?)
    data class StoredMessage(val id: Long, val role: String, val text: String)
    data class Hit(val chatId: Long, val created: Long, val title: String, val text: String, val score: Float)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE chats(
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, created INTEGER NOT NULL,
                updated INTEGER NOT NULL, last_response_id TEXT, indexed_upto INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL,
                role TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX messages_chat ON messages(chat_id, id)")
        db.execSQL(
            """CREATE TABLE chunks(
                id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL,
                created INTEGER NOT NULL, text TEXT NOT NULL, embedding BLOB NOT NULL)"""
        )
        db.execSQL("CREATE INDEX chunks_chat ON chunks(chat_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- chats & messages ----------

    @Synchronized
    fun addMessage(chatId: Long, role: String, text: String) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val exists = db.rawQuery("SELECT 1 FROM chats WHERE id=?", arrayOf(chatId.toString())).use { it.moveToFirst() }
            if (!exists) {
                db.insert("chats", null, ContentValues().apply {
                    put("id", chatId)
                    put("title", text.lines().first().take(60).ifBlank { "Chat" })
                    put("created", now)
                    put("updated", now)
                })
            } else {
                db.update("chats", ContentValues().apply { put("updated", now) }, "id=?", arrayOf(chatId.toString()))
            }
            db.insert("messages", null, ContentValues().apply {
                put("chat_id", chatId); put("role", role); put("text", text); put("created", now)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun setLastResponseId(chatId: Long, responseId: String) {
        writableDatabase.update(
            "chats", ContentValues().apply { put("last_response_id", responseId) }, "id=?", arrayOf(chatId.toString())
        )
    }

    @Synchronized
    fun chats(): List<ChatInfo> = readableDatabase.rawQuery(
        "SELECT id, title, updated, last_response_id FROM chats ORDER BY updated DESC LIMIT 200", null
    ).use { c ->
        buildList { while (c.moveToNext()) add(ChatInfo(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3))) }
    }

    @Synchronized
    fun messages(chatId: Long): List<StoredMessage> = readableDatabase.rawQuery(
        "SELECT id, role, text FROM messages WHERE chat_id=? ORDER BY id", arrayOf(chatId.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(StoredMessage(c.getLong(0), c.getString(1), c.getString(2))) } }

    @Synchronized
    fun deleteChat(chatId: Long) {
        val db = writableDatabase
        val arg = arrayOf(chatId.toString())
        db.delete("messages", "chat_id=?", arg)
        db.delete("chunks", "chat_id=?", arg)
        db.delete("chats", "id=?", arg)
    }

    // ---------- RAG: pieces of chats with their embeddings ----------

    /** Messages of this chat not yet turned into searchable pieces. */
    @Synchronized
    fun unindexed(chatId: Long): List<StoredMessage> {
        val upto = readableDatabase.rawQuery(
            "SELECT indexed_upto FROM chats WHERE id=?", arrayOf(chatId.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else return emptyList() }
        return readableDatabase.rawQuery(
            "SELECT id, role, text FROM messages WHERE chat_id=? AND id>? AND role IN ('user','agent') ORDER BY id",
            arrayOf(chatId.toString(), upto.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(StoredMessage(c.getLong(0), c.getString(1), c.getString(2))) } }
    }

    @Synchronized
    fun addChunks(chatId: Long, texts: List<String>, embeddings: List<FloatArray>, uptoMessageId: Long) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            texts.zip(embeddings).forEach { (t, e) ->
                db.insert("chunks", null, ContentValues().apply {
                    put("chat_id", chatId); put("created", now); put("text", t); put("embedding", toBytes(e))
                })
            }
            db.update("chats", ContentValues().apply { put("indexed_upto", uptoMessageId) }, "id=?", arrayOf(chatId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Finds the past-chat pieces whose meaning is closest to [query] (cosine similarity). */
    @Synchronized
    fun search(query: FloatArray, limit: Int, excludeChat: Long?): List<Hit> {
        val qn = norm(query)
        val hits = mutableListOf<Hit>()
        readableDatabase.rawQuery(
            "SELECT k.chat_id, k.created, IFNULL(c.title,''), k.text, k.embedding FROM chunks k LEFT JOIN chats c ON c.id=k.chat_id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val chat = c.getLong(0)
                if (chat == excludeChat) continue
                val e = fromBytes(c.getBlob(4))
                if (e.size != query.size) continue
                var dot = 0f
                for (i in e.indices) dot += e[i] * query[i]
                val score = dot / (qn * norm(e))
                hits += Hit(chat, c.getLong(1), c.getString(2), c.getString(3), score)
            }
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    private fun norm(v: FloatArray): Float {
        var s = 0f
        for (x in v) s += x * x
        return sqrt(s).coerceAtLeast(1e-6f)
    }

    private fun toBytes(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

    private fun fromBytes(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }

    companion object {
        /** Groups messages into searchable pieces of about [max] characters. */
        fun makeChunks(messages: List<StoredMessage>, agentName: String, max: Int = 1200): List<String> {
            val chunks = mutableListOf<String>()
            val cur = StringBuilder()
            for (m in messages) {
                val line = (if (m.role == "user") "User: " else "$agentName: ") + m.text.trim()
                if (cur.isNotEmpty() && cur.length + line.length > max) {
                    chunks += cur.toString(); cur.clear()
                }
                cur.append(line.take(max)).append('\n')
            }
            if (cur.isNotBlank()) chunks += cur.toString().trim()
            return chunks
        }
    }
}
