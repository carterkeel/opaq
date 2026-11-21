package app.opaq

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Smoke test of the first-run path, driven through the real screen like a person would (UI
 * Automator), so the app runs exactly as on a phone: enter a name, create a circle, keep running
 * with the map, people sheet and location service up, then open the invite screen.
 *
 * A crash anywhere kills the app process and fails the test; CI saves the stack trace from logcat
 * to the ci-logs branch.
 */
@RunWith(AndroidJUnit4::class)
class CreateCircleTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private companion object {
        const val TAG = "OpaqTest"
        const val PIN = "2468"
    }

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun waitFor(text: String, timeoutMs: Long = 20_000): UiObject2 =
        device.wait(Until.findObject(By.text(text)), timeoutMs) ?: run {
            logScreen()
            error("\"$text\" didn't appear within ${timeoutMs / 1000} s")
        }

    /**
     * Taps [label] until [expected] shows up, up to three times. A slow CI emulator sometimes puts a
     * system dialog (such as "isn't responding") over the app, which swallows the first tap.
     */
    private fun tapUntil(label: String, expected: String) {
        repeat(3) {
            dismissSystemDialogs()
            device.wait(Until.findObject(By.text(label)), 10_000)?.click()
            if (device.wait(Until.hasObject(By.text(expected)), 10_000)) return
        }
        waitFor(expected, 1_000)
    }

    /**
     * Types [pin] into the PIN field labelled [label]. Taps the label (which focuses its field) and
     * types through the input method, so it works whether or not the keyboard hides other fields
     * and survives recomposition, which made UiObject2 handles go stale.
     */
    private fun typePin(label: String, pin: String = PIN) {
        repeat(5) { attempt ->
            hideKeyboard() // the keyboard can cover the next field, so a tap on it lands on a key
            val ok = runCatching {
                val dots = By.text("•".repeat(pin.length))
                val before = device.findObjects(dots).size
                val field = device.wait(Until.findObject(By.text(label)), 5_000) ?: error("no \"$label\" field")
                field.click()
                device.waitForIdle()
                device.executeShellCommand("input text $pin")
                device.waitForIdle()
                // The field shows one dot per digit once the text arrived.
                val deadline = System.currentTimeMillis() + 3_000
                while (device.findObjects(dots).size <= before && System.currentTimeMillis() < deadline) Thread.sleep(100)
                device.findObjects(dots).size > before
            }.getOrDefault(false)
            if (ok) return
            Log.e(TAG, "\"$label\" didn't take the PIN (attempt ${attempt + 1})")
            Thread.sleep(300)
        }
        error("\"$label\" never took the PIN")
    }

    private var shots = 0

    /**
     * Saves a half-size screenshot for the CI summary (ci/emulator-test.sh pulls them), so screens
     * can be checked for visual glitches without a device. The emulator has no real data on it.
     */
    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(600) // let animations settle
        shots++
        // screencap runs as the shell user, which can always write here and adb can always read.
        device.executeShellCommand("mkdir -p /sdcard/Download/opaq-screens")
        device.executeShellCommand("screencap -p /sdcard/Download/opaq-screens/%02d-%s.png".format(shots, name))
    }

    /** Switches the emulator between light and dark mode; the app follows the system. */
    private fun darkMode(on: Boolean) {
        device.executeShellCommand("cmd uimode night ${if (on) "yes" else "no"}")
        Thread.sleep(2_500)
    }

    /** Closes the on-screen keyboard if it's showing (Back would otherwise leave the screen). */
    /** Like [tapUntil], for a button found by its content description. */
    private fun tapDescUntil(label: String, expected: String) {
        repeat(3) {
            dismissSystemDialogs()
            device.wait(Until.findObject(By.desc(label)), 10_000)?.click()
            if (device.wait(Until.hasObject(By.desc(expected)), 10_000)) return
        }
        device.wait(Until.findObject(By.desc(expected)), 1_000) ?: run {
            logScreen()
            error("\"$expected\" didn't appear")
        }
    }

    /** Finds [text], scrolling the screen's scrollable part down until it shows. */
    private fun scrollTo(text: String): UiObject2 {
        // A list under a map is short, so each scroll moves it only a little: allow plenty.
        repeat(15) {
            device.findObject(By.text(text))?.let { return it }
            // Scroll the scrollable part, keeping clear of the screen's bottom edge: a swipe that
            // starts there is the system's "go home" gesture and sends the app to the background.
            // The map counts as scrollable too; the screen's list is the one reaching lowest.
            device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.bottom }?.let { list ->
                list.setGestureMargin((device.displayHeight * 0.15).toInt())
                list.scroll(androidx.test.uiautomator.Direction.DOWN, 0.9f)
            }
            Thread.sleep(500)
        }
        return waitFor(text, 2_000)
    }

    private fun hideKeyboard() {
        if ("mInputShown=true" in device.executeShellCommand("dumpsys input_method")) {
            device.pressBack()
            device.waitForIdle()
            Thread.sleep(300)
        }
    }

    private fun dismissSystemDialogs() {
        listOf("Wait", "OK").forEach { button ->
            device.findObject(By.pkg("android").text(button))?.let {
                Log.e(TAG, "dismissing a system dialog with \"$button\"")
                it.click()
                device.waitForIdle()
            }
        }
    }

    /** Logs the texts and descriptions on screen, so a failed step in CI shows what was there instead. */
    private fun logScreen() {
        val out = ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }
        fun attr(node: String, name: String) = Regex(""" $name="([^"]*)"""").find(node)?.groupValues?.get(1).orEmpty()
        out.toString().split("<node").asSequence()
            .map { node -> attr(node, "package") to listOf(attr(node, "text"), attr(node, "content-desc")).filter { it.isNotBlank() } }
            .filter { it.second.isNotEmpty() }
            .map { (pkg, labels) -> "$pkg: ${labels.joinToString(" / ")}" }
            .distinct()
            .take(40)
            .forEach { Log.e(TAG, "on screen: $it") }
    }

    @Test
    fun setupCreateCircleAndUseTheMainScreens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        // Setup, step 1: name.
        waitFor("What should people call you?")
        shot("setup-name")
        val nameField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000) ?: error("no name field")
        nameField.click()
        nameField.text = "Tester"
        device.pressBack() // close the keyboard
        waitFor("Continue").click()

        // Step 2: photo (skip).
        waitFor("Add a profile photo")
        shot("setup-photo")
        waitFor("Skip").click()

        // Step 3: optional PIN (set one, so the PIN prompt gets exercised later).
        waitFor("Add a PIN?")
        shot("setup-pin")
        typePin("New PIN (4 to 8 digits)")
        typePin("New PIN again")
        hideKeyboard()
        waitFor("Set PIN").click()

        // Step 4: create a circle.
        waitFor("Create a circle")
        shot("setup-circle")
        waitFor("Create a circle").click()
        waitFor("Create").click()

        // Step 5: location (already granted by the test rule). Background location and the battery
        // saver aren't, so their prompts (#131) show too, and may push the button below the screen.
        waitFor("Keep sharing when Opaq is closed")
        shot("setup-location")
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
        waitFor("Open the map").click()

        // The home screen with the map and people sheet appears and keeps running.
        waitFor("Invite", 30_000)
        Thread.sleep(25_000)
        assertNotNull("Home screen is gone after 25 s (did the app crash?)", device.findObject(By.text("Invite")))
        shot("home")

        // Sharing toggle on the map.
        // The sharing button is an icon described as "Sharing" or "Paused"; it says so in words for 5 s after a tap.
        tapDescUntil("Sharing", expected = "Paused")
        shot("sharing-paused")
        tapDescUntil("Paused", expected = "Sharing")

        // Invite screen with a QR code, as a link several people can use (#53). Creating an invite
        // asks for the PIN once the last entry is old.
        device.findObject(By.text("Invite")).click()
        waitFor("Let more than one person use this link").click()
        waitFor("Create invite").click()
        if (device.wait(Until.hasObject(By.text("Enter your PIN")), 5_000)) {
            typePin("PIN")
            waitFor("Continue").click()
        }
        waitFor("Used by 0 of 10 people")
        shot("invite")
        // The buttons are below the QR code. Cancel the invite early: back to "Create invite".
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
        // Below the QR code, so a small screen has to scroll to them.
        scrollTo("Share link")
        scrollTo("Cancel this invite").click()
        waitFor("Create invite")
        device.pressBack()

        // An invite link cut off at the # (#96): the join screen says it's incomplete.
        waitFor("Invite")
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://opaq.me/join"), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        device.wait(Until.findObject(By.textContains("invite link is incomplete")), 20_000) ?: run {
            logScreen()
            error("the join screen didn't say the link is incomplete")
        }
        shot("join-incomplete")
        device.pressBack()

        // Settings, then back to the map.
        waitFor("Invite")
        (device.wait(Until.findObject(By.desc("Settings")), 10_000) ?: error("no Settings button")).click()
        waitFor("Appearance")
        shot("settings")
        // Each section opens on its own screen (#108). A row from each shows it opened.
        listOf(
            "Sharing" to "Share my location",
            "Circles" to "Family",
            "Appearance" to "Dark map",
            "Privacy" to "Location history",
            "Security" to "Security log",
            "Backups" to "Back up to a file",
            "Connection" to "Relays",
        ).forEach { (section, row) ->
            waitFor(section).click()
            waitFor(row)
            shot("settings-${section.lowercase()}")
            device.pressBack()
            waitFor("Appearance")
        }
        // Place alerts (#151): add one where the map is, see it listed, then delete it.
        waitFor("Place alerts").click()
        waitFor("Add a place").click()
        waitFor("Move the map and tap where the place should be.")
        val placeName = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000) ?: error("no place name field")
        placeName.click()
        placeName.text = "Home"
        device.pressBack() // close the keyboard
        Thread.sleep(2_000) // let the map load
        shot("place-new")
        scrollTo("Add place").click()
        waitFor("Add a place")
        waitFor("Home")
        shot("places")
        waitFor("Home").click()
        waitFor("Edit place")
        scrollTo("Delete place").click()
        waitFor("Delete").click()
        waitFor("Add a place")
        device.pressBack()
        waitFor("Appearance")

        // The app's own theme, then back to following the phone.
        waitFor("Appearance").click()
        waitFor("Theme").click()
        waitFor("Dark").click()
        waitFor("Dark map")
        shot("settings-theme-dark")
        waitFor("Theme").click()
        waitFor("System default").click()
        waitFor("System default")
        // The launcher icon: switch to another and back, without the app closing.
        waitFor("App icon").click()
        waitFor("Oversized A")
        shot("settings-app-icon")
        waitFor("Two-tone").click()
        waitFor("Two-tone")
        waitFor("App icon").click()
        waitFor("Dark on white").click()
        waitFor("Dark on white")
        device.pressBack()
        waitFor("Appearance")
        device.pressBack()
        waitFor("Invite")

        // Your profile, then your location history (#58).
        (device.wait(Until.findObject(By.desc("Profile")), 10_000) ?: error("no Profile button")).click()
        waitFor("Location history")
        shot("profile")
        waitFor("Location history").click()
        waitFor("Your history")
        waitFor("Today")
        Thread.sleep(3_000) // let the history map load
        shot("history")
        device.pressBack()
        waitFor("Location history")
        device.pressBack()
        waitFor("Invite")

        // The same screens in dark mode.
        darkMode(true)
        try {
            waitFor("Invite")
            shot("dark-home")
            (device.wait(Until.findObject(By.desc("Profile")), 10_000) ?: error("no Profile button")).click()
            waitFor("Location history")
            shot("dark-profile")
            waitFor("Location history").click()
            waitFor("Your history")
            Thread.sleep(2_000)
            shot("dark-history")
            device.pressBack()
            device.pressBack()
            (device.wait(Until.findObject(By.desc("Settings")), 10_000) ?: error("no Settings button")).click()
            waitFor("Appearance")
            shot("dark-settings")
            device.pressBack()
            waitFor("Invite")
        } catch (t: Throwable) {
            // Screenshots only: a missed step here shouldn't fail the run (a crash still shows in logcat).
            Log.e(TAG, "dark mode screenshots stopped: ${t.message}")
            logScreen()
        } finally {
            darkMode(false)
        }
        Thread.sleep(3_000)
    }
}
