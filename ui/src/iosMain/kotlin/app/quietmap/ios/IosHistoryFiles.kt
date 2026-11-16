package app.quietmap.ios

import app.quietmap.core.HistoryFiles
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL

/**
 * Location history files in Application Support, excluded from iCloud and device backups. Their
 * contents are encrypted by `LocationHistory` with a key kept in the Keychain-protected state.
 * Files are small (one person, one day), so an append rewrites the file.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosHistoryFiles : HistoryFiles {
    private val lock = NSLock()
    private val dir: NSURL = run {
        val fm = NSFileManager.defaultManager
        val support = fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL
        val dir = support.URLByAppendingPathComponent("Quietmap/history", isDirectory = true)!!
        fm.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        dir.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        dir
    }

    private fun url(name: String): NSURL {
        require(name.matches(Regex("[a-z0-9-]+"))) { "bad history file name" }
        return dir.URLByAppendingPathComponent(name)!!
    }

    private fun readText(name: String): String =
        NSString.stringWithContentsOfURL(url(name), encoding = NSUTF8StringEncoding, error = null) ?: ""

    override fun list(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir.path!!, error = null)?.map { it as String }.orEmpty()

    override fun append(name: String, line: String) {
        lock.lock()
        try {
            val text = readText(name) + line + "\n"
            NSString.create(string = text).writeToURL(url(name), atomically = true, encoding = NSUTF8StringEncoding, error = null)
        } finally {
            lock.unlock()
        }
    }

    override fun read(name: String): List<String> = readText(name).lines().filter { it.isNotBlank() }

    override fun delete(name: String) {
        NSFileManager.defaultManager.removeItemAtURL(url(name), error = null)
    }
}
