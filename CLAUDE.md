# Opaq: notes for Claude (and humans)

Opaq is an open-source, Life360-style location sharing app with **no accounts and no server of its own**. Phones exchange end-to-end encrypted updates through public [Nostr](https://nostr.com) relays. Android ships first; iOS reuses the shared Kotlin code.

This repo is **public**. Never commit secrets, real keys, keystores, personal emails or real location data. Commit as the GitHub no-reply address.

**Naming.** The app was called Quietmap until November 2026, and the repository is now `carterkeel/opaq`. Everything is named Opaq (packages `app.opaq`, iOS target, link scheme `opaq://`). The app id on Google Play and the App Store is **`me.opaq.app`** (Android `applicationId`, iOS bundle id in `iosApp/project.yml`; test builds add `.debug` or `.staging`). The rename and the move to this app id each needed a fresh install. A few things keep the old name on purpose, because they're part of the data format: the encryption labels `quietmap-pin|`, `quietmap-circle|` and `quietmap-invite|` (changing them would cut phones on the new app off from anyone still on the old one, and from circles restored from an old backup), and the old backup header `quietmap-backup-v2`, which still restores. Pasted `quietmap://` links are still recognised.

## Ground rules

These are product decisions, not style preferences. Don't weaken them without the owner (carterkeel) agreeing.

- **No backend.** Nothing the app needs may depend on a server we run. Public relays, OpenFreeMap tiles and a static page for invite links are the only outside services.
- **Never hand-roll crypto.** Primitives come from audited libraries: secp256k1-kmp, javax.crypto and BouncyCastle on Android, CommonCrypto and libsodium on iOS. NIP-44 and NIP-01 are implemented to spec and checked against the spec's test vectors in `CryptoTest`.
- **Relays must learn as little as possible.**
  - Location events are padded to one fixed size (`Envelopes.LOCATION_SIZE`, `Engine.MAX_LOCATION_CONTENT`).
  - They're signed with a per-hour publisher key and use per-hour `d` tags derived from the circle key.
  - Updates go out at only two steady rates (~10 s moving, ~300 s still, ±15% jitter), plus one extra update when movement starts or stops, one when sharing with a circle starts, one when the locate button finds the shared position was more than 25 m off, and one when someone in the circle asks for it (#149; only once their last update is 5 minutes old, at most one answer per person every 2 minutes, 20 an hour, and only while already sharing exactly with that circle; the person asked sees nothing but their location going out). The relay can see that a direct message was followed by an update; it can't read either.
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
  core/Grouping.kt          Which people's dots overlap at a zoom level, so the map draws them as one group.
  core/Places.kt            Alert areas: whether an update means someone arrived at or left a place.
  core/History.kt           On-device location history: encrypted per-person/day files, thinning,
                            pruning, and grouping into segments (still/walking/driving/paused).
  core/Diagnostics.kt       The on-device diagnostic log (#154): encrypted day files beside the history, sanitiser.
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
  PlacesScreens.kt Place alerts list and the add/edit screen (tap the map to set the place).
  DiagnosticsScreen.kt Settings → Diagnostic logs: keep on/off, latest events, export, clear.
  MapGroups.kt    Each person's face as a map icon (FacePainter), and how tightly a stack packs them.
  Everyone.kt     The "Everyone" view: all circles combined, display only.
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

site/        Static GitHub Pages site (pages.yml), no build step:
             index.html   the public website (three.js from cdnjs, fonts from Google Fonts; a CSP meta tag
                          allows only those). The demo map draws sf-map.json: downtown San Francisco
                          from OpenStreetMap (© OSM contributors, ODbL, credited on the page).
             report.html  "Report a problem" form (the app's Settings → Report a problem opens it; it has a
                          box for pasting the diagnostic log), posted with fetch to Web3Forms (api.web3forms.com),
                          which emails it to the owner. Its access key is in the page on purpose: Web3Forms
                          keys are public by design (limit it to the domain in the Web3Forms dashboard).
                          This is the website only; the app never contacts Web3Forms.
             privacy.html, terms.html  the privacy policy and terms of use (#159), linked from Settings
                          and the store listings. Static, no scripts.
             join.html    hands https invite links to the app. Styled like the rest of the site but loads
                          nothing from anywhere else (no web fonts), so opening an invite contacts no third party. Invite links point at
                          opaq.me/join (the Pages custom domain). Older links use
                          carterkeel.github.io/opaq/join, which Pages redirects there, keeping the # part.
docs/readme/ README images: brand wordmarks, the banner and framed screenshots from the iOS simulator
             (test people and locations only).
tools/       generate-app-icons.py: Android icon resources and AppIconArt.kt from the brand SVG geometry.
```

The iOS platform layer lives in `ui/src/iosMain/.../ios/` (Keychain state store, CoreLocation service, photo picker, `PlatformBridge`). The Xcode app shell is in `iosApp/` (project `iosApp.xcodeproj`, scheme `Opaq`; App Intents in `OpaqShortcuts.swift`; Control Center controls in the `OpaqControls` extension).

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
- The link is `opaq://join#<base64url JSON>`. Shared and copied links use `https://opaq.me/join#…` (`InviteLinks.webLink`; older `carterkeel.github.io/opaq/join` links still work), since chat apps only make http(s) links tappable; `site/join.html` (GitHub Pages, `pages.yml`) hands it to the app (on Android as `intent://join/<payload>#Intent;scheme=opaq;end`, since browsers can mangle a fragment on a custom-scheme link). The payload is never in the part a web host sees. `InviteLinks.decode` finds the invite in any of these forms, a bare payload or a whole pasted message; `InviteLinks.problem` says when a link was cut off at the `#`.
- The same link is shown as a QR code (qrose) and can be scanned in-app (zxing-android-embedded).
- Flow: the joiner sends `JoinRequestBody` as a sealed DM, the admin approves, the admin sends `RosterBody` (circle key and members) to every member, and the new member publishes their profile.

**Sealed DMs** (kind 1059). Signed by a throwaway key, `p`-tagged to the recipient, and NIP-44 encrypted to them. Inside is an `Envelope` whose body is signed by the sender's real key (`Envelopes.open` verifies it). They're used for join requests and refusals, rosters and removals, and location requests (`LocationRequestBody`, "Ask for their location" on a profile).

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
- Three activities: still (< 0.5 m/s), walking (running too, < 4 m/s, about 9 mph) and driving. There's no cycling: speed can't tell it from a run or slow traffic. `Activity.CYCLING` stays in the enum so older updates and history files still decode; it's shown and stored as driving.
- Classification uses 15% hysteresis.
- A drive is held through stops (#155, `MotionPolicy.settle`): once driving, slowing or stopping stays "Driving" (shown as "stopped" near zero speed) until 2 minutes of steady walking pace (`WALK_TAKEOVER_SECONDS`, parked and walked off) or 4 minutes below driving speed in all (`STOP_TAKEOVER_SECONDS`, parked and stayed). Times are on this phone's clock (`receivedAt`), not GPS time.
- A "motion hint" switches to GPS straight away. On Android it comes from the significant-motion sensor. On iOS it comes from Core Motion's activity reading (running, cycling or in a vehicle at once; walking only once it has lasted 30 s, so steps around the house don't turn GPS on; at least medium confidence; needs Motion & Fitness, `NSMotionUsageDescription`) and from leaving a `CLMonitor` geofence (100 m, or twice the fix's accuracy) placed where the phone went still. The older `startMonitoringForRegion` is on its way to deprecation, so don't use it.
- One speed reading never starts a drive: a lone fix counts only if it says still, and two or more use the lower median.
- `shouldPublish` publishes on the steady rate, or on a still↔moving transition at least 10 s after the last publish. The location services check every 2 s while moving or about to, and while still only when the next update is due (at most every 30 s, `MotionPolicy.nextCheckMillis`); a fresh fix or motion hint wakes them, so updates go out when they're due either way.
- The locate button (`PlatformBridge.refreshMyLocation`, `MotionPolicy.requestRefresh`) switches to GPS for a fresh fix. If the first fix accurate to 30 m within 30 s is more than 25 m (or its own error) from the last one shared, it's sent straight away; otherwise nothing extra goes out. The map follows you to the new position.
- Phones stop sending fixes when they stop moving (on iOS, still phones ask for 3 km accuracy, the cheapest there is, and those fixes only keep the app running: none is shared, and one whose error circle is clear of where the phone stopped counts as moving; a distance filter on CLLocationManager would leave a resting phone without fixes and iOS would then suspend the app), so the policy also goes by the clock. `refresh(now)` runs every tick: no fix for `QUIET_SECONDS` (60 s) counts as stopped. A still update carries the time it was sent, not the time of the last fix, so someone who stays put doesn't look stale and then offline. A fix that's already over `MAX_FIX_AGE` (2 min) old when it arrives (the OS's cached last position) is ignored.
- On the receiving side, `Format.status` shows a stale moving update as "Was driving".

**Between updates.** Display only, on the viewer's phone (`MapPane.rememberMovingMarkers`); nothing extra is sent or published.
- `Prediction.plan` carries someone travelling forward at their reported speed for `Prediction.HORIZON_SECONDS` (the moving interval plus its jitter plus 15 s, so it lasts until the next update is due), then eases to a stop. Only exact, accurate (≤ 50 m), moving updates; one older than `STALE_SECONDS` is shown where it really was.
- On a road it follows the road round its bends (`Roads`, in `Roads.kt`, built from the tiles on screen with `queryRenderedFeatures`, refreshed when the camera stops, zoom ≥ 12). It carries on into the next piece of road only where the road clearly goes straight on, and stops where the known road ends, forks or meets a T junction. A travelling person within 6 m (`SNAP_METERS`, about 20 ft) of a road running their way is drawn on it; drivers and cyclists up to 12 m off it follow it from where they really are. Off-road it goes straight along the heading, and only for 15 s on a winding path.
- A new update doesn't make the dot jump (`Track`): it travels from where it's drawn to the new estimate along the recent points (lined up with the road), at the person's speed plus enough to close the gap within 0.8–8 s, and waits rather than going back if it got ahead. Over 3 km (`Track.JUMP_METERS`) it's moved at once.

**Location history** (`LocationHistory`, `HistoryScreen`). Every phone records received (and its own) exact updates in encrypted files (`HistoryFiles`: `FileHistory` on Android in `noBackupFilesDir`, `IosHistoryFiles` on iOS). The key is `AppState.historyKeyHex` (never in backups). `AppState.historyDays` (default 7, max 90, 0 = off) is pruned hourly; history of people no longer in any circle is deleted. Keeping more needs the PIN. Opened from a profile. A pause is stored as one marker point (`HistoryPoint.p`) and shown as a "Sharing paused" stretch until the next update (#156). The drawn path is split wherever nothing was recorded for 3 minutes or the jump is faster than a car (`historyPieces`), and a moving stretch isn't joined to a stop before it across a pause, so no straight line is drawn across water or buildings. While driving, points keep the speed (`HistoryPoint.v`: the reported speed for the update, distance over time for its path points), and a drive shows its average and top speed. The drawn path is lined up with roads it runs close to (`RoadSnap`, 12 m walking, 25 m otherwise) using road shapes from the tiles already on screen (`queryRenderedFeatures`); stored points are never changed and nothing is sent to a map-matching service.

**Sharing button.** On the home map it's an icon: a blue location arrow tilted right while sharing, the same arrow in red with a line through it while paused (content description "Sharing" or "Paused"). After a change the pill widens to say so (a no-bounce spring) and goes back to the icon after 5 seconds.

**Pausing.** `AppState.sharing` pauses everything; `Circle.paused` pauses one circle (`ShareStatus.PAUSED`), and the circle still shows on the map. Either can be timed (`sharingResumeAt`, `Circle.pausedUntil`), and housekeeping ends it when it's over, the next time the app runs. Pausing sends each affected circle one last update with `status = MemberLocation.PAUSED` (last shared position, still, no speed or path), so they see "Sharing paused" instead of the person going offline. Resuming needs no PIN, like turning sharing back on.

**PIN and biometrics.** The optional PIN (`PinLock`, a PBKDF2 hash used only as a gate) is asked for before backups, invites, approvals, removals, relay and security changes, never to open the app; one entry lasts `Engine.AUTH_WINDOW_SECONDS`, except that a backup needs its own entry made just then (`Engine.FRESH_SECONDS`, `PinGate.requireNow`), and setting the first PIN outside setup needs the phone's own unlock first (`PlatformBridge.confirmPhoneOwner`: `PhoneLock.kt` on Android, `IosBiometrics.confirmOwner`), so someone holding an unlocked phone can't copy its identity. Once a PIN is set, Settings → Security (and the PIN step of setup) can turn on Face ID, Touch ID or fingerprint/face unlock in its place (`PinLock.biometrics`, `Engine.unlockWithBiometrics`, which still respects a PIN lockout). Biometrics only, never the phone's passcode. Android uses `BiometricPrompt` (strong, Android 11+) with a Keystore key that needs a biometric and is deleted when one is enrolled (`Biometrics.kt`); iOS records `evaluatedPolicyDomainState` (`IosBiometrics`). If the phone's faces or fingers change, it turns itself off and asks for the PIN. The PIN always works too.

**Shortcuts (iOS).** `iosApp/iosApp/OpaqShortcuts.swift` declares App Intents (start, pause for a while, toggle, pause or resume a circle, update my location) and Siri phrases; the work is in Kotlin (`OpaqShortcuts` in ui/src/iosMain). Control Center (iOS 18+) has a "Share My Location" toggle and an "Update My Location" button: the `OpaqControls` widget extension (`iosApp/OpaqControls/`) draws them, and their intents (`iosApp/Shared/OpaqControlIntents.swift`, in both targets) adopt `LiveActivityIntent` so iOS runs them in the app, where the Kotlin engine is. The toggle reads whether sharing is on from the App Group `group.me.opaq.app` (`OpaqSharedState`, the only value there), which the app keeps current (`OpaqShortcuts.watchSharing`). Every action needs the phone unlocked, starting sharing from a shortcut or control posts a notification, and no action or control returns anyone's location (a shortcut can pass its output anywhere, outside the encryption). Keep it that way.

**Relays.** `RelayPool` keeps one websocket per relay, pinged every minute, and retries a lost one after 2 s doubling up to 5 min, or up to an hour while other relays are connected (at once when the network comes back). A connection attempt gives up after 20 s (60 s through Tor), so a relay that never answers doesn't hold the radio up for a minute each time (#165). A relay that closes a subscription isn't asked for it again for 30 min if it wants sign-in or blocks us, 5 min otherwise; one that answers an update with rate-limited or banned gets no updates for 5 or 30 min (`RelayReplies`). Asking again straight away costs battery and gets the household's address banned. The sealed-DM filter starts on the hour a week back (`Engine.directSince`), so subscriptions only change when the hourly tags do.

**People close together** (#146). Display only. `Grouping.group` puts people whose dots would overlap (40 dp apart, regrouped every half zoom level) into one group, anchored on its first person (you first, then people online). People are drawn over the map in Compose (`PeopleOverlay`, placed with maplibre-compose's `placedAt`), not as map symbols: symbols faded and swapped order while zooming. Each stack is one piece placed at its first person's spot: faces (`FacePainter`) round a ring (`stackSlot`: two side by side, three a triangle, four a square; neighbours 26 dp apart so faces still overlap; front face on top), lifted by `stackLift` so they rest on their pointers. When someone joins or leaves a stack, their face glides from the old slot to the new over 400 ms, both worked out at the current zoom (`rememberStackedFaces`), and its pointer stretches with it. Faces take the place round the ring nearest where they really are (`ringPlaces`: the arrangement with the shortest total distance, kept unless a new one is clearly better), so pointers fanning out never cross. When the face carrying a pointer changes, the pointer slides across to the new face over 350 ms (`Handoff`) instead of jumping. People who appear on the map (switching to another circle or Everyone, or a first update) pop in one after another, west to east; people who leave it shrink away the same way. Past the world's top and bottom edge, `WorldEdges` fills the area MapLibre leaves blank with the map's own colour there. Faces take no touches (a pinch or drag that starts on one still moves the map); a tap is matched to the face under it in the map's own click callback (`FaceHits`). Each spot in a stack gets one white pointer whose tip is the spot (a small one for one person, a larger one for people together, several fanning out when a zoomed-out stack covers more than one area), and the faces and pointers share one soft shadow. Tapping a stack pops up a list of who's in it from the stack itself, like the long-press menu (`GroupBubble` in PersonMenu.kt): above the faces with an arrow pointing at them, the map first moving down if there isn't room under the top controls (`MapPane`'s stack tap), or below them if there's no room either way. Tapping a name zooms to them, the info button opens their profile, a long press gives the quick menu, and tapping elsewhere closes it. Stacks of two or more say "N people here" to screen readers. In both that list and the people list, a long press on someone pops up a quick menu just below their row, or above it near the bottom of the screen, with an arrow pointing at their picture (`PersonMenu`, with a long-press buzz): ask for their location, rename, directions, and for the admin remove from the circle (after a confirm sheet); tapping elsewhere closes it.

**Everyone** (#147). With two or more circles the circle picker offers Everyone (`EVERYONE`): each person once, at their newest location from any circle, with profiles opening in the circle they were last seen in. It's a view only; invites, pausing and per-circle banners stay with each circle.

**Directions** (#150). A profile offers directions to the person's last shared spot through `PlatformBridge.openDirections`: Google Maps or any map app on Android, Apple Maps or Google Maps (`comgooglemaps://`, listed in `LSApplicationQueriesSchemes`) on iOS. The chosen map app sees the destination; nothing else is sent.

**Place alerts** (#151, plan in #101). On the add/edit screen the map moves freely and a tap sets the place; the area glides to it and the centre dot pops (450 ms). The hint stays pinned under the map, with a fade below it for the settings scrolling underneath. `AppState.places` (at most 20, radius 30 m to 5 km, one circle or all) and `placeInside` (who's inside now, so restarts don't re-announce). `Engine.checkPlaces` runs on each received update: only exact, fresh (under 10 min), accurate updates count; leaving needs 25 m (or the accuracy) past the edge; passing through between updates counts as arriving; one alert per person, place and change every 10 minutes; never about yourself. Alerts go out as `Notice`s with `kind = PLACE` (Android channel "Place alerts"). Nothing is published and places are never logged. They're in backups (`BackupData.places`; who's inside isn't), and a circle's places go when the circle does. Enabled places are drawn faintly on the maker's home map.

**Approximate sharing** (#157). A circle can get only a neighbourhood (0.02° cell, within about 1 mi) or city (0.2°, about 10 mi) position (`LocationPrecision`). Viewers see a dashed circle of that size round the dot in the person's colour (`approximateAreasToGeoJson`), "Approximate (about 1 mi)" in the list (`Format.approximate`) and a sentence on the profile.

**Diagnostic logs** (#154, `DiagnosticLog`, Settings → Diagnostic logs). Events and times only: app start (and how, on iOS), the sharing service starting and stopping and why, permissions, GPS/coarse switches, battery bands and power saving, publish outcomes (logged when they change, else every 30 min), `shareStatus` changes per circle ("circle 2", never ids or names), relay connect/drop/refusals (host names only), pauses, and crashes with stack traces. `Engine.diagnostic` adds a line; `DiagnosticLog.clean` blanks anything like a coordinate, a hex key or encoded data before it's stored. Lines are NIP-44 encrypted under the history key in `d-<day>` files beside the history (`h-` files are the history's; neither prunes the other), kept 7 days, at most 3000 lines a day. On by default (`AppState.keepDiagnostics`); turning it off deletes it. "Export logs" opens the share sheet with plain text. On iOS, `IosMetrics` adds Apple's MetricKit report about once a day: background, CPU, location and network use, and why iOS ended the app in the background. Never pass a location, key, name or message to `diagnostic`.

**Legal pages** (#159). opaq.me/privacy and opaq.me/terms (`PRIVACY_URL`, `TERMS_URL`), opened from Settings with `PlatformBridge.openUrl`, as is the website's report form (`REPORT_URL`). The app never contacts Web3Forms itself.

**Offline.** No update for 45 min (`Engine.OFFLINE_AFTER_SECONDS`) shows the person as offline. The app can't tell a dead phone from no signal, by design.

**Status rings and battery.** Every face in the people list, the group list and on the map has a ring (`Format.ring`, `ringColor`): accent blue for an exact location, green for neighbourhood or city, grey when offline, paused or without a location. Your own follows the circle's setting. Battery is a `BatteryBadge` beside the status, filled to its level (white on dark screens, mid grey on light ones, red at 20% or below) with the drained part a quieter grey and the number always dark (`StatusLine`, `Format.battery`); `Format.status` leaves it out of the text.

**First run.** `SetupScreen` walks through name, photo (optional), a PIN (optional), creating or joining a circle, and location access, then sets `AppState.setupDone`. The location step fits without scrolling, buttons pinned to the bottom: one grouped checklist (#158) of short rows (icon, a few words from `SettingItem.brief`, a small button or a tick) for location access, the platform's required background rows, and sharing on. Whether setup is needed is saved with the screen (`rememberSaveable`), so turning the phone on the last step doesn't skip it. The background rows come from the same `sharingSettings` as Settings → Sharing (on Android "Allow all the time" and turning off the battery saver for Opaq; optional switches stay in Settings), each with its button (#131). Installs that already had circles skip it. The app reopens on `AppState.lastCircleId`. The map opens at city level around you once; switching circles or to Everyone keeps it where it is, as zoomed in as it was.

## Building and testing

Requires JDK 17 and the Android SDK.

```
./gradlew :shared:testAndroidHostTest        # crypto, protocol and motion tests
./gradlew :androidApp:assembleDebug          # debug APK (slow Compose: don't judge smoothness on it)
./gradlew :androidApp:assembleStaging        # R8-optimised, debug-signed: judge performance here
```

**CI** (`.github/workflows/android.yml`) runs the tests and compiles the app (`:androidApp:compileDebugSources`) on every push. The APKs and the emulator test only run when the workflow is started by hand (Actions → Android → Run workflow, any branch); pushes, `main` included, only test and compile. The Play bundle is also built only by hand (below). Those runs upload `opaq-debug-apk` and `opaq-staging-apk`. Every run writes a plain-text summary of each run to the **`ci-logs` branch** (`log.txt`, force-pushed, latest run only). Claude sessions without Maven access read build errors from that branch:

```
gh api repos/carterkeel/opaq/commits/ci-logs --jq .commit.message
gh api repos/carterkeel/opaq/contents/log.txt?ref=ci-logs --jq .content | base64 -d
```

**Play bundle.** The signed release bundle for Google Play is built by hand: Actions → Play bundle → Run workflow (`.github/workflows/play-bundle.yml`), with a version name and optionally a version code (default: that workflow's run number + 100; it must be higher than any bundle uploaded before). It signs with the upload key from four repository secrets (`OPAQ_UPLOAD_KEYSTORE_B64`, `OPAQ_UPLOAD_STORE_PASSWORD`, `OPAQ_UPLOAD_KEY_ALIAS`, `OPAQ_UPLOAD_KEY_PASSWORD`), never in the repo. The release build type is R8-optimised like staging; its artifact holds the `.aab` and R8's `mapping.txt`.

**Store screenshots.** Actions → Store screenshots → Run workflow (`.github/workflows/store-screenshots.yml`, `ci/store-screenshots.sh`) takes the Google Play screenshots on an emulator, as the `phone` set (1080 × 1920) or the `foldable` set (unfolded 1:1, 4:3 and 3:4, outer screen 10:16, set with `wm size`); the `group` set taps a stack on the map, the `setup` set walks through first-run setup on a 16:9 and a 20:9 phone and photographs the location step, and the `video` set records the background location demo: `androidTest/StoreScreenshots.kt` writes a made-up circle (invented people in San Francisco, letter avatars, one drive in history) into the debug app's saved state, then photographs the map, a profile, history, an invite, and in dark mode the map, the people list, history and a place alert. The app is pointed at a relay address nothing answers, so nothing is sent. The PNGs are uploaded as `opaq-store-screenshots-<set>` and pushed to the `store-screenshots-<set>` branch (latest run only). The test is skipped unless run with `-e storeScreenshots 1`, so the normal emulator test never runs it.

Two-phone manual testing is described in `TESTING.md`. Simulators and emulators should use the local test relay (`tools/test-relay.sh`, `nak serve` on port 7777) rather than public relays: debug builds read it from the `opaqTestRelay` user default (iOS) or `files/test-relay` (Android) and then use only that relay (`Engine.testRelays`; plain `ws://` is allowed only to localhost and the emulator's 10.0.2.2, and only in debug builds).

## Toolchain gotchas

- **AGP 9 with its current setup, no compatibility switches.** The app module uses AGP's built-in Kotlin (no `org.jetbrains.kotlin.android`); `shared` and `ui` use `com.android.kotlin.multiplatform.library`, configured in `kotlin { android { … } }`. Host (JVM) tests live in the `androidHostTest` source set and run with `:shared:testAndroidHostTest`. compileSdk is 37 (required by Compose Multiplatform 1.12 and lifecycle 2.11).
- **No deprecated libraries.** The Material icons library is frozen, so icons are drawn in `AppIcons` (Components.kt); add new ones there. GitHub Actions use their Node 24 releases.
- **Kotlin 2.4.x.** Dependencies built with a newer Kotlin may not be readable. See the libsodium comment in `libs.versions.toml`.
- **CI runs steps with `shell: bash`** so that `| tee` doesn't hide a failing Gradle command (pipefail). Keep it.
- **iOS targets can't build on Linux.** `kotlin.native.ignoreDisabledTargets=true` silences that. iOS `actual`s (`*.ios.kt`) are only compiled on macOS, so double-check them by hand.
- **CI test builds share one signing key** (cached in GitHub Actions as `opaq-ci-signing-v1`, never committed) and use the run number as `versionCode`, so a new APK installs over the previous one. APKs are built only on manual runs; a run on another branch can read `main`'s cache, so run one on `main` first and they all share its key.
- **The `staging` build uses R8.** Anything reached through JNI or reflection (secp256k1, MapLibre, kotlinx.serialization serializers, the zxing scanner) needs a rule in `androidApp/proguard-rules.pro`.
- **Compose is much slower in debug builds.** Judge smoothness on the staging APK.
- **maplibre-compose needs a renderer runtime on Android.** It only ships the JNI bridge; `libmaplibre-native-c.so` comes from `org.maplibre.nativeffi:maplibre-native-ffi-runtime-opengl` (in `ui` androidMain). Its version must match the `maplibre-native-ffi` version maplibre-compose pulls in. Without it the app crashes as soon as the map opens.
- **CI runs a UI smoke test on an emulator** (`androidApp/src/androidTest`, `ci/emulator-test.sh`): go through setup (with a PIN), create a circle, let the map and sharing service run, toggle sharing, open the invite screen, settings, your profile and location history, then repeat the main screens in dark mode. Crash stack traces from logcat and a screenshot of each screen land in the `ci-logs` branch. Extend this test when you add a screen.

## Conventions

- Branch from `main`, open a PR, and keep `main` releasable. Delete a branch once its PR is merged.
- UI changes go in `ui/` (shared), not `androidApp/`, so iOS gets them too. Android-only APIs belong behind `PlatformBridge` or an `expect`/`actual`.
- New persisted fields go in `AppState` **with defaults**, so older saved state still loads.
- New protocol messages: add a `Body` subclass with a `@SerialName`, keep old ones decodable, and add a round-trip test in `ProtocolTest`.
- User-facing text: plain and short, and say what happens ("Stop sharing with everyone", not "Disable").
- Look: Signal-like. White or near-black surfaces, one ultramarine accent, the system font, pill buttons, 24 dp gutters, and settings rows with leading icons. Screens slide in from the side their button is on (Settings and sub-screens from the end edge, screens opened from the people sheet from the bottom; `Route.edge()` in App.kt) with a short tween, no fade, nothing bouncy. Brand colours: Ink `#0F172A` (`Palette.Ink`) for dark text and the dark theme's background; the accent stays ultramarine. App icons (brand pack, Nov 2026; the A is a lock that is also a map pin): Dark A on white by default; Two-tone and Oversized A from Settings → Appearance → App icon (Android activity-aliases `.Icon*`, iOS alternate icon sets). The theme follows the phone unless set to Light, Dark or Midnight (dark with a black background) in Settings → Appearance (`AppState.themeMode`); check `LocalDarkTheme.current`, never `isSystemInDarkTheme()`, and `SystemBarsTheme` keeps the status bar icons readable. A soft fade at the top of the map keeps the status bar readable, and the same fade at the bottom edge fades out people-list rows that run off the screen.
