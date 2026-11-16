package app.quietmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.provider.Settings
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import app.quietmap.ui.QuietmapScreens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Invite links opened while the app is running arrive here. */
    private val incomingLink = MutableStateFlow<String?>(null)

    private var onPhoto: ((String?) -> Unit)? = null
    private var onScan: ((String?) -> Unit)? = null
    private var onSaveFile: ((Boolean) -> Unit)? = null
    private var pendingSaveText: String? = null
    private var onOpenFile: ((String?) -> Unit)? = null

    /** Saving a backup: the system "save as" screen, no storage permission needed. */
    private val fileSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val callback = onSaveFile ?: return@registerForActivityResult
        val text = pendingSaveText
        onSaveFile = null
        pendingSaveText = null
        if (uri == null || text == null) return@registerForActivityResult callback(false)
        val ok = runCatching { contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) } != null }.getOrDefault(false)
        if (!ok) Toast.makeText(this, "Couldn't save the backup file", Toast.LENGTH_LONG).show()
        callback(ok)
    }

    /** Opening a backup. */
    private val fileOpener = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = onOpenFile ?: return@registerForActivityResult
        onOpenFile = null
        if (uri == null) return@registerForActivityResult callback(null)
        // Backups are a few tens of KB; refuse anything huge rather than reading it all.
        // One byte past the limit is read to tell "exactly at the limit" from "too big".
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input -> input.readNBytesCompat(MAX_BACKUP_BYTES + 1) }
        }.getOrNull()
        val text = when {
            bytes == null -> {
                Toast.makeText(this, "Couldn't open that file", Toast.LENGTH_LONG).show()
                null
            }
            bytes.size > MAX_BACKUP_BYTES -> {
                Toast.makeText(this, "That file is too big to be a Quietmap backup", Toast.LENGTH_LONG).show()
                null
            }
            else -> bytes.decodeToString()
        }
        callback(text)
    }

    /** "Allow all the time": on Android 11+ this opens the app's location permission page directly. */
    private val backgroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) QuietmapApp.from(this).ensureSharingService()
    }

    /** System photo picker: no storage permission needed. */
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val callback = onPhoto ?: return@registerForActivityResult
        onPhoto = null
        if (uri == null) return@registerForActivityResult callback(null)
        val app = QuietmapApp.from(this)
        app.scope.launch {
            val photo = PhotoProcessor.process(this@MainActivity, uri)
            withContext(Dispatchers.Main) {
                if (photo == null) Toast.makeText(this@MainActivity, "Couldn't use that photo", Toast.LENGTH_LONG).show()
                callback(photo)
            }
        }
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val callback = onScan ?: return@registerForActivityResult
        onScan = null
        callback(result.contents)
    }

    val platform: AndroidPlatform by lazy {
        AndroidPlatform(
            context = this,
            photoPicker = { cb ->
                onPhoto = cb
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            qrScanner = { cb ->
                onScan = cb
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan a Quietmap invite")
                        .setBeepEnabled(false)
                        .setOrientationLocked(true),
                )
            },
            fileSaver = { name, text, cb ->
                onSaveFile = cb
                pendingSaveText = text
                fileSaver.launch(name)
            },
            fileOpener = { cb ->
                onOpenFile = cb
                fileOpener.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            },
            requestBackgroundLocation = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // "Hide in recent apps": FLAG_SECURE blanks the app switcher thumbnail and blocks screenshots.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                QuietmapApp.from(this@MainActivity).engine.state.map { it.hideInRecents }.distinctUntilChanged().collect { hide ->
                    if (hide) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        // Only on a fresh start: when Android recreates the activity (dark mode, font size) the
        // launch intent is the same one, and its invite link was already handled.
        if (savedInstanceState == null) incomingLink.value = intent?.dataString
        setContent { Root(incomingLink, platform) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { incomingLink.value = it }
    }
}

/** Android host for the shared screens: permissions, settings rows and invite links. */
@Composable
private fun Root(incomingLink: MutableStateFlow<String?>, platform: AndroidPlatform) {
    val context = LocalContext.current
    val app = QuietmapApp.from(context)
    val link by incomingLink.collectAsStateWithLifecycle()

    // Permission state, rechecked whenever the app comes back to the foreground.
    var hasLocation by remember { mutableStateOf(false) }
    var hasBackground by remember { mutableStateOf(false) }
    var resumeCount by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasLocation = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            hasBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            resumeCount++
            app.ensureSharingService()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        app.ensureSharingService()
    }
    val requestLocation = {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }
    // Battery optimisation can change in system settings, so recheck it on every resume too.
    val sharingSettings = remember(hasBackground, resumeCount) { platform.sharingSettings(hasBackground) }
    val blocked by app.sharingBlocked.collectAsStateWithLifecycle()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { app.ensureSharingService() }
    val fixNotifications = {
        val activity = context as? ComponentActivity
        val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity != null &&
            !granted(context, Manifest.permission.POST_NOTIFICATIONS) &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
            resumeCount <= 1
        if (canAsk) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    QuietmapScreens(
        engine = app.engine,
        scope = app.scope,
        platform = platform,
        hasLocationPermission = hasLocation,
        onRequestLocation = requestLocation,
        sharingSettings = sharingSettings,
        incomingLink = link,
        onLinkHandled = { incomingLink.value = null },
        sharingBlocked = if (blocked) "Sharing is paused because Quietmap's notification is turned off. It has to be visible so you always know your location is being shared." else null,
        onFixSharing = fixNotifications,
    )
}

/** Largest backup file accepted. Real backups are a few tens of KB. */
private const val MAX_BACKUP_BYTES = 512 * 1024

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
