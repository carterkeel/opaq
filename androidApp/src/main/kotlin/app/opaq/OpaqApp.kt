package app.opaq

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.opaq.core.Engine
import app.opaq.core.Notice
import app.opaq.core.model.AppState
import app.opaq.core.nostr.RelayUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class OpaqApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: Engine by lazy {
        Engine(
            store = SecureStore(this),
            scope = scope,
            now = { System.currentTimeMillis() / 1000 },
            log = { Log.d("Opaq", it) },
            deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            elapsed = { SystemClock.elapsedRealtime() / 1000 },
            historyFiles = FileHistory(this),
            testRelays = testRelays(),
        ).also { it.start() }
    }

    private val _sharingBlocked = MutableStateFlow(false)

    /**
     * True while sharing is on but Opaq's sharing notification can't be shown (notifications or
     * the "Location sharing" channel turned off). Sharing doesn't run then: nobody may be tracked
     * without a visible sign on their phone.
     */
    val sharingBlocked: StateFlow<Boolean> = _sharingBlocked.asStateFlow()

    /**
     * Debug builds only: the relay in `files/test-relay` (one address per line) instead of the saved
     * relays, for testing against a relay on this machine (tools/test-relay.sh):
     *   adb shell run-as me.opaq.app.debug sh -c 'echo ws://10.0.2.2:7777 > files/test-relay'
     * Delete the file (or leave it empty) and restart the app to go back to the saved relays.
     */
    private fun testRelays(): List<String>? {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null
        val lines = runCatching { java.io.File(filesDir, "test-relay").readLines() }.getOrNull() ?: return null
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    override fun onCreate() {
        super.onCreate()
        // Debug builds may use a relay on this machine over plain ws://; release builds never do.
        RelayUrls.allowLocalPlaintext = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SHARING, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Opaq shares your location with your circles. Sharing stops if this is turned off."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CIRCLES, "Circle changes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When someone joins or leaves a circle, and monthly reminders that you're sharing"
            },
        )
        engine // start relays
        // Start sharing the moment you're let into a circle (or create one), not the next time
        // the app comes to the front.
        scope.launch {
            engine.state.map { it.sharing && it.circles.isNotEmpty() }.distinctUntilChanged().collect { if (it) ensureSharingService() }
        }
        // Tell people when someone joins or leaves their circles, even with the app closed.
        scope.launch { engine.notices.collect { notify(it) } }
    }

    /**
     * Whether the persistent "Sharing your location" notification can actually appear: notifications
     * allowed for the app (and the Android 13+ permission granted) and the sharing channel not
     * turned off. Checked before sharing starts and on every location tick.
     */
    fun canShowSharingNotification(): Boolean {
        val nmc = NotificationManagerCompat.from(this)
        if (!nmc.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val channel = getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_SHARING) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Starts the sharing service if sharing is on, location access was granted and the notification can show. */
    /** The locate button: asks the running sharing service for a fresh fix (see MotionPolicy.requestRefresh). */
    fun refreshMyLocation() {
        val s = engine.state.value
        if (!s.sharing || s.circles.isEmpty() || !canShowSharingNotification()) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java).setAction(LocationService.ACTION_REFRESH))
        } catch (t: Throwable) {
            Log.w("Opaq", "could not reach sharing service: ${t.message}")
        }
    }

    fun ensureSharingService() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val s = engine.state.value
        val wanted = granted && s.sharing && s.circles.isNotEmpty()
        val visible = canShowSharingNotification()
        _sharingBlocked.value = s.sharing && s.circles.isNotEmpty() && !visible
        if (wanted && visible) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
            } catch (t: Throwable) {
                Log.w("Opaq", "could not start sharing service: ${t.message}")
            }
        } else if (!visible) {
            stopService(Intent(this, LocationService::class.java))
        }
    }

    /** Called by the service when the notification disappears mid-share. */
    fun markSharingBlocked() {
        _sharingBlocked.value = true
    }

    /** Monthly: "You're still sharing your location". Opening the app shows who sees it. */
    fun notifySharingReminder() {
        val people = sharingWithCount(engine.state.value)
        notify(Notice("You're still sharing your location", "With $people ${if (people == 1) "person" else "people"}. Tap to check who."))
    }

    /**
     * How many other people see this phone's location: members of circles the user has confirmed
     * ("Start sharing"). Unconfirmed circles get nothing, so their members aren't counted.
     */
    fun sharingWithCount(s: AppState): Int {
        val me = engine.myPubkey
        return s.circles.filter { it.confirmed }
            .flatMap { c -> c.members.map { it.pubkey } }
            .toSet()
            .count { it != me }
    }

    private fun notify(n: Notice) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_CIRCLES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(n.title.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission withdrawn; the in-app history still has it.
        }
    }

    companion object {
        const val CHANNEL_SHARING = "sharing"
        const val CHANNEL_CIRCLES = "circles"
        fun from(context: Context): OpaqApp = context.applicationContext as OpaqApp
    }
}
