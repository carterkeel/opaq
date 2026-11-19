# Testing Opaq

Sections 1–7 cover Android. iOS is in [section 8](#8-ios); the two-phone test works across platforms, so an iPhone and an Android phone can test each other.

## 1. Get a build

**Easiest:** open the repo's **Actions** tab, pick the latest green run, and download an artifact (you need to be signed in to GitHub). Unzip it to get the `.apk`.

- **`opaq-staging-apk`**: optimised like a release build. Use this one to judge smoothness and battery.
- **`opaq-debug-apk`**: debuggable and noticeably slower (Compose runs unoptimised). Use it for `adb` debugging.

**From source:** install [Android Studio](https://developer.android.com/studio), open the repo folder, and press Run. Android Studio installs the JDK and SDK for you.

The debug build installs as **app.opaq.debug** and the staging build as **app.opaq.staging**, so they can sit side by side and won't clash with a future release build.

## 2. Install on a phone

1. Copy the APK to the phone (Google Drive, a USB cable, or email it to yourself).
2. Tap it. Android asks you to allow installs from that app once.
3. Or, with USB debugging on: `adb install -r opaq-debug.apk`.

## 3. The core test: two phones

Location sharing needs at least two devices. Two real phones are best; one phone plus an emulator also works.

1. **Phone A:** enter a name (the photo and PIN steps are optional), tap **Create a circle**, allow location (choose *While using the app*), and allow notifications. You should see a "Sharing your location" notification.
2. **Phone A:** tap **Invite**, then **Create invite**. A QR code appears, along with **Share link**.
3. **Phone B:** in person, tap **Join a circle → Scan QR code** and point it at phone A, enter a name and tap **Ask to join**. Remotely, tap the shared link (the page has an **Open in Opaq** button), or paste the link or the whole message into **Or paste an invite link** and tap **Open invite**. The join screen shows phone A's device code: compare it with the code phone A's owner reads out and tap **The codes match**, or tap **Join without checking** (A then has no green check until you mark them verified from their profile).
4. **Phone A:** a banner says B wants to join. Tap **Review** and compare the device code with phone B, then **Approve**.
5. **Phone B:** once approved, the circle shows who's in it and asks you to tap **Start sharing**. Within a minute or two, both phones should show each other on the map and in the list.

Things to check after that:

| Test | Expected |
|---|---|
| Walk or drive with phone B | Phone A shows "Walking" or "Driving · NN mph", updating about every 30 seconds, with the dot gliding between updates |
| Leave phone B still for 10 minutes | Updates slow to about every 5 minutes; status shows "Still" |
| Turn phone B off for an hour | Phone A shows "Offline · last seen 1 h ago" after 45 minutes |
| Phone A removes B (profile → Remove) | B's circle disappears; A no longer receives B's updates |
| Reuse an invite link | The second person is told "This invite was already used. Ask for a new one." and stops waiting |
| Turn **Let more than one person use this link** on, then join from two phones | A approves each one; the invite screen counts "Used by 2 of 10 people" |
| Cancel an invite, then try it | "This invite was cancelled. Ask for a new one." |
| Phone A denies a request | Phone B shows "A didn't let you in." |
| Open a shared link cut off at the # (delete everything after it) | The page and the app's join screen both say the link is incomplete |
| Paste the whole shared message (not just the link) into **Or paste an invite link** | The invite opens |
| Restart phone B | Sharing resumes if "Allow all the time" was granted |
| Settings → Relays | Green dots for connected relays; removing one still works |

## 4. Faking movement

- **Emulator:** in the emulator's **⋮ → Location** panel, load a GPX/KML route and press Play to simulate driving.
- **Real phone:** enable Developer options, install a mock-location app, and choose it under *Select mock location app*.

## 5. Watching what happens

With the phone connected over USB:

```
adb logcat -s Opaq
```

This shows relay connections, rejections, and notices. It never logs locations or keys.

## 6. Battery and background reliability

- Test on at least one **Samsung or Xiaomi** phone, not just a Pixel. Those brands stop background apps most aggressively.
- In Opaq's **Settings**, use **Battery saver for Opaq → Turn off** and **Background location → Turn on**, then leave the phone in a pocket for a few hours and check whether updates kept arriving.
- Android's **Settings → Battery → Battery usage** shows how much Opaq used.

## 7. Automated tests

```
./gradlew :shared:testAndroidHostTest
```

These cover NIP-44 encryption (including the spec's test vectors), event signing, sealed messages, circle key rotation, fixed-size updates, invite links and the motion policy. They run on every push in GitHub Actions.

## 8. iOS

You need a Mac with Xcode. The iOS app lives in `iosApp/`; it hosts the same shared screens and protocol as Android.

### Run in the simulator

1. Install Xcode and JDK 17 (`brew install openjdk@17`). Xcode's build step finds JDK 17 by itself.
2. Open `iosApp/iosApp.xcodeproj`, pick an iPhone simulator, and press **Run**. The first build takes a few minutes because Gradle builds the Kotlin code.
3. Give the simulator a location: **Features → Location** in the Simulator app, or from a terminal:

```
xcrun simctl location booted set 30.2672,-97.7431
```

From a terminal instead of Xcode:

```
cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme Opaq -destination 'generic/platform=iOS Simulator' -derivedDataPath build/dd build
xcrun simctl install booted build/dd/Build/Products/Debug-iphonesimulator/Opaq.app
xcrun simctl launch booted app.opaq
```

Only `iosApp/project.yml` is edited by hand. After changing it, run `xcodegen` in `iosApp/` (`brew install xcodegen`) and commit the regenerated project too.

### Install on your own iPhone

A free Apple account is enough for your own phones:

1. Connect the iPhone by USB. On the phone, turn on **Settings → Privacy & Security → Developer Mode** (it restarts).
2. In Xcode, select the **Opaq** target → **Signing & Capabilities**, choose your personal team, and if Xcode says the bundle identifier is taken, change it to something unique such as `app.opaq.yourname`. Don't commit that change.
3. Pick the phone as the run destination and press **Run**. The first time, trust your developer certificate on the phone under **Settings → General → VPN & Device Management**.

With a free account the app stops opening after **7 days**; press Run again to renew it. Giving builds to other people needs TestFlight, which needs the paid Apple Developer Program.

### How iOS differs in the two-phone test

The steps in [section 3](#3-the-core-test-two-phones) are the same, with these differences:

| On iOS | What to expect |
|---|---|
| Location permission | Asks for **While Using the App** first. Once sharing starts, iOS offers **Change to Always Allow**: choose it, or sharing stops when the app is in the background. |
| Settings → Sharing | Shows **Allow all the time** with **Open** until Always is granted. |
| While sharing | The blue location indicator in the status bar takes the place of Android's "Sharing your location" notification. |
| Scanning an invite | **Scan QR code** asks for camera access the first time, then opens a full-screen scanner. The iPhone Camera app also works: it opens the invite in Opaq, as do shared `opaq://` links. The simulator has no camera, so there it says to paste the link instead. |
| Profile photo | Uses the system photo picker; no photo library permission is needed. |

Things to check on iOS in particular:

| Test | Expected |
|---|---|
| Swipe Opaq away in the app switcher, then move about 500 m | iOS relaunches it in the background and updates resume (needs Always) |
| Restart the iPhone and unlock it, then move | Same as above. Before the first unlock nothing is sent, because saved state can't be read yet |
| Leave it still for 10 minutes | Updates slow to about every 5 minutes, as on Android |

### Faking movement

- **Simulator:** **Features → Location → City Run** or **Freeway Drive** for continuous movement, or **Custom Location** for a fixed point.
- **Real iPhone:** while it runs from Xcode, use **Debug → Simulate Location**, or add a GPX file to the scheme.

### Watching what happens

Open the **Console** app on the Mac, select the phone or simulator, and search for `Opaq:`. Or for the simulator:

```
xcrun simctl spawn booted log stream --predicate 'eventMessage BEGINSWITH "Opaq:"'
```

As on Android, this shows relay status only, never locations or keys.

### Automated tests on iOS

```
./gradlew :shared:iosSimulatorArm64Test :ui:iosSimulatorArm64Test
```

These run the shared crypto, protocol and motion tests on iOS, plus the iOS state store tests.
