package com.jarvis.app.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mémoire d'éléphant de Jarvis, stockée sur le téléphone (SQLite, aucune limite pratique).
 *
 *  - FAITS : ce que Jarvis doit retenir pour toujours (« mon anniversaire est le… »).
 *  - JOURNAL : chaque échange (toi + Jarvis) est conservé à vie, avec la date.
 *  - RAPPEL : à chaque question, Jarvis retrouve les vieux échanges qui parlent de la même
 *    chose (recherche par mots-clés) et les glisse dans son contexte.
 *
 * Tout reste sur le téléphone ; seul le texte utile à la question en cours part vers le backend.
 */
class JarvisMemory private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jarvis_memory.db", null, 1) {

    data class Turn(val ts: Long, val user: String, val jarvis: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("date", formatDate(ts))
            .put("user", user)
            .put("jarvis", jarvis)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE facts (k TEXT PRIMARY KEY, v TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE turns (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "user TEXT NOT NULL, jarvis TEXT NOT NULL, norm TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ── Faits ────────────────────────────────────────────────────────────────

    @Synchronized
    fun saveFact(key: String, value: String) {
        val values = ContentValues().apply {
            put("k", key.trim().lowercase(Locale.FRENCH))
            put("v", value.trim())
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("facts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun deleteFact(key: String): Boolean {
        val k = key.trim().lowercase(Locale.FRENCH)
        var n = writableDatabase.delete("facts", "k = ?", arrayOf(k))
        if (n == 0) n = writableDatabase.delete("facts", "k LIKE ?", arrayOf("%$k%"))
        return n > 0
    }

    @Synchronized
    fun allFacts(): List<Triple<String, String, Long>> {
        val out = mutableListOf<Triple<String, String, Long>>()
        readableDatabase.rawQuery("SELECT k, v, ts FROM facts ORDER BY ts ASC", null).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    // ── Journal des échanges ─────────────────────────────────────────────────

    @Synchronized
    fun addTurn(user: String, jarvis: String) {
        if (user.isBlank()) return
        val values = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("user", user.take(2000))
            put("jarvis", jarvis.take(3000))
            put("norm", normalize(user + " " + jarvis))
        }
        writableDatabase.insert("turns", null, values)
    }

    @Synchronized
    fun turnCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM turns", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Les [n] derniers échanges, du plus ancien au plus récent. */
    @Synchronized
    fun recent(n: Int): List<Turn> {
        val out = mutableListOf<Turn>()
        readableDatabase.rawQuery(
            "SELECT ts, user, jarvis FROM turns ORDER BY id DESC LIMIT ?", arrayOf(n.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Turn(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return out.reversed()
    }

    /**
     * Retrouve jusqu'à [limit] vieux échanges qui parlent de la même chose que [query].
     * Les échanges déjà présents dans [exclude] (les plus récents) sont ignorés.
     */
    @Synchronized
    fun recall(query: String, limit: Int, exclude: List<Turn>): List<Turn> {
        val words = keywords(query).take(8)
        if (words.isEmpty()) return emptyList()
        val excludedTs = exclude.map { it.ts }.toSet()

        val where = words.joinToString(" OR ") { "norm LIKE ?" }
        val args = words.map { "%$it%" }.toTypedArray()
        val scored = mutableListOf<Pair<Double, Turn>>()
        readableDatabase.rawQuery(
            "SELECT ts, user, jarvis, norm FROM turns WHERE $where ORDER BY id DESC LIMIT 400", args
        ).use { c ->
            var rank = 0
            while (c.moveToNext()) {
                val ts = c.getLong(0)
                if (ts in excludedTs) { rank++; continue }
                val norm = c.getString(3)
                val hits = words.count { norm.contains(it) }
                // Plus de mots en commun = mieux ; à égalité, le plus récent gagne.
                val score = hits * 10.0 - rank * 0.01
                scored.add(score to Turn(ts, c.getString(1), c.getString(2)))
                rank++
            }
        }
        return scored.sortedByDescending { it.first }.take(limit).map { it.second }.sortedBy { it.ts }
    }

    /** Tout ce qu'il faut envoyer au backend pour cette question. */
    fun buildPayload(query: String): JSONObject {
        val facts = JSONObject()
        allFacts().forEach { (k, v, _) -> facts.put(k, v) }
        val recent = recent(20)
        val relevant = recall(query, 6, recent)
        return JSONObject()
            .put("facts", facts)
            .put("recent", JSONArray(recent.map { it.toJson() }))
            .put("relevant", JSONArray(relevant.map { it.toJson() }))
            .put("now", nowText())
    }

    /** Résumé lisible (pour l'écran « Ce que Jarvis sait de moi »). */
    fun summaryText(): String {
        val facts = allFacts()
        val sb = StringBuilder()
        sb.append("Souvenirs d'échanges : ").append(turnCount()).append("\n")
        sb.append("Faits retenus : ").append(facts.size).append("\n\n")
        if (facts.isEmpty()) {
            sb.append("Aucun fait enregistré pour l'instant.\nDis par exemple : « Jarvis, retiens que mon plat préféré est le riz au gras ».")
        } else {
            facts.forEach { (k, v, ts) -> sb.append("• ").append(k).append(" : ").append(v)
                .append("  (").append(formatDate(ts)).append(")\n") }
        }
        return sb.toString()
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("facts", null, null)
        writableDatabase.delete("turns", null, null)
    }

    companion object {
        @Volatile private var instance: JarvisMemory? = null

        fun get(context: Context): JarvisMemory =
            instance ?: synchronized(this) {
                instance ?: JarvisMemory(context).also { instance = it }
            }

        private val STOP_WORDS = setOf(
            "alors", "avec", "dans", "pour", "mais", "donc", "plus", "tout", "tous", "cette", "cela",
            "comme", "elle", "elles", "nous", "vous", "ils", "etre", "avoir", "fait", "faire", "peux",
            "peut", "veux", "veut", "dire", "dis", "jarvis", "quel", "quelle", "quels", "sont", "suis",
            "est", "que", "qui", "quoi", "pas", "une", "des", "les", "mon", "mes", "ton", "tes", "son",
            "ses", "ces", "aux", "sur", "par", "bien", "tres", "aussi", "encore", "comment", "pourquoi"
        )

        fun normalize(text: String): String =
            text.lowercase(Locale.FRENCH)
                .replace(Regex("[éèêë]"), "e")
                .replace(Regex("[àâä]"), "a")
                .replace(Regex("[îï]"), "i")
                .replace(Regex("[ôö]"), "o")
                .replace(Regex("[ûùü]"), "u")
                .replace("ç", "c")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        fun keywords(text: String): List<String> =
            normalize(text).split(" ").filter { it.length >= 4 && it !in STOP_WORDS }.distinct()

        fun formatDate(ts: Long): String =
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH).format(Date(ts))

        fun nowText(): String =
            SimpleDateFormat("EEEE d MMMM yyyy 'à' HH:mm", Locale.FRENCH).format(Date())
    }
}
