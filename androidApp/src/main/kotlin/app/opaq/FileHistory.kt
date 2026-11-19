package app.opaq

import android.content.Context
import app.opaq.core.HistoryFiles
import java.io.File

/**
 * Location history files in the app's no-backup directory: private to the app, never in a device
 * or cloud backup. Their contents are encrypted by `LocationHistory` with a key kept in the
 * Keystore-protected state, so the files alone reveal nothing.
 */
class FileHistory(context: Context) : HistoryFiles {
    private val dir = File(context.noBackupFilesDir, "history").apply { mkdirs() }

    private fun file(name: String): File {
        require(name.matches(Regex("[a-z0-9-]+"))) { "bad history file name" }
        return File(dir, name)
    }

    override fun list(): List<String> = dir.list()?.toList().orEmpty()

    override fun append(name: String, line: String) = file(name).appendText(line + "\n")

    override fun read(name: String): List<String> = file(name).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    override fun delete(name: String) {
        file(name).delete()
    }
}
