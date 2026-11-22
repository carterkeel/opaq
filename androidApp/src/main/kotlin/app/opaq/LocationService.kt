package app.opaq

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.SystemClock
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.opaq.core.MotionPolicy
import app.opaq.core.model.LocationSample
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that collects location fixes and publishes encrypted updates.
 *
 * While still it uses cheap network location and the phone's low-power significant-motion sensor.
 * When that sensor fires (or network fixes jump), it switches to GPS within seconds, and an update
 * goes out as soon as movement is confirmed instead of waiting out the 5-minute still interval.
 *
 * The persistent notification is required by Android, and doubles as an anti-stalkerware
 * signal: you can always see that Opaq is sharing, and with how many people.
 */
class LocationService : LifecycleService(), LocationListener {
    private val policy = MotionPolicy()
    private lateinit var locationManager: LocationManager
    private var sensorManager: SensorManager? = null
    private var significantMotion: Sensor? = null
    private var highAccuracy: Boolean? = null
    private var lastReminderAt = 0L

    /** Wakes the publish loop early: a fix arrived while an update is due. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The last update reached no relay (offline, or relays still confirming the circle key). */
    private var missed = false

    private var connectivity: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            OpaqApp.from(this@LocationService).engine.networkAvailable()
        }
    }

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            policy.motionHint(nowSeconds())
            diag("Motion sensor: started moving")
            adjustAccuracy()
            armMotionSensor() // one-shot sensor: re-arm for the next time
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)!!
        sensorManager = getSystemService(SensorManager::class.java)
        significantMotion = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        val app = OpaqApp.from(this)
        diag("Sharing service started. ${permissionSummary()}. ${powerSummary()}")
        startInForeground(0)

        lifecycleScope.launch {
            app.engine.state
                .map { s -> Triple(s.sharing, s.circles.isEmpty(), app.sharingWithCount(s)) }
                .distinctUntilChanged()
                .collect { (sharing, noCircles, people) ->
                    if (!sharing || noCircles) {
                        diag(if (noCircles) "Sharing service stopping: no circles" else "Sharing service stopping: sharing paused")
                        stopSelf()
                    } else {
                        startInForeground(people.coerceAtLeast(0))
                    }
                }
        }

        // Joined a circle and tapped Start sharing (#148), someone new arrived, or a circle paused or resumed: send our location now instead of at the next
        // interval (up to 5 minutes away while still).
        lifecycleScope.launch {
            app.engine.state
                .map { s -> s.circles.map { c -> listOf(c.id, c.members.size, c.paused, c.confirmed) } }
                .distinctUntilChanged()
                .collect { policy.publishSoon() }
        }
        // Someone in a circle asked where we are (#149): GPS now, and the fresh fix goes out at once.
        lifecycleScope.launch {
            app.engine.locationRequests.collect {
                policy.requestRefresh(nowSeconds(), always = true)
                adjustAccuracy()
                wake.trySend(Unit)
            }
        }

        armMotionSensor()
        requestUpdates(highAccuracy = true)
        lifecycleScope.launch {
            while (isActive) {
                val now = nowSeconds()
                // Never share without the visible notification: if it was turned off, stop.
                if (!app.canShowSharingNotification()) {
                    app.markSharingBlocked()
                    diag("Sharing service stopping: the sharing notification is turned off")
                    stopSelf()
                    break
                }
                if (app.engine.consentDue() && now - lastReminderAt > 24 * 3600) {
                    lastReminderAt = now
                    app.notifySharingReminder()
                }
                // No fixes for a while means the phone stopped: report it, don't keep saying "Driving".
                policy.refresh(now)
                adjustAccuracy()
                notePower()
                // An update that reached no relay is replaced as soon as one would get through, rather
                // than at the next interval: the relays never saw the missed one.
                if (policy.shouldPublish(now) || (missed && app.engine.canPublishNow())) {
                    val battery = batteryPercent()
                    val status = if (battery != null && battery <= 5) "low battery" else null
                    policy.snapshot(battery, status, now)?.let {
                        missed = !app.engine.publishLocation(it)
                        policy.markPublished(now)
                    }
                }
                // Every 2 s while moving; while still, only when the next update is due (at most 30 s).
                withTimeoutOrNull(policy.nextCheckMillis(nowSeconds())) { wake.receive() }
            }
        }

        connectivity = getSystemService(ConnectivityManager::class.java)
        try {
            connectivity?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: RuntimeException) {
            connectivity = null // too many callbacks registered, or no permission: relays still retry on their own
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_REFRESH) {
            // The locate button: GPS now, and the fix goes out at once if the shared one was off.
            policy.requestRefresh(nowSeconds())
            adjustAccuracy()
            wake.trySend(Unit)
        }
        if (intent?.action == ACTION_STOP) {
            // On the app's scope, not the service's: stopSelf() below cancels the service's scope,
            // which could otherwise drop this before sharing is actually turned off.
            val app = OpaqApp.from(this)
            diag("Sharing paused from the notification")
            app.scope.launch { app.engine.setSharing(false) }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diag("Opaq was swiped away from recent apps")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        diag("Sharing service stopped")
        connectivity?.let { runCatching { it.unregisterNetworkCallback(networkCallback) } }
        locationManager.removeUpdates(this)
        significantMotion?.let { sensorManager?.cancelTriggerSensor(motionListener, it) }
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        policy.add(
            LocationSample(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy else 100f,
                speed = if (location.hasSpeed() && location.provider == LocationManager.GPS_PROVIDER) location.speed else null,
                heading = if (location.hasBearing()) location.bearing else null,
                time = location.time / 1000,
            ),
            receivedAt = nowSeconds(),
            // From the monotonic clock: location.time is GPS time, which can differ from a phone
            // clock that's set wrong.
            ageSeconds = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000,
        )
        adjustAccuracy()
        // Publish with this fresh fix instead of waiting for the next tick (and an older fix).
        if (policy.shouldPublish(nowSeconds())) wake.trySend(Unit)
    }

    @Deprecated("Required on older Android versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = diag("Location provider $provider turned on")
    override fun onProviderDisabled(provider: String) = diag("Location provider $provider turned off")

    private fun armMotionSensor() {
        significantMotion?.let { sensorManager?.requestTriggerSensor(motionListener, it) }
    }

    /** GPS while moving (or just woken by the motion sensor); cheap network location while still. */
    private fun adjustAccuracy() {
        val wantHigh = policy.wantsHighAccuracy(nowSeconds())
        if (wantHigh != highAccuracy) requestUpdates(wantHigh)
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(highAccuracy: Boolean) {
        this.highAccuracy = highAccuracy
        try {
            locationManager.removeUpdates(this)
            val providers = locationManager.getProviders(true)
            val hasNetwork = LocationManager.NETWORK_PROVIDER in providers
            val hasGps = LocationManager.GPS_PROVIDER in providers
            if (hasGps && (highAccuracy || !hasNetwork)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, if (highAccuracy) 5_000L else 60_000L, 0f, this, Looper.getMainLooper())
            }
            if (hasNetwork) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, if (highAccuracy) 15_000L else 30_000L, 0f, this, Looper.getMainLooper())
            }
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, this, Looper.getMainLooper())
            diag(
                when {
                    !hasGps && !hasNetwork -> "Location: no provider is on (location turned off in phone settings?)"
                    hasGps && (highAccuracy || !hasNetwork) -> if (highAccuracy) "Location: GPS (moving)" else "Location: GPS only (no network location)"
                    else -> "Location: network (still)"
                },
            )
        } catch (_: SecurityException) {
            diag("Sharing service stopping: location permission was removed. ${permissionSummary()}")
            stopSelf()
        }
    }

    /** Adds an event to the diagnostic log (#154): events and reasons only, never a location. */
    private fun diag(event: String) = OpaqApp.from(this).engine.diagnostic(event)

    private fun permissionSummary(): String {
        fun granted(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val fine = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val access = when {
            fine -> "precise"
            coarse -> "approximate only"
            else -> "none"
        }
        return "Location permission: $access, ${if (background) "all the time" else "only while in use"}"
    }

    private fun powerSummary(): String {
        val power = getSystemService(android.os.PowerManager::class.java)
        val optimised = power?.isIgnoringBatteryOptimizations(packageName) != true
        return "Battery saver for Opaq ${if (optimised) "on" else "off"}, power saving mode ${if (power?.isPowerSaveMode == true) "on" else "off"}" +
            (batteryPercent()?.let { ", battery ${band(it)}" } ?: "")
    }

    private var loggedBand: Int? = null
    private var loggedPowerSave: Boolean? = null

    /** Battery in 20% bands, and power saving mode, logged when they change. */
    private fun notePower() {
        val band = batteryPercent()?.let { it / 20 }
        val powerSave = getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode
        if (band != null && loggedBand != null && band != loggedBand) diag("Battery ${band(band * 20)}")
        if (powerSave != null && loggedPowerSave != null && powerSave != loggedPowerSave) diag("Power saving mode ${if (powerSave) "on" else "off"}")
        if (band != null) loggedBand = band
        if (powerSave != null) loggedPowerSave = powerSave
    }

    private fun band(percent: Int): String = (percent / 20 * 20).coerceAtMost(80).let { "$it–${it + 20}%" }

    private fun batteryPercent(): Int? =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun startInForeground(people: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(people), type)
        } catch (e: Throwable) {
            diag("Sharing service couldn't start in the foreground (${e::class.simpleName})")
            stopSelf()
        }
    }

    private fun buildNotification(people: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (people) {
            0 -> "Sharing is on. Nobody sees your location yet."
            1 -> "Sharing your location with 1 person"
            else -> "Sharing your location with $people people"
        }
        return NotificationCompat.Builder(this, OpaqApp.CHANNEL_SHARING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Opaq")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.opaq.STOP"
        const val ACTION_REFRESH = "app.opaq.REFRESH"

    }
}
