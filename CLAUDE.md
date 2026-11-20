# Opaq: notes for Claude (and humans)

Opaq is an open-source, Life360-style location sharing app with **no accounts and no server of its own**. Phones exchange end-to-end encrypted updates through public [Nostr](https://nostr.com) relays. Android ships first; iOS reuses the shared Kotlin code.

This repo is **public**. Never commit secrets, real keys, keystores, personal emails or real location data. Commit as the GitHub no-reply address.

**Naming.** The app was called Quietmap until November 2026, and the repository is now `carterkeel/opaq`. Everything is named Opaq (app id `app.opaq`, packages, iOS bundle and target, link scheme `opaq://`), so the rename needed a fresh install. A few things keep the old name on purpose, because they're part of the data format: the encryption labels `quietmap-pin|`, `quietmap-circle|` and `quietmap-invite|` (changing them would cut phones on the new app off from anyone still on the old one, and from circles restored from an old backup), and the old backup header `quietmap-backup-v2`, which still restores. Pasted `quietmap://` links are still recognised.

## Ground rules

These are product decisions, not style preferences. Don't weaken them without the owner (carterkeel) agreeing.

- **No backend.** Nothing the app needs may depend on a server we run. Public relays, OpenFreeMap tiles and a static page for invite links are the only outside services.
- **Never hand-roll crypto.** Primitives come from audited libraries: secp256k1-kmp, javax.crypto and BouncyCastle on Android, CommonCrypto and libsodium on iOS. NIP-44 and NIP-01 are implemented to spec and checked against the spec's test vectors in `CryptoTest`.
- **Relays must learn as little as possible.**
  - Location events are padded to one fixed size (`Envelopes.LOCATION_SIZE`, `Engine.MAX_LOCATION_CONTENT`).
  - They're signed with a per-hour publisher key and use per-hour `d` tags derived from the circle key.
  - Updates go out at only two steady rates (~30 s moving, ~300 s still, ±15% jitter), plus one extra update when movement starts or stops.
  - Activity, speed, battery, names and photos travel only inside the encryption.
- **Never log locations, keys or message contents.** `Log.d("Opaq", …)` is for relay status only.
- **Keys stay on the device.** State is encrypted with an Android Keystore key and excluded from backups (`data_extraction_rules.xml`, `allowBackup=false`).
- **Consent and anti-stalkerware.** Joining needs an explicit tap, the admin approves every join, and Android shows a persistent "Sharing with N people" notification. Keep all of these.

## Layout

```
shared/      Kotlin Multiplatform core. No UI. Android + iOS targets.
  core/Engine.kt            The whole protocol: state, circles, invites, key rotation, profiles,
                            publishing, handling incoming events, relay subscriptions.
  core/MotionPolicy.kt      When to publish and which activity to report (still/walking/...).
  core/Prediction.kt        Carrying a moving person's dot forward between updates (viewer side).
  core/RoadSnap.kt          Lines a drawn history path up with nearby roads (display only).
  core/History.kt           On-device location history: encrypted per-person/day files, thinning,
                            pruning, and grouping into segments (still/walking/driving).
  core/model/Models.kt      AppState (everything persisted) and data classes.
  core/protocol/Protocol.kt Message bodies, sealed DMs, circle crypto (tags, publisher keys),
                            profiles, device codes, invite links.
  core/protocol/Backup.kt   Passphrase-encrypted backup files.
  core/nostr/               NIP-01 events, relay protocol, RelayTransport (interface; tests use an
                            in-memory network), RelayPool (Ktor websockets, optional Tor SOCKS).
  core/crypto/              PlatformCrypto (expect/actual), Keys (secp256k1), Nip44, encoding.
  commonTest/               Crypto, protocol and motion tests (run on the JVM in CI).

ui/          Compose Multiplatform screens shared by Android and iOS.
  App.kt          OpaqScreens(): navigation. Home is always composed; other screens
                  slide over it (AnimatedContent), so the map never reloads.
  HomeScreen.kt   Map + draggable people sheet (BottomSheetScaffold), circle picker, welcome.
  SetupScreen.kt  First-run walkthrough.
  DetailScreens.kt Profile, EditProfile (photo/colour avatar), Add (QR invite), Join, Settings.
  SecurityScreens.kt Recovery (unreadable state), PIN prompt and fields, security log, circle info,
                  sharing reminder.
  HistoryScreen.kt Location history map and segments, history-length picker.
  Avatars.kt      Avatar colours, photo decoding cache, verified badge.
  Components.kt   Signal-style rows, buttons, top bar, custom icons (AppIcons).
  Theme.kt        Colours, type scale (system font), shapes.
  MapPane.kt      maplibre-compose map with OpenFreeMap tiles.
  Platform.kt     PlatformBridge (toast, clipboard, share, photo picker, QR scanner) + expects.
  AppIconArt.kt   The four app icons for previews (generated by tools/generate-app-icons.py).

androidApp/  Android host app.
  OpaqApp.kt     Application: creates the Engine, notification channel.
  MainActivity.kt    Permissions, photo picker and QR scanner launchers, hosts OpaqScreens.
  AndroidPlatform.kt PlatformBridge implementation.
  LocationService.kt Foreground service: GPS/network location, significant-motion sensor,
                     publish loop driven by MotionPolicy.
  SecureStore.kt     Keystore-encrypted state file.
  FileHistory.kt     Location history files in noBackupFilesDir (contents encrypted by LocationHistory).
  PhotoProcessor.kt  Crops/compresses profile photos to a small JPEG.
  BootReceiver.kt    Resumes sharing after reboot or update.

site/        Static GitHub Pages site (pages.yml): join.html hands https invite links to the app.
tools/       generate-app-icons.py: Android icon resources and AppIconArt.kt from the brand SVG geometry.
```

The iOS platform layer lives in `ui/src/iosMain/.../ios/` (Keychain state store, CoreLocation service, photo picker, `PlatformBridge`). The Xcode app shell is in progress (#13, PR #19).

## How it works

**Identity.** On first launch the phone generates a secp256k1 keypair (`KeyPair.generate`). The **device code** is the first 8 bytes of SHA-256(pubkey), shown as `7F3A 91C2 D04E 6B1A` (`DeviceCode`). People verify each other by comparing codes; verified contacts get a green check on their avatar.

**Circles.** A circle is an id, a 32-byte circle key, an epoch number, an admin pubkey, a member list and a list of managers.
- The **admin** invites, approves, removes (which rotates the key), chooses managers, hands the admin role to someone else (`transferAdmin`) and can delete the circle (`deleteCircle`, a `RemovedBody` with `deleted = true` to everyone).
- **Managers** can invite and approve. Their rosters are accepted only under the current key and only add people.
- Rosters under the same key are **merged**, so an admin and a manager approving at the same moment don't drop anyone. Only the admin's rosters can change the key, the admin or the managers.
- **Leaving** sends a `LeftBody` to the admin, whose app removes the member automatically (new key). An admin has to hand over the role or delete the circle before leaving.
- Only one person rotates keys, which avoids conflicting epochs. Moving to MLS later would remove that limit.

**Invites.**
- `Engine.createInvite` makes a secret that expires in 1 h by default (24 h optional). It works once, or for up to `Invite.MAX_USES` (10) people if "Let more than one person use this link" is on; the inviter still approves every join. `Engine.cancelInvite` stops it early. Links are signed by the inviter (v2, `InviteLinks.signed`); unsigned v1 links are refused.
- A request that can't go ahead (used up, expired, cancelled, or denied with `Engine.deny`) gets a `JoinRefusedBody` reply, so the joiner stops waiting and sees why (`AppState.joinRefusals`). The joiner accepts it only from the inviter they asked, naming them and that invite. Replies are rate-limited (one per person per 10 min, 20 an hour in all). The inviter listens on an invite's inbox until it's pruned, a day after it expires.
- The join screen shows the inviter's device code: "The codes match" marks them verified, "Join without checking" joins unverified (verify later from their profile). Scanning the QR in the app verifies automatically. A new circle stays unconfirmed, sharing nothing, until the joiner taps "Start sharing".
- The link is `opaq://join#<base64url JSON>`. Shared and copied links use `https://carterkeel.github.io/opaq/join#…` (`InviteLinks.webLink`), since chat apps only make http(s) links tappable; `site/join.html` (GitHub Pages, `pages.yml`) hands it to the app (on Android as `intent://join/<payload>#Intent;scheme=opaq;end`, since browsers can mangle a fragment on a custom-scheme link). The payload is never in the part a web host sees. `InviteLinks.decode` finds the invite in any of these forms, a bare payload or a whole pasted message; `InviteLinks.problem` says when a link was cut off at the `#`.
- The same link is shown as a QR code (qrose) and can be scanned in-app (zxing-android-embedded).
- Flow: the joiner sends `JoinRequestBody` as a sealed DM, the admin approves, the admin sends `RosterBody` (circle key and members) to every member, and the new member publishes their profile.

**Sealed DMs** (kind 1059). Signed by a throwaway key, `p`-tagged to the recipient, and NIP-44 encrypted to them. Inside is an `Envelope` whose body is signed by the sender's real key (`Envelopes.open` verifies it). They're used for join requests and refusals, rosters and removals.

**Location updates** (kind 30078, addressable).
- Content is NIP-44 encrypted under a conversation key derived from the circle key and epoch.
- The `d` tag is `HMAC(circleKey, "qm-tag|epoch|hour")`.
- The signer is `HMAC(circleKey, "qm-pub|member|hour")`, so relays keep only the newest update per member per hour and can't link hours together.
- Receivers check that the outer signer matches the derived key for the claimed sender (`handleCircleUpdate`).
- Each update carries up to 6 recent points for path replay. The oldest points are dropped if needed to keep the fixed size.

**Profiles** (kind 30078). `ProfileBody` holds the name, avatar colour index and an optional photo (base64 JPEG, at most `CircleCrypto.MAX_PHOTO_CHARS`). It uses one tag and one publisher key per circle epoch, and is republished when the profile changes, when circle membership changes, and daily.

**Backups.** `Backups` (in `protocol/Backup.kt`) exports identity, profile, settings and circles as a text file (`opaq-backup-v2`; files headed `quietmap-backup-v2` from before the rename still restore): Argon2id (64 MiB, 3 passes, one lane, 16-byte salt) turns the passphrase into a key for NIP-44. v1 files (PBKDF2-HMAC-SHA256, 600k iterations) still restore. Restoring replaces the identity and starts with sharing paused. Others' locations and photos aren't included; they come back from relays.

**Removing someone.** The admin makes a new key (epoch + 1) and sends it only to remaining members. The old key stays accepted for 10 minutes (`OVERLAP_SECONDS`), and the removed person gets a `RemovedBody`.

**Circle protocol v2.** New circles use it, and an admin's app upgrades v1 circles by rotating. The sections above describe v1; in short, v2 differs like this (details and tests in `SECURITY_IMPLEMENTATION_LOG.md`):
- The location key ratchets forward every UTC day (`CircleCrypto.keyForDay`), and the admin rotates to fresh random keys on removals and weekly.
- A per-epoch **control key** covers profiles, heartbeats and rotating member inbox tags (`CircleCrypto.inbox`), so relays don't see permanent public keys once someone has joined.
- Publisher keys come from each member's own identity secret (`CircleCrypto.memberPublisher`), and every update carries a sequence number, so members can't overwrite or replay each other's updates.
- Rosters (`roster2`) must be acknowledged (`RosterAckBody`) and are resent until they are. A rotation publishes a closed notice for the old epoch, and the admin publishes a heartbeat every 6 h; members stop publishing on a closed or replaced key, or when the newest heartbeat is over 3 days old (`Engine.shareStatus`).

**Motion** (`MotionPolicy`).
- Speed comes only from fixes accurate to 30 m: the median of reported GPS speeds over 30 s, otherwise distance over time. Coarse network fixes count as movement only for a clear jump.
- Classification uses 15% hysteresis.
- A "motion hint" switches to GPS straight away. On Android it comes from the significant-motion sensor. On iOS it comes from Core Motion's activity reading (walking, running, cycling or in a vehicle, at least medium confidence; needs Motion & Fitness, `NSMotionUsageDescription`) and from leaving a `CLMonitor` geofence (100 m, or twice the fix's accuracy) placed where the phone went still. The older `startMonitoringForRegion` is on its way to deprecation, so don't use it.
- One speed reading never starts a drive: a lone fix counts only if it says still, and two or more use the lower median.
- `shouldPublish` publishes on the steady rate, or on a still↔moving transition at least 20 s after the last publish.
- Phones stop sending fixes when they stop moving (iOS's 25 m distance filter while still), so the policy also goes by the clock. `refresh(now)` runs every tick: no fix for `QUIET_SECONDS` (60 s) counts as stopped. A still update carries the time it was sent, not the time of the last fix, so someone who stays put doesn't look stale and then offline. A fix that's already over `MAX_FIX_AGE` (2 min) old when it arrives (the OS's cached last position) is ignored.
- On the receiving side, `Format.status` shows a stale moving update as "Was driving".

**Between updates.** Display only, on the viewer's phone (`MapPane.rememberMovingMarkers`); nothing extra is sent or published.
- `Prediction.plan` carries someone travelling forward at their reported speed for `Prediction.HORIZON_SECONDS` (the moving interval plus its jitter plus 15 s, so it lasts until the next update is due), then eases to a stop. Only exact, accurate (≤ 50 m), moving updates; one older than `STALE_SECONDS` is shown where it really was.
- On a road it follows the road round its bends (`Roads`, in `Roads.kt`, built from the tiles on screen with `queryRenderedFeatures`, refreshed when the camera stops, zoom ≥ 12). It carries on into the next piece of road only where the road clearly goes straight on, and stops where the known road ends, forks or meets a T junction. A travelling person within 6 m (`SNAP_METERS`, about 20 ft) of a road running their way is drawn on it; drivers and cyclists up to 12 m off it follow it from where they really are. Off-road it goes straight along the heading, and only for 15 s on a winding path.
- A new update doesn't make the dot jump (`Track`): it travels from where it's drawn to the new estimate along the recent points (lined up with the road), at the person's speed plus enough to close the gap within 0.8–8 s, and waits rather than going back if it got ahead. Over 3 km (`Track.JUMP_METERS`) it's moved at once.

**Location history** (`LocationHistory`, `HistoryScreen`). Every phone records received (and its own) exact updates in encrypted files (`HistoryFiles`: `FileHistory` on Android in `noBackupFilesDir`, `IosHistoryFiles` on iOS). The key is `AppState.historyKeyHex` (never in backups). `AppState.historyDays` (default 7, max 90, 0 = off) is pruned hourly; history of people no longer in any circle is deleted. Keeping more needs the PIN. Opened from a profile. The drawn path is lined up with roads it runs close to (`RoadSnap`, 12 m walking, 25 m otherwise) using road shapes from the tiles already on screen (`queryRenderedFeatures`); stored points are never changed and nothing is sent to a map-matching service.

**Offline.** No update for 45 min (`Engine.OFFLINE_AFTER_SECONDS`) shows the person as offline. The app can't tell a dead phone from no signal, by design.

**First run.** `SetupScreen` walks through name, photo (optional), a PIN (optional), creating or joining a circle, and location access, then sets `AppState.setupDone`. Installs that already had circles skip it. The app reopens on `AppState.lastCircleId`.

## Building and testing

Requires JDK 17 and the Android SDK.

```
./gradlew :shared:testAndroidHostTest        # crypto, protocol and motion tests
./gradlew :androidApp:assembleDebug          # debug APK (slow Compose: don't judge smoothness on it)
./gradlew :androidApp:assembleStaging        # R8-optimised, debug-signed: judge performance here
```

**CI** (`.github/workflows/android.yml`) runs the tests and both APK builds on every push. It uploads `opaq-debug-apk` and `opaq-staging-apk`, and writes a plain-text summary of each run to the **`ci-logs` branch** (`log.txt`, force-pushed, latest run only). Claude sessions without Maven access read build errors from that branch:

```
gh api repos/carterkeel/opaq/commits/ci-logs --jq .commit.message
gh api repos/carterkeel/opaq/contents/log.txt?ref=ci-logs --jq .content | base64 -d
```

Two-phone manual testing is described in `TESTING.md`.

## Toolchain gotchas

- **AGP 9 with its current setup, no compatibility switches.** The app module uses AGP's built-in Kotlin (no `org.jetbrains.kotlin.android`); `shared` and `ui` use `com.android.kotlin.multiplatform.library`, configured in `kotlin { android { … } }`. Host (JVM) tests live in the `androidHostTest` source set and run with `:shared:testAndroidHostTest`. compileSdk is 37 (required by Compose Multiplatform 1.12 and lifecycle 2.11).
- **No deprecated libraries.** The Material icons library is frozen, so icons are drawn in `AppIcons` (Components.kt); add new ones there. GitHub Actions use their Node 24 releases.
- **Kotlin 2.4.x.** Dependencies built with a newer Kotlin may not be readable. See the libsodium comment in `libs.versions.toml`.
- **CI runs steps with `shell: bash`** so that `| tee` doesn't hide a failing Gradle command (pipefail). Keep it.
- **iOS targets can't build on Linux.** `kotlin.native.ignoreDisabledTargets=true` silences that. iOS `actual`s (`*.ios.kt`) are only compiled on macOS, so double-check them by hand.
- **CI test builds share one signing key** (cached in GitHub Actions as `opaq-ci-signing-v1`, never committed) and use the run number as `versionCode`, so a new APK installs over the previous one. Caches are per branch (branches can read `main`'s), so the first build on a new branch may get its own key until `main` has one.
- **The `staging` build uses R8.** Anything reached through JNI or reflection (secp256k1, MapLibre, kotlinx.serialization serializers, the zxing scanner) needs a rule in `androidApp/proguard-rules.pro`.
- **Compose is much slower in debug builds.** Judge smoothness on the staging APK.
- **maplibre-compose needs a renderer runtime on Android.** It only ships the JNI bridge; `libmaplibre-native-c.so` comes from `org.maplibre.nativeffi:maplibre-native-ffi-runtime-opengl` (in `ui` androidMain). Its version must match the `maplibre-native-ffi` version maplibre-compose pulls in. Without it the app crashes as soon as the map opens.
- **CI runs a UI smoke test on an emulator** (`androidApp/src/androidTest`, `ci/emulator-test.sh`): go through setup (with a PIN), create a circle, let the map and sharing service run, toggle sharing, open the invite screen, settings, your profile and location history, then repeat the main screens in dark mode. Crash stack traces from logcat and a screenshot of each screen land in the `ci-logs` branch. Extend this test when you add a screen.

## Conventions

- Branch from the branch you're changing, open a PR, and keep `main` releasable. `ios-app` holds the multiplatform work in progress.
- UI changes go in `ui/` (shared), not `androidApp/`, so iOS gets them too. Android-only APIs belong behind `PlatformBridge` or an `expect`/`actual`.
- New persisted fields go in `AppState` **with defaults**, so older saved state still loads.
- New protocol messages: add a `Body` subclass with a `@SerialName`, keep old ones decodable, and add a round-trip test in `ProtocolTest`.
- User-facing text: plain and short, and say what happens ("Stop sharing with everyone", not "Disable").
- Look: Signal-like. White or near-black surfaces, one ultramarine accent, the system font, pill buttons, 24 dp gutters, and settings rows with leading icons. Screens slide in from the side their button is on (Settings and sub-screens from the end edge, screens opened from the people sheet from the bottom; `Route.edge()` in App.kt) with a short tween, no fade, nothing bouncy. Brand colours: Ink `#0F172A` (`Palette.Ink`) for dark text and the dark theme's background; the accent stays ultramarine. App icons (brand pack, Nov 2026; the A is a lock that is also a map pin): Dark A on white by default; Two-tone and Oversized A from Settings → Appearance → App icon (Android activity-aliases `.Icon*`, iOS alternate icon sets). The theme follows the phone unless set to Light, Dark or Midnight (dark with a black background) in Settings → Appearance (`AppState.themeMode`); check `LocalDarkTheme.current`, never `isSystemInDarkTheme()`, and `SystemBarsTheme` keeps the status bar icons readable. A soft fade at the top of the map keeps the status bar readable, and the same fade at the bottom edge fades out people-list rows that run off the screen.
