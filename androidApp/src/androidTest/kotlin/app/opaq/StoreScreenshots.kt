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

        // Sam's drive: up Valencia Street, then along Market Street towards the Ferry Building.
        val DRIVE = listOf(
            37.75222 to -122.42063, // Valencia & 24th
            37.76505 to -122.42188, // Valencia & 16th
            37.77235 to -122.42250, // Valencia & Market
            37.77525 to -122.41930, // Market & Van Ness
            37.78395 to -122.40780, // Market & 5th
        )
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
        Thread.sleep(2_000)

        val now = System.currentTimeMillis() / 1000
        val me = engine.myPubkey
        val sam = KeyPair.generate().publicHex
        val maya = KeyPair.generate().publicHex
        val leo = KeyPair.generate().publicHex
        val nora = KeyPair.generate().publicHex

        // Sam's drive: a point every 10 s at about 11 m/s (25 mph), ending at Market & 5th.
        val path = drivePoints(end = now - 15, stepSeconds = 10, metersPerSecond = 11.0)
        val last = path.last()
        val heading = bearing(path[path.size - 2], last)

        val locations = mapOf(
            me to MemberLocation(ME_LAT, ME_LON, 6f, activity = Activity.STILL, battery = 86, time = now - 20),
            sam to MemberLocation(
                last.lat, last.lon, 5f, speed = 11.2f, heading = heading.toFloat(), activity = Activity.DRIVING,
                battery = 72, time = last.t, points = path.dropLast(1).takeLast(6),
            ),
            // Maya and Leo together at a café on Valencia Street: drawn as one stack.
            maya to MemberLocation(37.75870, -122.42150, 8f, activity = Activity.STILL, battery = 64, time = now - 95),
            leo to MemberLocation(37.75885, -122.42132, 10f, speed = 1.3f, activity = Activity.WALKING, battery = 15, time = now - 40),
            // Nora shares an approximate location with this circle.
            nora to LocationPrecision.apply(
                MemberLocation(37.75020, -122.43370, 12f, activity = Activity.STILL, battery = 91, time = now - 240),
                Precision.NEIGHBORHOOD,
            ).copy(precision = Precision.NEIGHBORHOOD),
        )
        val people = listOf(sam to "Sam", maya to "Maya", leo to "Leo", nora to "Nora")
        val colours = mapOf(sam to 3, maya to 6, leo to 1, nora to 8)

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
            locations = s.locations + (circle.id to locations),
            lastCircleId = circle.id,
        )

        // Sam's history for today: at home in the morning, then the drive.
        val history = LocationHistory(FileHistory(context))
        val key = historyKey.hexToBytes()
        val home = 37.75100 to -122.42800
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
        Thread.sleep(12_000) // map tiles
        shot("map")

        // Sam's profile, from the info button on his row.
        profileButtonFor("Sam").click()
        waitFor("Location history")
        Thread.sleep(2_000)
        shot("profile")

        waitFor("Location history").click()
        waitFor("Sam's history")
        Thread.sleep(8_000) // history map
        shot("history")
        device.pressBack()
        waitFor("Location history")
        device.pressBack()
        waitFor("Invite")

        // Invite with its QR code.
        waitFor("Invite").click()
        waitFor("Create invite").click()
        device.wait(Until.hasObject(By.textContains("Share link")), 15_000)
        Thread.sleep(2_000)
        shot("invite")
        device.pressBack()
        waitFor("Invite")

        // Dark mode: the map, then the quick menu on someone's row.
        device.executeShellCommand("cmd uimode night yes")
        Thread.sleep(4_000)
        waitFor("Sam")
        Thread.sleep(8_000)
        shot("dark-map")
        waitFor("Maya").longClick()
        Thread.sleep(1_500)
        shot("dark-menu")
        device.pressBack()
        device.executeShellCommand("cmd uimode night no")
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

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(800)
        shots++
        device.executeShellCommand("mkdir -p $DIR")
        device.executeShellCommand("screencap -p $DIR/%02d-%s.png".format(shots, name))
    }
}
