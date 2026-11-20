# Opaq

Private location sharing with the people you choose. No accounts, no phone numbers, and no Opaq server.

- **Identity is a key on your phone.** On first launch the app creates a secp256k1 keypair. Your *device code* (for example `7F3A 91C2 D04E 6B1A`) is a fingerprint of the public key; compare it in person to verify someone.
- **Circles share both ways**, like Life360. The circle admin sends a signed, single-use invite link that expires in an hour (or 24 hours), and approves each person who opens it.
- **Everything is end-to-end encrypted** on the device before it leaves. Updates travel through public [Nostr](https://nostr.com) relays, which only ever see ciphertext.
- **Open source.** Anyone can check what the app sends.

Runs on Android 8.0+ and iOS 17+ (iPhone and iPad) from one shared Kotlin Multiplatform codebase. Still in early development.

<p align="center">
  <img src="docs/screenshots/phone.png" alt="Map with the people list on a phone" height="420">
  &nbsp;
  <img src="docs/screenshots/foldable.png" alt="Unfolded foldable held sideways, with the people list in a side panel" height="420">
</p>
<p align="center">
  <img src="docs/screenshots/tablet.png" alt="iPad in landscape, map with a side panel" width="640">
</p>

## Features

- **Live map.** Everyone in the circle as a dot with their photo or colour, plus a people list with what they're doing ("Walking", "Driving", "Still") and battery level if they share it. Tap a dot or a row to open their profile.
- **Smooth movement.** Between updates, a moving person's dot carries on along the road they're on, so it doesn't jump every 30 seconds. This is worked out on your phone; nothing extra is sent.
- **Location history.** Each phone keeps the updates it receives in encrypted files on the device (7 days by default, up to 90, or off). Open it from a profile to see the day's path and stops.
- **Circles.** Invite with a link or QR code, scan codes in the app, let a link be used by up to 10 people, and approve every join. Make people managers, hand over the admin role, rename, remove someone (which changes the key), or delete the circle.
- **Verification.** Compare device codes, or scan the QR in person; verified contacts get a green check.
- **Your phone, your keys.** Optional PIN, a security log of PIN changes, lockouts and backups, and a passphrase-encrypted backup file (Argon2id) you can restore on a new phone.
- **Privacy options.** Connect through Tor (with Orbot), choose your own relays, hide the app in recent apps, turn off profile photos or battery sharing.
- **Every screen size.** Phones get a draggable people sheet under the map. Turned sideways, on an unfolded foldable or on a tablet, the people list moves to a side panel you can collapse. Light and dark themes, with an optional dark map.

## How it works

| Piece | What it does |
|---|---|
| Location updates | Encrypted once per circle (NIP-44 v2 with a circle key that ratchets forward daily and is replaced weekly), padded to a fixed size, and published as a replaceable Nostr event. Relays keep only the newest one. |
| Unlinkability | The signing key and the event tag both change every hour, and come from secrets the relay doesn't have, so relays can't tie a member's updates together across hours. Each update is also signed inside the encryption by the sender's real key, so circle members know who sent it. |
| Send rhythm | Two fixed rates with jitter: about every 30 seconds while moving, about every five minutes while still. Relays can tell at most whether someone is moving. Walking, driving and speed are inside the encryption. |
| Invites and approvals | Sealed one-to-one messages (kind 1059) signed by a throwaway key and encrypted to the recipient. |
| Removing someone | The admin rotates the circle key and sends it only to remaining members. |
| Storage | App state, including your private key, is encrypted with an Android Keystore key (iOS: the Keychain) and excluded from cloud and device backups. Location history uses its own key that never leaves the phone. |
| Map | MapLibre (via maplibre-compose, on Android and iOS) with OpenFreeMap tiles. The tile server sees roughly which area you're viewing, never anyone's position. |

## Project layout

```
shared/       Kotlin Multiplatform core: crypto, Nostr, circles, invites, motion policy (+ unit tests)
ui/           Compose Multiplatform screens and map, shared by Android and iOS
androidApp/   Android app: foreground location service, Keystore storage, permissions
iosApp/       Xcode project for the iPhone and iPad app (iOS code lives in ui/src/iosMain)
site/         Static page that hands https invite links to the app
```

## Building

Requires JDK 17 and the Android SDK (Android Studio installs both).

```
./gradlew :shared:testAndroidHostTest    # crypto and protocol tests
./gradlew :androidApp:assembleDebug      # debug APK in androidApp/build/outputs/apk/debug/
```

Every push also builds on GitHub Actions; the debug and staging APKs are attached to each run as `opaq-debug-apk` and `opaq-staging-apk`.

See [TESTING.md](TESTING.md) for how to test on real phones.
