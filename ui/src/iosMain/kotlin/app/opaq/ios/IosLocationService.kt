package app.opaq.ios

import app.opaq.core.Engine
import app.opaq.core.MotionPolicy
import app.opaq.core.model.LocationSample
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLCircularGeographicCondition
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLMonitor
import platform.CoreLocation.CLMonitorConfiguration
import platform.CoreLocation.CLMonitoringState
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.CoreMotion.CMMotionActivity
import platform.CoreMotion.CMMotionActivityConfidenceLow
import platform.CoreMotion.CMMotionActivityManager
import platform.Foundation.NSBundle
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeIntervalSinceNow
import platform.UIKit.UIDevice
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS counterpart of Android's LocationService: collects fixes and publishes encrypted
 * updates on the same moving/still rhythm.
 *
 * iOS suspends apps freely; Opaq stays running only while location updates are on. So
 * updates stay on the whole time sharing is on (GPS while moving, coarse while still) with
 * automatic pausing off. iOS shows the blue location indicator while this runs, the
 * counterpart of Android's persistent notification; people can hide it in Settings
 * ([setShowIndicator]), which leaves no sign of sharing at the top of the screen. Significant-change monitoring makes iOS
 * relaunch the app after a reboot or force-quit once the phone moves about 500 m.
 *
 * Two things stand in for Android's significant-motion sensor, so a walk or drive is noticed
 * straight away rather than after 100 m or more of coarse fixes:
 * - Core Motion's activity reading (walking, running, cycling, in a vehicle), worked out on the
 *   phone from its motion chips. It needs the Motion & Fitness permission; without it, nothing
 *   changes except that starts are noticed later. Nothing from it leaves the phone.
 * - A geofence ([CLMonitor]) around the spot where the phone stopped.
 *   Leaving it switches to GPS, and wakes or relaunches the app if iOS had stopped it.
 *
 * Everything here runs on the main thread, where CoreLocation delivers its callbacks.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationService(private val engine: Engine, private val scope: CoroutineScope, private val notifier: IosNotifier) {
    private val manager = CLLocationManager()
    private val delegate = Delegate()
    private val policy = MotionPolicy()
    private var publishJob: Job? = null
    private var highAccuracy: Boolean? = null
    private var lastReminderAt = 0L
    private val activityManager = CMMotionActivityManager()
    private var activityUpdatesOn = false

    /** Geofence around where the phone stopped; null until iOS hands it over. */
    private var monitor: CLMonitor? = null

    /** Wakes the publish loop early: a fix arrived while an update is due. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The last update reached no relay (offline, or relays still confirming the circle key). */
    private var missed = false

    private val _authorization = MutableStateFlow(manager.authorizationStatus)
    /** Current location permission; recomposes the UI when the user changes it. */
    val authorization: StateFlow<CLAuthorizationStatus> = _authorization.asStateFlow()

    val hasPermission: Boolean get() = authorization.value.let { it == kCLAuthorizationStatusAuthorizedAlways || it == kCLAuthorizationStatusAuthorizedWhenInUse }
    val hasAlways: Boolean get() = authorization.value == kCLAuthorizationStatusAuthorizedAlways

    private val _showIndicator = MutableStateFlow(
        NSUserDefaults.standardUserDefaults.let { it.objectForKey(SHOW_INDICATOR_KEY) == null || it.boolForKey(SHOW_INDICATOR_KEY) },
    )
    /**
     * Whether to ask iOS for the blue location pill (Dynamic Island or status bar) while sharing
     * in the background. On by default. iOS only honours "off" with Always access; with While
     * Using it shows the pill anyway. With it off, nothing at the top of the screen shows that
     * sharing is on while the app is in the background.
     */
    val showIndicator: StateFlow<Boolean> = _showIndicator.asStateFlow()

    fun setShowIndicator(show: Boolean) {
        _showIndicator.value = show
        NSUserDefaults.standardUserDefaults.setBool(show, SHOW_INDICATOR_KEY)
        if (publishJob?.isActive == true && canRunInBackground) manager.showsBackgroundLocationIndicator = show
    }

    init {
        manager.delegate = delegate
        manager.pausesLocationUpdatesAutomatically = false
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        // Asked for on every launch, with the same name, so a geofence exit that relaunched the
        // app is delivered to it.
        CLMonitor.requestMonitorWithConfiguration(
            CLMonitorConfiguration.configWithMonitorName(MONITOR_NAME, dispatch_get_main_queue()) { m, event ->
                if (event?.identifier == STILL_REGION && event.state == CLMonitoringState.CLMonitoringStateUnsatisfied) {
                    m?.removeConditionFromMonitoringWithIdentifier(STILL_REGION)
                    onMovementHint()
                }
            },
        ) { m -> monitor = m }
        // Follow sharing on/off and circles coming and going, like Android's service does.
        scope.launch(Dispatchers.Main) {
            engine.state
                .map { it.sharing && it.circles.isNotEmpty() }
                .distinctUntilChanged()
                .collect { update() }
        }
        // Joined a circle, or someone new arrived: send our location now instead of at the next
        // interval (up to 5 minutes away while still), as Android's service does.
        scope.launch(Dispatchers.Main) {
            engine.state
                .map { s -> s.circles.map { c -> c.id to c.members.size } }
                .distinctUntilChanged()
                .collect { policy.publishSoon() }
        }
    }

    /**
     * Asks for location access. iOS only allows "Always" as a second step after "While Using",
     * so the first call asks for While Using and a later call (once sharing is set up) for Always.
     */
    fun requestPermission() {
        when (authorization.value) {
            kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()
            kCLAuthorizationStatusAuthorizedWhenInUse -> manager.requestAlwaysAuthorization()
            else -> Unit
        }
    }

    /** Starts or stops sharing to match the current state and permission. Call on the main thread. */
    fun update() {
        val s = engine.state.value
        if (hasPermission && s.sharing && s.circles.isNotEmpty()) start() else stop()
    }

    private fun start() {
        if (publishJob?.isActive == true) return
        // Second step of the permission flow: iOS shows the "Always" prompt at most once.
        if (authorization.value == kCLAuthorizationStatusAuthorizedWhenInUse) manager.requestAlwaysAuthorization()
        if (canRunInBackground) {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = showIndicator.value
        }
        setAccuracy(high = true)
        manager.startUpdatingLocation()
        if (CLLocationManager.significantLocationChangeMonitoringAvailable()) manager.startMonitoringSignificantLocationChanges()
        startActivityUpdates()
        // Same loop as Android's LocationService: MotionPolicy decides each tick whether an update
        // is due (steady moving/still rates, plus one extra when someone starts or stops moving).
        publishJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val now = nowSeconds()
                if (engine.consentDue() && now - lastReminderAt > 24 * 3600) {
                    lastReminderAt = now
                    notifier.sharingReminder()
                }
                // No fixes for a while means the phone stopped: report it, don't keep saying "Driving".
                policy.refresh(now)
                adjustAccuracy()
                // An update that reached no relay is replaced as soon as one would get through, rather
                // than at the next interval: the relays never saw the missed one.
                if (policy.shouldPublish(now) || (missed && engine.canPublishNow())) {
                    val battery = batteryPercent()
                    val status = if (battery != null && battery <= 5) "low battery" else null
                    policy.snapshot(battery, status, now)?.let { loc ->
                        policy.markPublished(now)
                        missed = !withContext(Dispatchers.Default) { runCatching { engine.publishLocation(loc) }.getOrDefault(false) }
                    }
                }
                withTimeoutOrNull(TICK_MS) { wake.receive() }
            }
        }
    }

    private fun stop() {
        publishJob?.cancel()
        publishJob = null
        highAccuracy = null
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        if (activityUpdatesOn) activityManager.stopActivityUpdates()
        activityUpdatesOn = false
        monitor?.removeConditionFromMonitoringWithIdentifier(STILL_REGION)
    }

    /**
     * Core Motion says the phone started walking, running, cycling or travelling in a vehicle.
     * The first call shows the Motion & Fitness prompt; if it's refused, no readings arrive.
     */
    private fun startActivityUpdates() {
        if (activityUpdatesOn || !CMMotionActivityManager.isActivityAvailable()) return
        activityUpdatesOn = true
        activityManager.startActivityUpdatesToQueue(NSOperationQueue.mainQueue) { activity: CMMotionActivity? ->
            if (activity != null && activity.confidence != CMMotionActivityConfidenceLow &&
                (activity.walking || activity.running || activity.cycling || activity.automotive)
            ) {
                onMovementHint()
            }
        }
    }

    /** The locate button: GPS now, and the fix goes out at once if the shared one was off. */
    fun refreshNow() {
        if (publishJob?.isActive != true) return
        policy.requestRefresh(nowSeconds())
        adjustAccuracy()
        wake.trySend(Unit)
    }

    /** Started moving (motion chips, or left the geofence): switch to GPS now and let fixes say how fast. */
    private fun onMovementHint() {
        if (publishJob?.isActive != true) return
        val now = nowSeconds()
        policy.motionHint(now)
        adjustAccuracy()
        wake.trySend(Unit)
    }

    /**
     * While still, a geofence around the last fix (at least [STILL_RADIUS] m, more if the fix was
     * rough). Leaving it is the signal to switch back to GPS, even if iOS stopped the app meanwhile.
     */
    private fun placeStillRegion() {
        val m = monitor ?: return
        val fix = policy.latest ?: return
        val radius = maxOf(STILL_RADIUS, 2.0 * fix.accuracy)
        m.addConditionForMonitoring(
            CLCircularGeographicCondition(center = CLLocationCoordinate2DMake(fix.lat, fix.lon), radius = radius),
            STILL_REGION,
            CLMonitoringState.CLMonitoringStateSatisfied,
        )
    }

    private fun adjustAccuracy() = setAccuracy(high = policy.wantsHighAccuracy(nowSeconds()))

    private fun nowSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

    /**
     * GPS while moving; coarse location while still, which still keeps the app running. While
     * still, the 25 m distance filter plays the part of Android's significant-motion sensor: the
     * first fix after moving that far shows movement, and the next tick switches to GPS.
     */
    private fun setAccuracy(high: Boolean) {
        if (high == highAccuracy) return
        highAccuracy = high
        manager.desiredAccuracy = if (high) kCLLocationAccuracyBest else kCLLocationAccuracyHundredMeters
        manager.distanceFilter = if (high) kCLDistanceFilterNone else 25.0
        if (high) monitor?.removeConditionFromMonitoringWithIdentifier(STILL_REGION) else placeStillRegion()
    }

    private fun batteryPercent(): Int? =
        UIDevice.currentDevice.batteryLevel.takeIf { it >= 0f }?.let { (it * 100).toInt().coerceIn(0, 100) }

    private fun onLocation(location: CLLocation) {
        if (location.horizontalAccuracy < 0) return // invalid fix
        val (lat, lon) = location.coordinate.useContents { latitude to longitude }
        policy.add(
            LocationSample(
                lat = lat,
                lon = lon,
                accuracy = location.horizontalAccuracy.toFloat(),
                speed = location.speed.takeIf { it >= 0 }?.toFloat(),
                heading = location.course.takeIf { it >= 0 }?.toFloat(),
                time = location.timestamp.timeIntervalSince1970.toLong(),
            ),
            receivedAt = nowSeconds(),
            ageSeconds = (-location.timestamp.timeIntervalSinceNow).toLong(),
        )
        adjustAccuracy()
        // Publish with this fresh fix instead of waiting for the next tick (and an older fix).
        if (policy.shouldPublish(nowSeconds())) wake.trySend(Unit)
    }

    private inner class Delegate : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            didUpdateLocations.forEach { (it as? CLLocation)?.let(::onLocation) }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            _authorization.value = manager.authorizationStatus
            update()
        }
    }

    private companion object {
        // Often enough that a 10 s moving update goes out close to when it's due.
        const val TICK_MS = 2_000L
        const val SHOW_INDICATOR_KEY = "showLocationIndicator"
        const val MONITOR_NAME = "opaq"
        const val STILL_REGION = "still"
        const val STILL_RADIUS = 100.0

        /** Background updates crash unless Info.plist declares the `location` background mode. */
        val canRunInBackground: Boolean by lazy {
            (NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>)?.contains("location") == true
        }
    }
}
