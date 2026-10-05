package com.jarvis.app.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Mémoire de chaque conversation (une par contact), gardée sur le téléphone :
 *  - les derniers messages échangés (les tiens, ceux du contact, et ceux que Jarvis a écrits à ta place) ;
 *  - un résumé des échanges plus anciens + les détails retenus (projets, rendez-vous, questions en suspens...).
 * C'est ce qui permet à Jarvis de reprendre le fil comme un humain, même si la notification a disparu
 * ou si la conversation date d'hier. Seuls les contacts autorisés sont enregistrés.
 */
class ConversationStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jarvis_conversations.db", null, 2) {

    data class Msg(val fromMe: Boolean, val text: String, val ts: Long, val app: String)

    /** Une conversation connue, pour l'écran « Conversations ». */
    data class ContactInfo(val key: String, val display: String, val count: Int, val lastTs: Long, val summary: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, contact TEXT NOT NULL, app TEXT NOT NULL, " +
                "from_me INTEGER NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL, UNIQUE(contact, from_me, text, ts))"
        )
        db.execSQL("CREATE INDEX idx_messages_contact_ts ON messages(contact, ts)")
        db.execSQL(
            "CREATE TABLE notes (contact TEXT PRIMARY KEY, summary TEXT NOT NULL DEFAULT '', " +
                "summarized_upto INTEGER NOT NULL DEFAULT 0)"
        )
        createContactsTable(db)
    }

    private fun createContactsTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS contacts (contact TEXT PRIMARY KEY, display TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createContactsTable(db)
    }

    fun key(contactName: String): String = JarvisMemory.normalize(contactName)

    /** Ajoute un message. Renvoie false s'il était déjà connu. */
    @Synchronized
    fun add(contactName: String, app: String, fromMe: Boolean, text: String, ts: Long): Boolean {
        val k = key(contactName)
        val t = text.trim()
        if (k.isBlank() || t.isBlank()) return false
        val db = writableDatabase
        if (fromMe) {
            // La copie de notre propre réponse, revue dans la notification, a un horodatage un peu différent.
            db.rawQuery(
                "SELECT 1 FROM messages WHERE contact=? AND from_me=1 AND text=? AND ABS(ts-?)<120000 LIMIT 1",
                arrayOf(k, t, ts.toString())
            ).use { if (it.moveToFirst()) return false }
        }
        val values = ContentValues().apply {
            put("contact", k); put("app", app); put("from_me", if (fromMe) 1 else 0); put("text", t); put("ts", ts)
        }
        val inserted = db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted && contactName != k) {
            db.insertWithOnConflict(
                "contacts", null,
                ContentValues().apply { put("contact", k); put("display", contactName.trim()) },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        return inserted
    }

    fun ingest(contactName: String, app: String, msgs: List<Msg>) {
        msgs.forEach { add(contactName, app, it.fromMe, it.text, it.ts) }
    }

    /** Les [n] derniers messages, du plus ancien au plus récent. */
    @Synchronized
    fun recent(contactName: String, n: Int): List<Msg> {
        val out = mutableListOf<Msg>()
        readableDatabase.rawQuery(
            "SELECT from_me, text, ts, app FROM messages WHERE contact=? ORDER BY ts DESC LIMIT ?",
            arrayOf(key(contactName), n.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Msg(c.getInt(0) == 1, c.getString(1), c.getLong(2), c.getString(3)))
        }
        return out.reversed()
    }

    @Synchronized
    fun summary(contactName: String): String =
        readableDatabase.rawQuery("SELECT summary FROM notes WHERE contact=?", arrayOf(key(contactName)))
            .use { if (it.moveToFirst()) it.getString(0) else "" }

    @Synchronized
    fun summarizedUpTo(contactName: String): Long =
        readableDatabase.rawQuery("SELECT summarized_upto FROM notes WHERE contact=?", arrayOf(key(contactName)))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Ajoute un détail à retenir (ex : « a un examen vendredi »). */
    @Synchronized
    fun appendNote(contactName: String, line: String) {
        val clean = line.trim().take(160)
        if (clean.isBlank()) return
        val current = summary(contactName)
        if (current.contains(clean)) return
        saveNotes(contactName, (if (current.isBlank()) "" else "$current\n") + "• $clean", summarizedUpTo(contactName))
    }

    @Synchronized
    fun saveNotes(contactName: String, summary: String, upTo: Long) {
        val values = ContentValues().apply {
            put("contact", key(contactName)); put("summary", summary.trim()); put("summarized_upto", upTo)
        }
        writableDatabase.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Messages plus anciens que les [keep] derniers et pas encore résumés. */
    @Synchronized
    fun unsummarizedOlder(contactName: String, keep: Int): List<Msg> {
        val k = key(contactName)
        val out = mutableListOf<Msg>()
        readableDatabase.rawQuery(
            "SELECT from_me, text, ts, app FROM messages WHERE contact=? AND ts>? AND id NOT IN " +
                "(SELECT id FROM messages WHERE contact=? ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC",
            arrayOf(k, summarizedUpTo(contactName).toString(), k, keep.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Msg(c.getInt(0) == 1, c.getString(1), c.getLong(2), c.getString(3)))
        }
        return out
    }

    /** Une fois résumés, les vieux messages bruts sont supprimés (on garde toujours les plus récents). */
    @Synchronized
    fun pruneUpTo(contactName: String, ts: Long) {
        writableDatabase.delete("messages", "contact=? AND ts<=?", arrayOf(key(contactName), ts.toString()))
    }

    /** Toutes les conversations connues, la plus récente d'abord. */
    @Synchronized
    fun contactsList(): List<ContactInfo> {
        val out = mutableListOf<ContactInfo>()
        readableDatabase.rawQuery(
            "SELECT m.contact, COALESCE(c.display, m.contact), COUNT(*), MAX(m.ts), COALESCE(n.summary, '') " +
                "FROM messages m LEFT JOIN contacts c ON c.contact = m.contact LEFT JOIN notes n ON n.contact = m.contact " +
                "GROUP BY m.contact ORDER BY MAX(m.ts) DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(ContactInfo(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getString(4)))
        }
        return out
    }

    /** Nom affiché d'un contact (tel qu'il apparaît dans WhatsApp / Messenger). */
    @Synchronized
    fun displayName(contactName: String): String =
        readableDatabase.rawQuery("SELECT display FROM contacts WHERE contact=?", arrayOf(key(contactName)))
            .use { if (it.moveToFirst()) it.getString(0) else contactName }

    /** « Oublie la conversation avec X » : efface messages et résumé de ce contact. */
    @Synchronized
    fun clear(contactName: String): Boolean {
        val k = key(contactName)
        val a = writableDatabase.delete("messages", "contact=?", arrayOf(k))
        val b = writableDatabase.delete("notes", "contact=?", arrayOf(k))
        writableDatabase.delete("contacts", "contact=?", arrayOf(k))
        return a + b > 0
    }

    companion object {
        @Volatile private var instance: ConversationStore? = null
        fun get(context: Context): ConversationStore =
            instance ?: synchronized(this) { instance ?: ConversationStore(context).also { instance = it } }
    }
}
