package com.jarvis.app

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.app.ai.ApiKeyStore

/** Réglages « Importer sur GitHub » : jeton, dépôt, branche, dossier, message, puis choix des fichiers. */
object GithubDialog {
    fun show(a: AppCompatActivity, pickFiles: () -> Unit) {
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(a).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val token = field("Jeton GitHub (ghp_… ou github_pat_…)", ApiKeyStore.getKey(a, "github"), secret = true)
        val repo = field("Dépôt (proprio/nom)", GithubSync.repo(a))
        val branch = field("Branche", GithubSync.branch(a))
        val folder = field("Dossier dans le dépôt (vide = racine)", GithubSync.folder(a))
        val msg = field("Message de commit", GithubSync.message(a))
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            listOf(token, repo, branch, folder, msg).forEach { addView(it) }
        }
        AlertDialog.Builder(a)
            .setTitle("Importer sur GitHub")
            .setMessage("Choisis des fichiers (ou un .zip de projet) : ils seront créés ou mis à jour dans le dépôt.")
            .setView(box)
            .setPositiveButton("Choisir les fichiers") { _, _ ->
                ApiKeyStore.setKey(a, "github", token.text.toString())
                GithubSync.save(a, repo.text.toString(), branch.text.toString(), folder.text.toString(), msg.text.toString())
                pickFiles()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
