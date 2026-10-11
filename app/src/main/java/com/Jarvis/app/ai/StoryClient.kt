package com.jarvis.app.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import com.jarvis.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Parle aux routes /story/… du backend (scénario + images par case). Bloquant : hors thread principal. */
object StoryClient {

    class StoryException(message: String) : Exception(message)

    private val BASE_URL = BuildConfig.BACKEND_URL.trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url("$BASE_URL$path")
        if (BuildConfig.APP_SHARED_SECRET.isNotBlank()) b.addHeader("x-app-secret", BuildConfig.APP_SHARED_SECRET)
        return b
    }

    private fun errorOf(code: Int, raw: String?): StoryException {
        val msg = runCatching { JSONObject(raw ?: "").optString("error") }.getOrNull().orEmpty()
        return StoryException(msg.ifBlank { "Erreur du serveur ($code)." })
    }

    fun start(ctx: Context, topic: String, pageName: String, panels: Int): String {
        val body = JSONObject(ApiKeyStore.requestExtras(ctx).toString())
            .put("topic", topic)
            .put("pageName", pageName)
            .put("panels", panels)
        val req = request("/story/start")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw errorOf(r.code, raw)
            return JSONObject(raw ?: "{}").optString("id").ifBlank { throw StoryException("Réponse inattendue du serveur.") }
        }
    }

    fun status(id: String): JSONObject {
        http.newCall(request("/story/status/$id").get().build()).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw errorOf(r.code, raw)
            return JSONObject(raw ?: "{}")
        }
    }

    fun panel(id: String, n: Int): ByteArray {
        http.newCall(request("/story/$id/panel/$n").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw errorOf(r.code, r.body?.string())
            return r.body?.bytes() ?: throw StoryException("Case vide.")
        }
    }
}

/** Lance une BD en cases, attend le scénario et les images, puis compose chaque case en PNG (bulle + nom de page). */
object StoryLauncher {

    fun start(context: Context, action: JarvisAction.CreateStory): String {
        val topic = action.topic.trim()
        if (topic.isBlank()) return "Quel est le sujet de l'histoire ?"
        val app = context.applicationContext
        val page = action.pageName.trim().ifBlank { "Jarvis" }
        val panels = action.panels.trim().toIntOrNull()?.coerceIn(2, 8) ?: 4
        thread(name = "story-job") { runJob(app, topic, page, panels) }
        return "C'est parti, je dessine l'histoire en $panels cases. Je te préviens quand les images sont prêtes."
    }

    private fun runJob(ctx: Context, topic: String, page: String, panels: Int) {
        val handler = Handler(Looper.getMainLooper())
        fun say(msg: String) = handler.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        try {
            val id = StoryClient.start(ctx, topic, page, panels)
            var fails = 0
            var done: JSONObject? = null
            while (done == null) {
                Thread.sleep(4000)
                val cur = try {
                    StoryClient.status(id).also { fails = 0 }
                } catch (e: IOException) {
                    if (++fails > 40) throw StoryClient.StoryException("Connexion perdue avec le serveur. Relance la demande.")
                    continue
                }
                when (cur.optString("status")) {
                    "done" -> done = cur
                    "error" -> throw StoryClient.StoryException(cur.optString("error", "Erreur inconnue."))
                }
            }
            val st = done
            val arr = st.optJSONArray("panels") ?: throw StoryClient.StoryException("Scénario vide.")
            val folder = slug(st.optString("title", topic))
            var saved = 0
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val bg = if (p.optBoolean("hasImage")) {
                    runCatching { StoryClient.panel(id, i + 1) }.getOrNull()
                        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } else null
                val bmp = render(bg, p.optString("text"), p.optString("bubble") != "bottom", page)
                savePng(ctx, bmp, folder, "case-${i + 1}.png")
                saved++
            }
            say("Histoire prête : $saved cases dans Images/Jarvis/Stories/$folder")
        } catch (e: Exception) {
            say(e.message ?: "Erreur pendant la création de l'histoire.")
        }
    }

    /** Composition d'une case 1024×1024 : image de fond, bulle de dialogue, bandeau de la page. */
    private fun render(bg: Bitmap?, text: String, top: Boolean, page: String): Bitmap {
        val size = 1024
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(20, 20, 28))
        if (bg != null) c.drawBitmap(bg, null, Rect(0, 0, size, size), null)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
        }
        val maxW = 760
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val w = maxW + 60f
        val h = layout.height + 50f
        val left = (size - w) / 2f
        val topY = if (top) 40f else size - h - 110f
        val box = RectF(left, topY, left + w, topY + h)
        c.drawRoundRect(box, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawRoundRect(box, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
        })
        c.save()
        c.translate(left + 30f, topY + 25f)
        layout.draw(c)
        c.restore()

        val wm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
        }
        val label = " $page "
        val lw = wm.measureText(label)
        c.drawRoundRect(
            RectF(30f, size - 100f, 30f + lw + 20f, size - 40f), 30f, 30f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 30, 140) }
        )
        c.drawText(label, 40f, size - 58f, wm)
        return out
    }

    private fun savePng(ctx: Context, bmp: Bitmap, folder: String, name: String) {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val bytes = baos.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis/Stories/$folder")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw StoryClient.StoryException("Impossible d'enregistrer la case.")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        } else {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "stories/$folder").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
        }
    }

    private fun slug(s: String): String {
        val base = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
        return base.ifBlank { "histoire" }
    }
}
