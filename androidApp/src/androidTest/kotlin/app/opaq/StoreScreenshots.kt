package app.opaq

import android.content.Intent
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.opaq.core.crypto.KeyPair
import app.opaq.core.crypto.hexToBytes
import app.opaq.core.crypto.toHex
import app.opaq.core.LocationHistory
import app.opaq.core.model.Activity
import app.opaq.core.model.AppState
import app.opaq.core.model.Contact
import app.opaq.core.model.LocationPrecision
import app.opaq.core.model.Member
import app.opaq.core.model.MemberLocation
import app.opaq.core.model.MemberProfile
import app.opaq.core.model.Place
import app.opaq.core.model.Point
import app.opaq.core.model.Precision
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Screenshots for the Google Play listing, made by `ci/store-screenshots.sh` (workflow
 * store-screenshots.yml). Skipped unless run with `-e storeScreenshots 1`, so the normal emulator
 * test (connectedDebugAndroidTest) never runs it.
 *
 * Everything shown is made up: invented people in downtown San Francisco, with letter avatars.
 * Nothing is sent anywhere: the script points the debug app at a relay address nothing answers.
 *
 * [seed] runs first and writes the made-up circle into the app's saved state (and one person's
 * drive into location history); the script then stops the app so [capture] starts it fresh from
 * that state and takes the pictures.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshots {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val enabled = InstrumentationRegistry.getArguments().getString("storeScreenshots") == "1"

    private companion object {
        const val TAG = "OpaqStore"
        const val DIR = "/sdcard/Download/opaq-store"

        // Where "you" are: Mission Dolores Park. The script sets the emulator's GPS here too.
        const val ME_LAT = 37.75960
        const val ME_LON = -122.42690

        // Sam's drive: from home along 24th Street, then up Valencia Street.
        val DRIVE = listOf(
            37.75160 to -122.43000, // 24th & Sanchez (home)
            37.75222 to -122.42063, // Valencia & 24th
            37.76505 to -122.42188, // Valencia & 16th
            37.76800 to -122.42215, // Valencia & 14th
        )

        /**
         * Moving people's updates are stamped this far ahead, so they still read "Driving · 25 mph ·
         * just now" when the pictures are taken a minute or two after [seed] (an update over 50 s
         * old reads "Was driving"). The app treats a future time as just now and doesn't move them.
         */
        const val AHEAD = 240L
    }

    @Test
    fun seed() {
        assumeTrue(enabled)
        runBlocking { seedState() }
    }

    private suspend fun seedState() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as OpaqApp
        val engine = app.engine
        // Let the engine load or make its state, then make the circle the normal way.
        Thread.sleep(3_000)
        val circle = engine.createCircle("Family")
        val args = InstrumentationRegistry.getArguments()
        // A long list of circles, to check the circle menu scrolls (-e manyCircles 1).
        if (args.getString("manyCircles") == "1") {
            listOf("Work", "Soccer team", "Book club", "Neighbours", "Cousins", "Roommates", "College friends", "Climbing")
                .forEach { engine.createCircle(it) }
        }
        Thread.sleep(2_000)

        val now = System.currentTimeMillis() / 1000
        val me = engine.myPubkey
        val sam = KeyPair.generate().publicHex
        val maya = KeyPair.generate().publicHex
        val leo = KeyPair.generate().publicHex
        val nora = KeyPair.generate().publicHex

        // Sam's drive: a point every 10 s at about 11 m/s (25 mph), ending at Valencia & 14th.
        val path = drivePoints(end = now - 15, stepSeconds = 10, metersPerSecond = 11.0)
        val last = path.last()
        val heading = bearing(path[path.size - 2], last)
        val live = path.map { it.copy(t = it.t + 15 + AHEAD) }

        val locations = mapOf(
            me to MemberLocation(ME_LAT, ME_LON, 6f, activity = Activity.STILL, battery = 86, time = now - 20),
            sam to MemberLocation(
                last.lat, last.lon, 5f, speed = 11.2f, heading = heading.toFloat(), activity = Activity.DRIVING,
                battery = 72, time = live.last().t, points = live.dropLast(1).takeLast(6),
            ),
            // Maya and Leo together near Mission & 24th: drawn as one stack.
            maya to MemberLocation(37.75215, -122.41850, 8f, activity = Activity.STILL, battery = 64, time = now - 95),
            leo to MemberLocation(37.75232, -122.41828, 10f, speed = 1.3f, activity = Activity.WALKING, battery = 15, time = now + AHEAD),
            // Nora shares an approximate location with this circle.
            nora to LocationPrecision.apply(
                MemberLocation(37.75020, -122.43370, 12f, activity = Activity.STILL, battery = 91, time = now - 240),
                Precision.NEIGHBORHOOD,
            ).copy(precision = Precision.NEIGHBORHOOD),
        )
        // Three more people with Maya and Leo, to check a long stack list (-e bigStack 1).
        val extra = if (args.getString("bigStack") == "1") listOf("Ava", "Ben", "Zoe").map { KeyPair.generate().publicHex to it } else emptyList()
        val extraLocations = extra.mapIndexed { i, (pk, _) ->
            pk to MemberLocation(37.75220 + i * 0.00008, -122.41840 - i * 0.00006, 9f, activity = Activity.STILL, battery = 50 + i * 15, time = now - 120)
        }.toMap()
        val people = listOf(sam to "Sam", maya to "Maya", leo to "Leo", nora to "Nora") + extra
        val colours = mapOf(sam to 3, maya to 6, leo to 1, nora to 8) + extra.mapIndexed { i, (pk, _) -> pk to (2 + i * 2) }

        val s = engine.state.value
        val historyKey = s.historyKeyHex ?: KeyPair.generate().secret.toHex()
        val seeded: AppState = s.copy(
            myName = "Alex",
            myColor = 0,
            setupDone = true,
            sharing = true,
            profileChangedAt = now,
            historyKeyHex = historyKey,
            circles = s.circles.map { c ->
                if (c.id != circle.id) c else c.copy(members = listOf(Member(me, "Alex")) + people.map { (pk, name) -> Member(pk, name) })
            },
            profiles = s.profiles + people.associate { (pk, name) -> pk to MemberProfile(name, colours[pk], updated = now - 3_600) },
            contacts = s.contacts + mapOf(sam to Contact(verified = true), maya to Contact(verified = true), nora to Contact(verified = true)),
            locations = s.locations + (circle.id to locations + extraLocations),
            lastCircleId = circle.id,
            places = listOf(
                Place("home", "Home", DRIVE.first().first, DRIVE.first().second, radius = 120.0, circleId = circle.id, onArrive = true, onLeave = true),
                Place("school", "School", 37.76170, -122.42830, radius = 200.0, circleId = circle.id),
                Place("work", "Sam's work", 37.77230, -122.41450, radius = 150.0, circleId = circle.id),
            ),
        )

        // Sam's history for today: at home in the morning, then the drive.
        val history = LocationHistory(FileHistory(context))
        val key = historyKey.hexToBytes()
        val home = DRIVE.first()
        for (i in 0..6) {
            history.record(key, sam, MemberLocation(home.first, home.second, 8f, activity = Activity.STILL, time = path.first().t - 3_000 + i * 400L))
        }
        path.chunked(6).forEach { chunk ->
            val p = chunk.last()
            history.record(
                key, sam,
                MemberLocation(p.lat, p.lon, 5f, speed = 11.2f, activity = Activity.DRIVING, time = p.t, points = chunk.dropLast(1)),
            )
        }

        SecureStore(context).save(Json { ignoreUnknownKeys = true; encodeDefaults = true }.encodeToString(AppState.serializer(), seeded))
        Log.i(TAG, "seeded ${people.size} people in ${circle.name}")
    }

    @Test
    fun capture() {
        assumeTrue(enabled)
        val context = instrumentation.targetContext
        device.executeShellCommand("cmd uimode night no")
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        waitFor("Sam", 40_000)
        Thread.sleep(10_000) // map tiles
        shot("map")

        // Only the steps that lead to a wanted shot run, so one that doesn't fit a screen size
        // (a row that's off screen, say) can't stop the others.
        if (want("profile", "history", "history-scrolled", "history-full")) {
            // Sam's profile, from the info button on his row.
            profileButtonFor("Sam").click()
            waitFor("Location history")
            Thread.sleep(2_000)
            shot("profile")
            if (want("history", "history-scrolled", "history-full")) {
                // Tapping the map at the top of the profile opens history (#184).
                device.click(device.displayWidth / 2, device.displayHeight / 12)
                waitFor("Sam's history")
                Thread.sleep(8_000) // history map
                shot("history")
                if (want("history-scrolled")) {
                    // The map stays put and the stretches scroll under it, fading out.
                    // Dragged from inside the list (a drag from the bottom edge would go home).
                    val still = device.findObjects(By.text("Still")).maxByOrNull { it.visibleBounds.top }
                    if (still != null) {
                        val y = still.visibleBounds.centerY()
                        device.swipe(device.displayWidth / 2, y, device.displayWidth / 2, y - 220, 40)
                        Thread.sleep(1_500)
                    }
                    shot("history-scrolled")
                }
                if (want("history-full")) {
                    (device.wait(Until.findObject(By.desc("Full screen")), 5_000) ?: error("no Full screen button")).click()
                    Thread.sleep(8_000)
                    shot("history-full")
                    device.pressBack() // leaves full screen
                    waitFor("Sam's history")
                }
                device.pressBack()
                waitFor("Location history")
            }
            device.pressBack()
            waitFor("Invite")
        }

        if (want("circles")) {
            // The circle menu, opening out of the circle button (#185).
            (device.wait(Until.findObject(By.desc("Switch circle")), 10_000) ?: error("no circle button")).click()
            // Its top (the button it grew from) says "Close"; a long list may hide "New circle" below.
            device.wait(Until.hasObject(By.desc("Close")), 10_000) || error("the circle menu didn't open")
            Thread.sleep(1_500)
            shot("circles")
            device.click(device.displayWidth / 2, device.displayHeight * 2 / 3) // tap elsewhere: closes
            Thread.sleep(1_000)
            waitFor("Invite")
        }

        if (want("group")) {
            // Tap the stack of Maya and Leo: their list pops up over the map (#146).
            val stack = device.wait(Until.findObject(By.descContains("people here")), 10_000) ?: error("no stack on the map")
            val count = stack.contentDescription
            val box = stack.visibleBounds
            val faceY = box.top + (22 * context.resources.displayMetrics.density).toInt()
            device.click(box.centerX(), faceY)
            device.wait(Until.hasObject(By.text(count)), 10_000) || error("the stack's list didn't open")
            Thread.sleep(2_500) // the map moves, the list pops in
            shot("group")
            device.click(device.displayWidth / 2, device.displayHeight / 6) // tap elsewhere: closes
            Thread.sleep(1_000)
        }

        if (want("invite")) {
            // Invite with its QR code.
            waitFor("Invite").click()
            waitFor("Create invite").click()
            device.wait(Until.hasObject(By.textContains("Share link")), 15_000)
            Thread.sleep(2_000)
            shot("invite")
            device.pressBack()
            waitFor("Invite")
        }

        if (!want("dark-map", "dark-people", "dark-history", "dark-place")) return
        // Dark mode with the dark map.
        darkMap(true)
        device.executeShellCommand("cmd uimode night yes")
        Thread.sleep(3_000)
        waitFor("Sam")
        Thread.sleep(6_000)
        shot("dark-map")

        if (want("dark-people")) {
            // The people sheet pulled up, so everyone's row shows. Dragged by its title, inside the
            // sheet (a drag that starts above it moves the map instead).
            waitFor("Family", 5_000)
            // The sheet's title, not the circle picker at the top that says the same.
            val title = device.findObjects(By.text("Family")).maxByOrNull { it.visibleBounds.top }!!.visibleBounds
            device.swipe(title.centerX(), title.centerY(), title.centerX(), device.displayHeight / 4, 60)
            Thread.sleep(2_000)
            shot("dark-people")
        }

        if (want("dark-history")) {
            // Sam's history on the dark map.
            profileButtonFor("Sam").click()
            waitFor("Location history").click()
            waitFor("Sam's history")
            Thread.sleep(8_000)
            shot("dark-history")
            device.pressBack()
            waitFor("Location history")
            device.pressBack()
            waitFor("Invite")
        }

        if (want("dark-place")) {
            // A place alert, with its area on the map.
            (device.wait(Until.findObject(By.desc("Settings")), 10_000) ?: error("no Settings button")).click()
            waitFor("Appearance")
            if (device.findObject(By.text("Place alerts")) == null) waitFor("Circles").click()
            waitFor("Place alerts").click()
            waitFor("School").click()
            waitFor("Edit place")
            Thread.sleep(8_000) // map tiles
            shot("dark-place")
        }
    }

    /**
     * The demo video for Google Play's background location declaration (the script records the
     * screen meanwhile; sharing is on, background location not yet allowed). It has to show the
     * in-app disclosure, Android's permission screen, and the feature at work in the background:
     * Settings → Sharing → Background location → Turn on, the disclosure, Continue, "Allow all the
     * time", then the home screen with the "Sharing your location" notification still there.
     * Each step logs a "mark" line the script turns into captions.
     */
    @Test
    fun demo() {
        assumeTrue(enabled)
        val context = instrumentation.targetContext
        device.executeShellCommand("cmd uimode night no")
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        waitFor("Sam", 40_000)
        Thread.sleep(6_000) // map tiles
        mark("open")
        Thread.sleep(2_000)

        mark("settings")
        (device.wait(Until.findObject(By.desc("Settings")), 10_000) ?: error("no Settings button")).click()
        waitFor("Sharing").click()
        waitFor("Background location")
        Thread.sleep(2_000)
        waitFor("Turn on").click()

        mark("disclosure")
        device.wait(Until.hasObject(By.text("Share your location in the background")), 10_000) || error("no disclosure")
        Thread.sleep(6_000)
        waitFor("Continue").click()

        mark("prompt")
        val always = device.wait(Until.findObject(By.text("Allow all the time")), 15_000) ?: error("no permission screen")
        Thread.sleep(2_000)
        always.click()
        Thread.sleep(2_000)
        device.pressBack()
        device.wait(Until.hasObject(By.textContains("Sharing keeps going in the background")), 10_000)
        mark("allowed")
        Thread.sleep(3_000)

        mark("background")
        device.pressHome()
        Thread.sleep(2_000)
        device.openNotification()
        device.wait(Until.hasObject(By.textContains("Sharing your location")), 10_000) || error("no sharing notification")
        Thread.sleep(5_000)
        mark("end")
    }

    /**
     * Setup's location step, which should fit without scrolling: before location access, then with
     * it allowed, in dark and light mode. Runs on a fresh install without location access (no seed:
     * setup only shows while there are no circles), going through the earlier steps to get there.
     */
    @Test
    fun setup() {
        assumeTrue(enabled)
        val context = instrumentation.targetContext
        val pkg = context.packageName
        device.executeShellCommand("cmd uimode night yes")
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        waitFor("What should people call you?", 30_000)
        val name = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000) ?: error("no name field")
        name.click()
        name.text = "Alex"
        device.pressBack() // close the keyboard
        waitFor("Continue").click()
        waitFor("Add a profile photo")
        waitFor("Skip").click()
        waitFor("Add a PIN?")
        waitFor("Not now").click()
        waitFor("Create a circle")
        // A tap while the step is still sliding in can be lost, so tap until the next screen shows.
        repeat(5) {
            if (device.hasObject(By.text("Create"))) return@repeat
            device.findObject(By.text("Create a circle"))?.click()
            device.wait(Until.hasObject(By.text("Create")), 3_000)
        }
        waitFor("Create").click()
        waitFor("Share your location", 30_000)
        Thread.sleep(2_000)
        shot("setup-dark")
        // Changing the theme recreates the screen: setup has to stay on this step.
        device.executeShellCommand("cmd uimode night no")
        Thread.sleep(3_000)
        waitFor("Share your location", 10_000)
        shot("setup-light")
        // Allow location: the step stays, with location access ticked and "Open the map".
        waitFor("Allow location").click()
        (device.wait(Until.findObject(By.text("While using the app")), 10_000) ?: error("no permission dialog")).click()
        waitFor("Open the map", 15_000)
        Thread.sleep(2_000)
        shot("setup-allowed-light")
        device.executeShellCommand("cmd uimode night yes")
        Thread.sleep(3_000)
        waitFor("Open the map", 10_000)
        shot("setup-allowed-dark")
        device.executeShellCommand("cmd uimode night no")
    }

    /**
     * Checks the map stays smooth and its buttons keep working after a stack's list has been opened
     * and closed: frame times while dragging the map before and after (from `dumpsys gfxinfo`), the
     * app's windows left over, and whether Settings still opens. Written to check-group.txt.
     */
    @Test
    fun groupCheck() {
        assumeTrue(enabled)
        val context = instrumentation.targetContext
        val pkg = context.packageName
        val report = StringBuilder()
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        waitFor("Sam", 40_000)
        Thread.sleep(10_000)
        fun drag(label: String) {
            device.executeShellCommand("dumpsys gfxinfo $pkg reset")
            val w = device.displayWidth
            val y = device.displayHeight / 3
            repeat(6) { i ->
                if (i % 2 == 0) device.swipe(w / 4, y, w * 3 / 4, y + 60, 30) else device.swipe(w * 3 / 4, y + 60, w / 4, y, 30)
                Thread.sleep(400)
            }
            val info = device.executeShellCommand("dumpsys gfxinfo $pkg")
            val lines = info.lines().filter { l -> listOf("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "99th percentile").any { l.trim().startsWith(it) } }
            report.append("$label:\n").append(lines.joinToString("\n") { "  " + it.trim() }).append("\n")
        }
        fun windows(label: String) {
            val all = device.executeShellCommand("dumpsys window windows")
            val ours = all.lines().filter { it.contains("Window{") && it.contains(pkg) }.map { it.trim() }
            report.append("$label windows: ${ours.size}\n").append(ours.joinToString("\n") { "  $it" }).append("\n")
        }
        drag("before")
        windows("before")
        val stack = device.wait(Until.findObject(By.descContains("people here")), 10_000) ?: error("no stack on the map")
        val count = stack.contentDescription
        val box = stack.visibleBounds
        device.click(box.centerX(), box.top + (22 * context.resources.displayMetrics.density).toInt())
        device.wait(Until.hasObject(By.text(count)), 10_000) || error("the stack's list didn't open")
        Thread.sleep(1_500)
        windows("open")
        // Tap the map well below the list (which sits above the faces): closes it.
        val title = device.findObject(By.text(count))?.visibleBounds
        report.append("list title at: $title, stack at: $box\n")
        device.click(device.displayWidth / 2, box.bottom + (48 * context.resources.displayMetrics.density).toInt())
        val gone = device.wait(Until.gone(By.text(count)), 5_000)
        report.append("list closed after tapping outside: $gone\n")
        Thread.sleep(1_500)
        windows("closed")
        drag("after")
        val settings = device.wait(Until.findObject(By.desc("Settings")), 5_000)
        settings?.click()
        val opened = device.wait(Until.hasObject(By.text("Appearance")), 5_000)
        report.append("Settings opens after closing: $opened\n")
        if (opened) device.pressBack()
        Log.i(TAG, report.toString())
        device.executeShellCommand("mkdir -p $DIR")
        java.io.File(context.getExternalFilesDir(null), "check-group.txt").writeText(report.toString())
        device.executeShellCommand("cp ${context.getExternalFilesDir(null)}/check-group.txt $DIR/check-group.txt")
    }

    private fun mark(name: String) = Log.i(TAG, "mark $name")

    /** Settings → Appearance → Dark map, then back to the map. */
    private fun darkMap(on: Boolean) {
        (device.wait(Until.findObject(By.desc("Settings")), 10_000) ?: error("no Settings button")).click()
        waitFor("Appearance").click()
        val row = waitFor("Dark map")
        val switch = device.findObjects(By.checkable(true)).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - row.visibleBounds.centerY()) }
        if (switch == null || switch.isChecked != on) row.click()
        Thread.sleep(800)
        device.pressBack()
        waitFor("Appearance")
        device.pressBack()
        waitFor("Invite")
    }

    private fun drivePoints(end: Long, stepSeconds: Long, metersPerSecond: Double): List<Point> {
        val stepMeters = metersPerSecond * stepSeconds
        val out = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until DRIVE.size - 1) {
            val (a, b) = DRIVE[i] to DRIVE[i + 1]
            val n = maxOf(1, (meters(a, b) / stepMeters).toInt())
            for (k in 0 until n) out += (a.first + (b.first - a.first) * k / n) to (a.second + (b.second - a.second) * k / n)
        }
        out += DRIVE.last()
        val start = end - (out.size - 1) * stepSeconds
        return out.mapIndexed { i, (lat, lon) -> Point(lat, lon, start + i * stepSeconds) }
    }

    private fun meters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dy = (b.first - a.first) * 111_320.0
        val dx = (b.second - a.second) * 111_320.0 * cos(Math.toRadians(a.first))
        return sqrt(dx * dx + dy * dy)
    }

    private fun bearing(a: Point, b: Point): Double {
        val dy = b.lat - a.lat
        val dx = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    private fun waitFor(text: String, timeoutMs: Long = 20_000): UiObject2 =
        device.wait(Until.findObject(By.text(text)), timeoutMs) ?: error("\"$text\" didn't appear within ${timeoutMs / 1000} s")

    /** The "Profile" info button on the same row as [name]. */
    private fun profileButtonFor(name: String): UiObject2 {
        val row: Rect = waitFor(name).visibleBounds
        return device.findObjects(By.desc("Profile")).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - row.centerY()) }
            ?: error("no Profile button next to $name")
    }

    private var shots = 0

    private val args = InstrumentationRegistry.getArguments()

    /** Which shots to save (`-e shots map,history`; all by default), and their file name prefix. */
    private val wanted = args.getString("shots")?.takeIf { it != "all" }?.split(",")?.toSet()
    private val prefix = args.getString("prefix") ?: "phone"

    private fun want(vararg names: String) = wanted == null || names.any { it in wanted }

    private fun shot(name: String) {
        shots++
        if (!want(name)) return
        device.waitForIdle()
        Thread.sleep(800)
        device.executeShellCommand("mkdir -p $DIR")
        device.executeShellCommand("screencap -p $DIR/%s-%02d-%s.png".format(prefix, shots, name))
    }
}
