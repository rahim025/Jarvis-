package com.jarvis.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.jarvis.app.ai.EbookClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.Normalizer
import kotlin.concurrent.thread

/**
 * Écran « Atelier e-book » (style JARVIS) : lance la création sur le backend, interroge sa progression
 * toutes les ~1 s et la pousse dans la page assets/ebook_hud.html (réacteur, étapes, journal, couverture).
 * À la fin, l'EPUB est téléchargé dans le stockage de l'app : on peut l'ouvrir, le partager, ou enregistrer la couverture.
 */
class EbookActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TOPIC = "ebook_topic"
        private const val EXTRA_AUTHOR = "ebook_author"
        private const val EXTRA_CHAPTERS = "ebook_chapters"
        private const val EXTRA_STYLE = "ebook_style"
        private const val EXTRA_PHOTO = "ebook_photo"
        private const val PAGE = "file:///android_asset/ebook_hud.html"

        fun intent(context: Context, topic: String, author: String, chapters: String, style: String, photo: String): Intent =
            Intent(context, EbookActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TOPIC, topic)
                .putExtra(EXTRA_AUTHOR, author)
                .putExtra(EXTRA_CHAPTERS, chapters)
                .putExtra(EXTRA_STYLE, style)
                .putExtra(EXTRA_PHOTO, photo)
    }

    private lateinit var web: WebView

    @Volatile private var stopped = false
    /** Change à chaque (re)lancement : un ancien thread de sondage ne peut plus écrire dans la page. */
    @Volatile private var generation = 0
    @Volatile private var jobId: String? = null
    @Volatile private var title: String = ""
    private var worker: Thread? = null
    private var epubFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            setBackgroundColor(0xFF050810.toInt())
            settings.javaScriptEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "Native")
        }
        setContentView(web)
        web.loadUrl(PAGE)
    }

    override fun onDestroy() {
        stopped = true
        worker?.interrupt()
        web.removeJavascriptInterface("Native")
        web.destroy()
        super.onDestroy()
    }

    // ───────────────────────────── création + sondage ─────────────────────────────

    private fun js(code: String) {
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(code, null) }
    }

    private fun live(g: Int) = !stopped && g == generation
    private fun jsIf(g: Int, code: String) { if (live(g)) js(code) }

    private fun begin() {
        stopped = false
        val g = ++generation
        epubFile = null
        val topic = intent.getStringExtra(EXTRA_TOPIC).orEmpty()
        js("HUD.init(${JSONObject().put("topic", topic)})")
        worker = thread(name = "ebook-poll") { runJob(topic, g) }
    }

    private fun runJob(topic: String, g: Int) {
        try {
            val id = EbookClient.start(
                this, topic,
                intent.getStringExtra(EXTRA_AUTHOR).orEmpty(),
                intent.getStringExtra(EXTRA_CHAPTERS).orEmpty(),
                intent.getStringExtra(EXTRA_STYLE).orEmpty(),
                intent.getStringExtra(EXTRA_PHOTO).orEmpty()
            )
            if (!live(g)) return
            jobId = id
            var since = 0
            var fails = 0
            var coverSent = false
            while (live(g)) {
                val st = try {
                    EbookClient.status(id, since).also {
                        if (fails > 0) { fails = 0; jsIf(g, "HUD.setLink(true)") }
                    }
                } catch (e: IOException) {
                    // Coupure réseau / service Render qui se réveille : on retente sans abandonner.
                    if (++fails > 40) throw EbookClient.EbookException("Connexion perdue avec le serveur. Relance la création.")
                    jsIf(g, "HUD.setLink(false)")
                    Thread.sleep(3000)
                    continue
                }
                since = st.optInt("logSeq", since)
                title = st.optString("title", title)
                jsIf(g, "HUD.update($st)")

                if (!coverSent && st.optJSONObject("ready")?.optBoolean("cover") == true) {
                    coverSent = runCatching {
                        val svg = EbookClient.download(id, "cover.svg")
                        jsIf(g, "HUD.setCover('data:image/svg+xml;base64,${Base64.encodeToString(svg, Base64.NO_WRAP)}')")
                    }.isSuccess
                }
                when (st.optString("status")) {
                    "done" -> {
                        val bytes = EbookClient.download(id, "book.epub")
                        if (live(g)) saveEpub(bytes)
                        return
                    }
                    "error", "cancelled" -> return
                }
                Thread.sleep(1200)
            }
        } catch (e: InterruptedException) {
            // écran fermé : le travail continue côté serveur, rien à faire
        } catch (e: EbookClient.EbookException) {
            jsIf(g, "HUD.fail(${JSONObject.quote(e.message ?: "Erreur inconnue.")})")
        } catch (e: Exception) {
            jsIf(g, "HUD.fail(${JSONObject.quote("Erreur inattendue : ${e.message ?: e.javaClass.simpleName}")})")
        }
    }

    // ───────────────────────────── fichiers, ouverture, partage ─────────────────────────────

    private fun slug(): String {
        val base = Normalizer.normalize(title.ifBlank { "ebook" }, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
        return base.ifBlank { "ebook" }
    }

    private fun dir(name: String): File =
        File(getExternalFilesDir(null) ?: filesDir, name).apply { mkdirs() }

    private fun saveEpub(bytes: ByteArray) {
        val f = File(dir("ebooks"), "${slug()}.epub")
        f.writeBytes(bytes)
        epubFile = f
    }

    private fun uriFor(f: File) = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun openBook() {
        val f = epubFile ?: return toast("L'e-book n'est pas encore prêt.")
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(f), "application/epub+zip")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: Exception) {
            toast("Aucune application de lecture EPUB. Je te propose de le partager.")
            shareBook()
        }
    }

    private fun shareBook() {
        val f = epubFile ?: return toast("L'e-book n'est pas encore prêt.")
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/epub+zip")
            .putExtra(Intent.EXTRA_STREAM, uriFor(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Partager l'e-book"))
    }

    /** Couverture PNG (dessinée par la page) : galerie sur Android 10+, sinon feuille de partage. */
    private fun saveCover(pngBase64: String) {
        val bytes = runCatching { Base64.decode(pngBase64, Base64.DEFAULT) }.getOrNull()
            ?: return toast("Couverture illisible.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${slug()}-couverture.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            val ok = uri != null && runCatching {
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            }.isSuccess
            if (ok) js("HUD.toast('Couverture enregistrée dans Images/Jarvis')") else toast("Impossible d'enregistrer la couverture.")
        } else {
            val f = File(dir("covers"), "${slug()}-couverture.png").also { it.writeBytes(bytes) }
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uriFor(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Enregistrer la couverture"))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Pont appelé par la page (threads du WebView : on repasse sur le thread principal si besoin). */
    private inner class Bridge {
        @JavascriptInterface fun ready() = runOnUiThread { if (worker == null) begin() }

        @JavascriptInterface fun cancel() {
            val id = jobId ?: return
            thread { runCatching { EbookClient.cancel(id) } }
        }

        @JavascriptInterface fun retry() = runOnUiThread {
            generation++
            worker?.interrupt()
            worker = null
            jobId = null
            web.loadUrl(PAGE) // la page se recharge, rappelle ready() et relance une création
        }

        @JavascriptInterface fun close() = runOnUiThread { finish() }
        @JavascriptInterface fun openBook() = runOnUiThread { this@EbookActivity.openBook() }
        @JavascriptInterface fun shareBook() = runOnUiThread { this@EbookActivity.shareBook() }
        @JavascriptInterface fun saveCover(pngBase64: String) = runOnUiThread { this@EbookActivity.saveCover(pngBase64) }
    }
}
