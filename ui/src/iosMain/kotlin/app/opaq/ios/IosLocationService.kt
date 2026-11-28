package app.opaq.ios

import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.CLAccuracyAuthorization
import platform.Foundation.NSProcessInfo
import platform.Foundation.lowPowerModeEnabled
import platform.UIKit.UIBackgroundRefreshStatus
import platform.UIKit.UIApplication
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
import platform.CoreLocation.CLBackgroundActivitySession
import platform.CoreLocation.CLBackgroundActivitySessionDiagnostic
import platform.CoreLocation.CLServiceSession
import platform.CoreLocation.CLServiceSessionAuthorizationRequirementAlways
import platform.CoreLocation.CLServiceSessionAuthorizationRequirementWhenInUse
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
import platform.CoreLocation.kCLLocationAccuracyThreeKilometers
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
 * automatic pausing off, inside a [CLBackgroundActivitySession]: without one, iOS froze Opaq
 * within minutes of the phone going still and the circle saw it offline (#165). The session makes iOS
 * show the blue location pill, the counterpart of Android's persistent notification. People with
 * Always access can hide it in Settings ([setShowIndicator]); that also ends the session, so iOS
 * may then freeze Opaq while the phone is still. Significant-change monitoring makes iOS
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

    /**
     * While still, the last accurate fix: where the phone stopped. Coarse fixes keep arriving (no
     * distance filter: with one, a resting phone gets no fixes at all and iOS suspends the app
     * about 10 s after it leaves the screen), but they only keep Opaq running. A 3 km fix isn't
     * passed on as a position; one whose whole error circle is clear of this spot means the phone
     * has moved.
     */
    private var stillSpot: CLLocation? = null

    /** When Core Motion started saying "walking", until it has said so for [WALK_CONFIRM_SECONDS]. */
    private var walkingSince: Long? = null

    /** Geofence around where the phone stopped; null until iOS hands it over. */
    private var monitor: CLMonitor? = null

    /** How far off [stillSpot] may be, in metres (its fix's accuracy). */
    private var stillSpotAccuracy = 0.0

    /** Wakes the publish loop early: a fix arrived while an update is due. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Keeps location running in the background (#165); recreated on every launch, background relaunches included. */
    private var backgroundSession: CLBackgroundActivitySession? = null

    /** Holds the location permission Opaq uses, alongside [backgroundSession], as Apple asks. */
    private var serviceSession: CLServiceSession? = null

    /** What iOS last said about [backgroundSession], to log changes only. */
    private var sessionNote: String? = null

    /** When the publish loop last ran (seconds), to notice iOS freezing the app. */
    private var lastTick = 0L

    /** The last update reached no relay (offline, or relays still confirming the circle key). */
    private var missed = false

    /** When the current run of missed updates began (seconds). */
    private var missedAt = 0L

    private val _authorization = MutableStateFlow(manager.authorizationStatus)
    /** Current location permission; recomposes the UI when the user changes it. */
    val authorization: StateFlow<CLAuthorizationStatus> = _authorization.asStateFlow()

    val hasPermission: Boolean get() = authorization.value.let { it == kCLAuthorizationStatusAuthorizedAlways || it == kCLAuthorizationStatusAuthorizedWhenInUse }
    val hasAlways: Boolean get() = authorization.value == kCLAuthorizationStatusAuthorizedAlways

    private val _showIndicator = MutableStateFlow(
        NSUserDefaults.standardUserDefaults.objectForKey(SHOW_INDICATOR_KEY) == null ||
            NSUserDefaults.standardUserDefaults.boolForKey(SHOW_INDICATOR_KEY),
    )
    /**
     * Whether to show the blue location pill (Dynamic Island or status bar) while sharing in the
     * background, and with it hold the background session that keeps Opaq running while the phone
     * is still. On unless the person turned it off in Settings → Sharing. iOS only honours "off"
     * with Always access; with While Using it shows the pill anyway, so the session stays too.
     * With it off, nothing at the top of the screen shows that sharing is on, and iOS may freeze
     * Opaq while the phone is still.
     */
    val showIndicator: StateFlow<Boolean> = _showIndicator.asStateFlow()

    fun setShowIndicator(show: Boolean) {
        _showIndicator.value = show
        NSUserDefaults.standardUserDefaults.setBool(show, SHOW_INDICATOR_KEY)
        if (publishJob?.isActive == true && canRunInBackground) {
            manager.showsBackgroundLocationIndicator = show
            if (wantsSession) startBackgroundSession() else stopBackgroundSession()
        }
    }

    /** The session brings the pill with it, so it's held only where the pill shows anyway. */
    private val wantsSession: Boolean get() = showIndicator.value || !hasAlways

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
        // Joined a circle and tapped Start sharing (#148), someone new arrived, or a circle paused or resumed: send our location now instead of at the next
        // interval (up to 5 minutes away while still), as Android's service does.
        scope.launch(Dispatchers.Main) {
            engine.state
                .map { s -> s.circles.map { c -> listOf(c.id, c.members.size, c.paused, c.confirmed, c.precision, c.shareTrail) } }
                .distinctUntilChanged()
                // Wake the loop too: while still it sleeps up to 30 s between checks.
                .collect { policy.publishSoon(); wake.trySend(Unit) }
        }
        // Someone in a circle asked where we are (#149): GPS now, and the fresh fix goes out at once.
        scope.launch(Dispatchers.Main) {
            engine.locationRequests.collect {
                if (publishJob?.isActive != true) return@collect
                policy.requestRefresh(nowSeconds(), always = true)
                adjustAccuracy()
                wake.trySend(Unit)
            }
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
        if (hasPermission && s.sharing && s.circles.isNotEmpty()) {
            start()
        } else {
            if (publishJob?.isActive == true) {
                engine.diagnostic(
                    "Sharing stopping: " + when {
                        !hasPermission -> "location permission is off (${authorizationName()})"
                        !s.sharing -> "sharing paused"
                        else -> "no circles"
                    },
                )
            }
            stop()
        }
    }

    /** The permission in words for the diagnostic log (#154). */
    private fun authorizationName(): String = when (authorization.value) {
        kCLAuthorizationStatusAuthorizedAlways -> "Always"
        kCLAuthorizationStatusAuthorizedWhenInUse -> "While Using"
        kCLAuthorizationStatusDenied -> "denied"
        kCLAuthorizationStatusRestricted -> "restricted"
        else -> "not asked yet"
    } + if (manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationReducedAccuracy) ", Precise off" else ""

    private fun start() {
        if (publishJob?.isActive == true) return
        engine.diagnostic(
            "Sharing started. Location permission: ${authorizationName()}. " +
                "Background refresh ${if (UIApplication.sharedApplication.backgroundRefreshStatus == UIBackgroundRefreshStatus.UIBackgroundRefreshStatusAvailable) "on" else "off"}, " +
                "low power mode ${if (NSProcessInfo.processInfo.lowPowerModeEnabled) "on" else "off"}",
        )
        // Sharing (re)started: send the first update as soon as there's a fix, not up to 5 minutes later.
        policy.publishSoon()
        // Second step of the permission flow: iOS shows the "Always" prompt at most once.
        if (authorization.value == kCLAuthorizationStatusAuthorizedWhenInUse) manager.requestAlwaysAuthorization()
        if (canRunInBackground) {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = showIndicator.value
        }
        setAccuracy(high = true)
        startBackgroundSession()
        manager.startUpdatingLocation()
        if (CLLocationManager.significantLocationChangeMonitoringAvailable()) manager.startMonitoringSignificantLocationChanges()
        startActivityUpdates()
        // Same loop as Android's LocationService: MotionPolicy decides each tick whether an update
        // is due (steady moving/still rates, plus one extra when someone starts or stops moving).
        publishJob = scope.launch(Dispatchers.Main) {
            lastTick = nowSeconds()
            while (isActive) {
                val now = nowSeconds()
                // The loop wakes at least every 30 s, so a longer gap means iOS froze the app.
                if (now - lastTick > FROZEN_SECONDS) engine.diagnostic("iOS froze Opaq for ${(now - lastTick + 30) / 60} min")
                lastTick = now
                if (engine.consentDue() && now - lastReminderAt > 24 * 3600) {
                    lastReminderAt = now
                    notifier.sharingReminder()
                }
                // Still walking after [WALK_CONFIRM_SECONDS]: a real walk, not a few steps indoors.
                walkingSince?.let { if (now - it >= WALK_CONFIRM_SECONDS) { walkingSince = null; onMovementHint() } }
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
                        val sent = withContext(Dispatchers.Default) { runCatching { engine.publishLocation(loc) }.getOrDefault(false) }
                        if (!sent && !missed) missedAt = now
                        missed = !sent
                    }
                }
                // Every 2 s while moving, confirming a walk or just after a missed update (right after a
                // relaunch the first one waits for relays to confirm the circle key, usually a second or
                // two); while still, only when the next update is due (at most 30 s), instead of waking
                // all night for nothing.
                val retrying = missed && nowSeconds() - missedAt < MotionPolicy.MISSED_RETRY_SECONDS
                val wait = if (walkingSince != null || retrying) MotionPolicy.FAST_CHECK_MILLIS else policy.nextCheckMillis(nowSeconds())
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    private fun stop() {
        publishJob?.cancel()
        publishJob = null
        highAccuracy = null
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        stopBackgroundSession(log = false)
        stillSpot = null
        if (activityUpdatesOn) activityManager.stopActivityUpdates()
        activityUpdatesOn = false
        walkingSince = null
        monitor?.removeConditionFromMonitoringWithIdentifier(STILL_REGION)
    }

    /**
     * Apple's way to keep an app's location running in the background: a background activity
     * session plus a service session for the permission in use. Created when sharing starts,
     * including straight after iOS relaunches the app in the background, which is when Apple asks
     * for them to be recreated. Its notes about why it might not be working go to the
     * diagnostic log when they change.
     */
    private fun startBackgroundSession() {
        if (backgroundSession != null || !canRunInBackground) return
        if (!wantsSession) {
            engine.diagnostic("Background session: off (blue pill hidden)")
            return
        }
        serviceSession = CLServiceSession.sessionRequiringAuthorization(
            if (hasAlways) CLServiceSessionAuthorizationRequirementAlways
            else CLServiceSessionAuthorizationRequirementWhenInUse,
        )
        backgroundSession = CLBackgroundActivitySession.backgroundActivitySessionWithQueue(dispatch_get_main_queue()) { d: CLBackgroundActivitySessionDiagnostic? ->
            val note = d?.let {
                listOfNotNull(
                    "location off for Opaq".takeIf { _ -> it.authorizationDenied },
                    "Location Services off".takeIf { _ -> it.authorizationDeniedGlobally },
                    "location restricted".takeIf { _ -> it.authorizationRestricted },
                    "started while not in use".takeIf { _ -> it.insufficientlyInUse },
                    "needs a service session".takeIf { _ -> it.serviceSessionRequired },
                    "waiting for permission".takeIf { _ -> it.authorizationRequestInProgress },
                ).joinToString(", ")
            }.orEmpty()
            if (note != sessionNote) {
                sessionNote = note
                engine.diagnostic(if (note.isEmpty()) "Background session: ok" else "Background session: $note")
            }
        }
    }

    private fun stopBackgroundSession(log: Boolean = true) {
        if (log && backgroundSession != null) engine.diagnostic("Background session: off (blue pill hidden)")
        backgroundSession?.invalidate()
        backgroundSession = null
        serviceSession?.invalidate()
        serviceSession = null
        sessionNote = null
    }

    /**
     * Core Motion says the phone started running, cycling or travelling in a vehicle: switch to GPS
     * at once. Walking only counts once it has gone on for [WALK_CONFIRM_SECONDS]: a few steps
     * around the house or office would otherwise turn GPS on for minutes each time, all day.
     * The first call shows the Motion & Fitness prompt; if it's refused, no readings arrive.
     */
    private fun startActivityUpdates() {
        if (activityUpdatesOn || !CMMotionActivityManager.isActivityAvailable()) return
        activityUpdatesOn = true
        activityManager.startActivityUpdatesToQueue(NSOperationQueue.mainQueue) { activity: CMMotionActivity? ->
            if (activity == null) return@startActivityUpdatesToQueue
            val sure = activity.confidence != CMMotionActivityConfidenceLow
            when {
                sure && (activity.running || activity.cycling || activity.automotive) -> {
                    walkingSince = null
                    onMovementHint()
                }
                activity.walking -> if (sure && walkingSince == null && publishJob?.isActive == true) {
                    walkingSince = nowSeconds()
                    wake.trySend(Unit) // check again in 2 s rather than up to 30
                }
                else -> walkingSince = null // stopped walking before it counted
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
     * GPS while moving; the coarsest location iOS has (about 3 km, mostly from cell towers) while
     * still, which keeps the app running for the still updates at far less battery than GPS (#165).
     * Leaving the geofence, Core Motion, a significant location change or a coarse fix clearly
     * away from [stillSpot] switches back to GPS.
     */
    private fun setAccuracy(high: Boolean) {
        if (high == highAccuracy) return
        highAccuracy = high
        engine.diagnostic(if (high) "Location: GPS (moving)" else "Location: coarse (still)")
        manager.desiredAccuracy = if (high) kCLLocationAccuracyBest else kCLLocationAccuracyThreeKilometers
        manager.distanceFilter = kCLDistanceFilterNone // see stillSpot
        stillSpot = if (high) null else policy.latest?.let { CLLocation(latitude = it.lat, longitude = it.lon) }
        stillSpotAccuracy = policy.latest?.accuracy?.toDouble() ?: 0.0
        if (high) monitor?.removeConditionFromMonitoringWithIdentifier(STILL_REGION) else placeStillRegion()
    }

    private fun batteryPercent(): Int? =
        UIDevice.currentDevice.batteryLevel.takeIf { it >= 0f }?.let { (it * 100).toInt().coerceIn(0, 100) }

    private fun onLocation(location: CLLocation) {
        if (location.horizontalAccuracy < 0) return // invalid fix
        if (highAccuracy == false) {
            // Still: a coarse fix isn't a position worth sharing (the still update keeps the last
            // accurate one). Only a fix whose error circle is clear of where the phone stopped,
            // with the geofence's margin on top, says it moved.
            val spot = stillSpot ?: return
            val clear = location.horizontalAccuracy + stillSpotAccuracy + STILL_RADIUS
            if (location.distanceFromLocation(spot) > clear) onMovementHint()
            return
        }
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
            if (manager.authorizationStatus != _authorization.value) {
                _authorization.value = manager.authorizationStatus
                engine.diagnostic("Location permission changed: ${authorizationName()}")
            }
            _authorization.value = manager.authorizationStatus
            update()
        }
    }

    private companion object {
        // Often enough that a 10 s moving update goes out close to when it's due.
        /** How long Core Motion has to keep saying "walking" before GPS goes on. */
        const val WALK_CONFIRM_SECONDS = 30L
        const val SHOW_INDICATOR_KEY = "showLocationIndicator"
        const val MONITOR_NAME = "opaq"
        const val STILL_REGION = "still"
        const val STILL_RADIUS = 100.0

        /** A publish-loop gap longer than this (it wakes at least every 30 s) means iOS froze the app. */
        const val FROZEN_SECONDS = 120L

        /** Background updates crash unless Info.plist declares the `location` background mode. */
        val canRunInBackground: Boolean by lazy {
            (NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>)?.contains("location") == true
        }
    }
}
