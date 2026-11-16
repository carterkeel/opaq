# Security implementation log

This records how each finding from the security audit (13 Nov 2026, code at `078b96c`) was handled: the original problem, its root cause, what changed, the tests that guard it, and what is still open. The plan behind it is "Quietmap Security Hardening — Implementation Plan".

Ground rules that apply to every change below:

- No server, account, telemetry or analytics was added.
- Cryptographic primitives are unchanged (secp256k1-kmp, BouncyCastle, javax.crypto, CommonCrypto, libsodium). New constructions are built from them, never hand-rolled ciphers.
- Failures fail closed: when the app can't establish that a key is current, a device is authorized, an invitation is trusted or sharing is visible, it stops sharing.
- Message format changes are versioned. Older messages stay readable.
- Every fix has a regression test in `shared/src/commonTest/.../SecurityTest.kt` (or the named test file).

Authentication choice: the owner asked for an **optional PIN, offered during setup**, instead of biometrics or the device lock. The PIN is never needed to open the app or to see locations. When a PIN is set, it is asked for before the sensitive actions listed under QM-01.

## Phase 0: inspection

### Where each finding lives

| Finding | Code |
| --- | --- |
| QM-01 backup clone | `protocol/Backup.kt` (`Backups.fromState` copies the identity secret and every circle key), `Engine.exportBackup`, `Engine.sendRoster` (rotations are sealed to the identity key, so a clone gets them too) |
| QM-02 relay linkability | `Engine.refreshSubscriptions` (`qm-dm` filter on the permanent public key, `qm-circles` filter with every circle tag, both on one connection per relay) |
| QM-03 missed rotation | `Engine.removeMember` / `sendRoster` (one send, no acknowledgement), `DirectMessages` (7-day expiry), `refreshSubscriptions` (old key dropped after `OVERLAP_SECONDS`) |
| QM-04 forward secrecy | `CircleCrypto.conversationKey` (static key per epoch; the epoch changes only on removal) |
| QM-05 overwrite by members | `CircleCrypto.publisher` / `profilePublisher` (derived from the shared circle key) |
| QM-06 hidden notification | `QuietmapApp.ensureSharingService` (checks location permission only), `LocationService` |
| QM-08 invite swap | `InviteLinks`, `Engine.onJoinRequest`, `Engine.onRoster` (trusts `inviterPubkey` from the link) |
| QM-09 ws:// relays | `Engine.setRelays`, `Engine.acceptInvite`, `RelayPool.setRelays` |
| QM-10 backup KDF | `Backups` (PBKDF2-SHA256, 600k iterations, 8-character minimum) |
| QM-11 identity replaced | `SecureStore.load` and `IosStateStore.load` (failures returned `null`), `Engine` init (generated and saved a new identity on `null`) |
| QM-12 precision | `MemberLocation`, `AppState.shareBattery = true` |
| QM-14 silent joins | `Engine.onRoster` (no record or notice of membership changes) |
| QM-17 app protection | no PIN, no screen protection |
| QM-20/21 supply chain | `gradle/libs.versions.toml`, `.github/workflows/android.yml` |

The audit still applied to `main` at `078b96c` when this work started: every location above was unchanged.

### Platform differences

- **Storage.** Android: AES-GCM with an Android Keystore key (`SecureStore`). iOS: libsodium secretbox with a Keychain key, `AfterFirstUnlockThisDeviceOnly` (`IosStateStore`).
- **Crypto.** `PlatformCrypto` is `expect`/`actual`: BouncyCastle + javax on Android, CommonCrypto + libsodium on iOS. The iOS `actual`s compile only on macOS, so CI (Linux) never builds them.
- **Sharing indicator.** Android uses a foreground-service notification. iOS uses the system's blue location indicator (`showsBackgroundLocationIndicator`).

### Protocol versions before this work

- Invite links: `InviteData.v = 1`.
- Backups: `quietmap-backup-v1`.
- Message bodies: `loc`, `join_req`, `roster`, `profile`, `removed`, `left` (polymorphic, discriminator `t`, unknown fields ignored).

### Tests before this work, and the gap

- `CryptoTest`: NIP-44 spec vectors, signatures.
- `ProtocolTest`: message round trips, fixed-size padding, invite links, motion policy.
- `EngineTest`: backups, state restart, admin/manager rules, with no relays at all.
- `IosStateStoreTest` (iOS only), `CreateCircleTest` (emulator smoke test).

Nothing exercised two or more engines talking to each other, so none of the protocol attacks could be tested. This work adds `FakeRelayNetwork` (`commonTest`): an in-memory NIP-01 relay that keeps the newest addressable event per key and d-tag, honours expiration, replays stored events on subscribe and sends EOSE, and lets a test take one phone offline. Engines take a `transport` parameter so tests can plug it in; the app keeps using `RelayPool`.

Development note: this sandbox can't reach Maven, so shared code was also compiled and its tests run locally with `kotlinc` and stand-ins for secp256k1 and BouncyCastle that are not committed. CI runs the real libraries on every push and is the authority.

## QM-11: corrupted or unreadable state (Phase 6)

**Vulnerability.** Any failure to read saved state (a Keystore error, a damaged file, a missing key) made `SecureStore.load` return `null`. The engine then generated a new identity and saved it over the old file. The user silently dropped out of every circle.

**Root cause.** "Nothing saved yet" and "saved but unreadable" were the same value.

**Changes.**

- `StateStore.read()` now returns `StoredState.Missing`, `Found` or `Unreadable(reason, retryable)`. Only `Missing` (a true first launch) creates and saves an identity.
- `Engine.recovery` is non-null while state is unreadable. In that mode the engine writes nothing, refuses changes, doesn't connect and doesn't share.
- `Engine.retryLoad()` reads again (for example after unlocking the phone). `startFresh()` and restoring a backup first call `StateStore.setAside()`, which renames the old file (`state.bin.unreadable-<time>`) instead of deleting it.
- Android `SecureStore` reports a missing Keystore key, a failed authentication tag and Keystore errors separately. Reading never creates a key. Saving checks that the atomic rename succeeded, and an interrupted first save is recovered from the temporary file.
- iOS `IosStateStore` does the same. A missing Keychain key or a damaged file is now reported as unreadable instead of "start fresh".
- A recovery screen (`RecoveryScreen`) offers Try again, Restore from a backup, and Start over (with a confirmation that explains circles must be rejoined).

**Tests.** `SecurityTest`: `keystoreFailureNeverReplacesTheIdentity`, `throwingStoreIsTreatedAsUnreadable`, `damagedStateIsKeptNotOverwritten`, `startingOverSetsTheOldFileAsideFirst`, `restoringABackupWhileRecoveringSetsTheOldFileAside`, `onlyAFirstLaunchMakesANewIdentity`. `IosStateStoreTest`: `lockedKeychainIsUnreadableNotEmpty`, `lostKeyIsUnreadableNotEmpty`, `setAsideKeepsTheOldFile`.

**Remaining limitations.** If the Keystore key itself is destroyed (factory reset of secure hardware, some OS upgrades), the old file can't be decrypted by anyone; the only way back is a backup.

## QM-09: relay transport security (Phase 5)

**Vulnerability.** Remote relays could be added over plain `ws://`, by hand or through an invite link, exposing subscriptions and events on the network.

**Changes.**

- `RelayUrls.isAllowed` accepts only `wss://` with a real host for anything remote. Plain `ws://` is allowed only to `localhost`, `127.0.0.1`, `[::1]` or the emulator's `10.0.2.2`, and only when `RelayUrls.allowLocalPlaintext` is on, which the Android app sets only for debuggable builds.
- `Engine.setRelays` and `RelayPool.setRelays` both apply it, so nothing can bypass the check.
- TLS certificate checking is the platform default (OkHttp on Android, NSURLSession on iOS). No code disables or overrides it.

**Tests.** `SecurityTest.remoteRelaysMustUseTls`, `plaintextRelaysAreDroppedFromSettings`.

Invite-supplied relays are covered under QM-08 below.
