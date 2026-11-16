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

## Circle protocol v2 (Phases 3, 4, 7 and part of 12)

QM-03, QM-04, QM-05 and the inbox part of QM-02 all needed changes to the same message formats, so they ship together as **circle protocol v2**. New circles are created on v2. An admin's app upgrades each v1 circle it runs to v2 the first time it runs this version, by rotating to a fresh v2 key (the roster goes to members' public keys, as v1 members expect). v1 circles stay fully readable until then.

Compatibility: v2 rosters use a new message type (`roster2`), so an older app ignores them instead of misreading a v2 key as a v1 key. A member still on an older app stops receiving the circle after the upgrade, so everyone in a circle needs this version. The app is pre-release, which is why this is acceptable; it should be stated in release notes.

### QM-03: acknowledged key rotation and stale-key protection

**Vulnerability.** The new key after a removal was sent once, as a message that expires after 7 days. A member who missed it kept publishing under the old key indefinitely, and only the removed person (who still had that key) could read those updates.

**Root cause.** No acknowledgement, no retry, and no way for a member to learn that its key had been replaced.

**Changes.**

- **Acknowledgements and retries.** Every v2 roster (rotation, approval, role change) is recorded per recipient in `Circle.pendingAcks`. Members reply with `RosterAckBody`. Unacknowledged rosters are resent after 2 min, 10 min, 30 min, 2 h, then every 6 h, for 14 days (`Engine.retryRosters`). The admin sees who hasn't received the new key (a banner in the people list and a row on the person's profile).
- **Closed notices.** A rotation publishes a signed `EpochBody(closed = true)` under the old epoch's control key, on its own tag so a heartbeat can never replace it on a relay. The admin republishes it daily for 14 days. A member that sees it marks the circle `stale`, stops publishing to it and asks for the roster (`RosterRequestBody`). The admin resends the roster to members, and sends a removal notice to anyone who isn't one.
- **Heartbeats.** The admin publishes a signed `EpochBody(closed = false)` for the current epoch every 6 hours and at least once a day. A member publishes only while the newest heartbeat is under 3 days old (`HEARTBEAT_MAX_AGE_SECONDS`). This bounds how long a phone could keep a replaced key if every relay withheld the closed notice.
- **Sync before sending.** After starting or reconnecting, a member publishes only once a relay has finished sending what it stores for the control subscription (EOSE), so a phone that was off for days checks for closed notices first.
- **No late sends.** Location updates are never queued while offline (`publish(..., queueIfOffline = false)`), so nothing goes out late under a key replaced in the meantime.
- `Engine.shareStatus(circle)` reports `OK`, `CHECKING`, `KEY_REPLACED` or `ADMIN_AWAY`, and the home screen explains a pause.

**Tests.** `CircleSecurityTest`: `memberWhoMissesTheRotationNeverPublishesUnderTheOldKey` (C offline during A removing B; C's offline update is dropped; C reconnects through a relay that loses all its messages and still stops publishing; once messages flow C gets the new key; nothing published after the removal opens with any key B kept), `relaysWithholdingEverythingStopsPublishingAfterTheHeartbeatExpires`, `aDeviceThatWasOffForDaysChecksBeforePublishing`, `unacknowledgedRostersAreResentUntilConfirmed`, `removedMemberWhoIgnoresTheNoticeIsToldAgainWhenAsking`.

**Found by the tests.** The first version put the closed notice and the heartbeat under the same relay key and tag, so a relay could keep the heartbeat and drop the closed notice when both had the same timestamp. Closed notices now have their own tag (`CircleCrypto.closedTag`).

**Remaining limitations.** If the admin's phone is off for more than 3 days, members pause sharing until it comes back, and the app says why. Only the admin can rotate keys or send heartbeats; MLS would remove that single point.

### QM-04: forward secrecy and post-compromise recovery

**Vulnerability.** One static key per epoch, and the epoch changed only on removal. A key taken from any phone or backup decrypted every archived update since the last removal.

**Changes.**

- **Daily ratchet.** The location key moves forward every UTC day: `key(d+1) = HMAC-SHA256(key(d), "qm-ratchet|circle|epoch|d+1")`, an HKDF-Expand step. It can't be run backwards. Phones keep only yesterday's key (to read updates sent just before midnight) and derive today's. `CircleCrypto.keyForDay` returns null for any earlier day.
- **Fresh randomness.** Removals and a weekly automatic rotation replace the key and control key with new random keys (a new epoch), so a stolen key stops working within a week even if nobody is removed.
- **Catching up.** An offline member ratchets its stored key forward when it comes back. It is never sent older keys.
- **Backups** carry only the current key: previous keys and old control keys are stripped (`Backups.fromState`).
- **Control key.** Profiles, heartbeats and inbox tags use a per-epoch control key that doesn't ratchet. It protects no location data.

**Tests.** `CircleSecurityTest.yesterdaysKeyIsGoneAndTodaysCantReachBack`, `weeklyRotationReplacesTheKeyWithFreshRandomness`, `backupsCarryOnlyCurrentKeys`. `ProtocolTest.ratchetIsDeterministicOneWayAndCircleSpecific`.

**Remaining limitations.** Someone who takes a phone's keys can read up to about two days back (yesterday's key) and forward until the next fresh rotation (at most a week). A stolen identity (QM-01) still receives new keys; see Phase 1. MLS was evaluated and not adopted for now: it needs a delivery service model that Nostr relays don't provide without much more machinery, and the changes above meet the plan's acceptance criteria.

### QM-05: members overwriting or replaying each other's updates

**Vulnerability.** Publisher keys were derived from the shared circle key, so any member could compute everyone's and replace their newest update on a relay.

**Changes.**

- **Own publisher keys.** v2 publisher keys come from the member's own identity secret (`CircleCrypto.memberPublisher`, `memberProfilePublisher`, `controlPublisher`). Nobody else can sign with them, so nobody else can replace their events.
- **Binding.** The signed body names the outer event's key (`LocationBody.pub`, `ProfileBody.pub`), and receivers reject a mismatch. A member can't re-wrap someone's real update under a different key.
- **Replay protection.** Every update carries a sequence number (`seq`, at least the current time in milliseconds, so a restored phone keeps counting up). Receivers keep the newest per member (`AppState.memberSeqs`) and reject anything not newer. This doesn't rely on relay timestamps.

**Tests.** `CircleSecurityTest.aMemberCannotOverwriteOrReplayAnotherMembersUpdates`. `ProtocolTest.memberPublisherKeysDependOnTheMembersOwnSecret`, `v2BodiesRoundTripAndOldOnesStillDecode`.

### QM-02 (part): inboxes instead of permanent public keys

**Changes.**

- v2 messages to members go to rotating inbox tags (`CircleCrypto.inbox`: an HMAC of the control key, the member and the day) instead of the member's public key. The content is still encrypted to the member's identity key.
- A phone subscribes to its public key only while it has a v1 circle, a join in progress, or an open invite. Once joined, no subscription names a permanent key.
- Messages for members who may have missed a rotation also go to inboxes under recent control keys.

**Tests.** `CircleSecurityTest.relaysDontSeePermanentKeysOnceJoined`.

The rest of QM-02 is under Phase 12 below.

## QM-01: covert identity cloning (Phase 1)

**Vulnerability.** A backup holds the identity secret and every circle key. Anyone with the unlocked phone for a minute could export one, restore it elsewhere, and silently read every circle, including future keys (rotations are sent to the identity).

**Changes.**

- **Optional PIN.** Offered as a setup step and in Settings, Security. It is 4 to 8 digits, stored only as a salted PBKDF2-SHA256 hash (150,000 iterations) inside the encrypted state. After five wrong tries it locks for 30 seconds, doubling with each further miss; lockouts are logged. As the owner asked, the PIN is **never** needed to open the app or to see locations.
- **Enforced in the engine.** When a PIN is set, the engine itself refuses sensitive actions unless it was entered in the last 2 minutes (`Engine.isAuthorized`), so no screen can forget to ask: backup export and restore, creating invites, approving members, changing relays, removing people, changing roles, handing over or deleting a circle, resetting the identity, changing or removing the PIN, and turning off "Hide in recent apps". The UI asks through `PinGate`.
- **Backups need a PIN.** Because a backup can recreate the identity elsewhere, making one requires a PIN to be set and entered. The Backup screen offers to set one. This is the one place the PIN isn't optional; it can be dropped if the owner prefers.
- **Security log.** Every backup export (with the device model), restore, PIN change, lockout and identity reset is recorded and shown under Settings, Security log. The screen says plainly that someone who resets or wipes the app's data can erase it: this is a mitigation, not detection.
- **Restoring** still starts with sharing paused.
- **Identity reset** ("Get a new device code"). The phone gets a new key pair:
  - Circles it administers are rotated to fresh keys with the new identity as admin, signed by the old identity, which members still trust. The old identity, and any clone of it, is left out.
  - Circles run by someone else are left with a `LeftBody`, so their admin removes the old identity and rotates the key. Rejoining needs a new invite, and the confirmation screen says so.

**Tests.**

- `SecurityTest`: `backupsNeedAPinSetAndEntered`, `sensitiveActionsNeedThePinButViewingDoesNot`, `wrongPinsLockForLongerEachTime`, `pinIsStoredOnlyAsASaltedHash`.
- `CircleSecurityTest.aClonedIdentityLosesAccessWhenTheOwnerResetsIt`: a clone made from a backup reads the circle; after the owner resets, the clone sees nothing new and no later update opens with its keys.
- `CircleSecurityTest.anAdminResetMovesTheirCirclesToTheNewIdentity`.

**Not implemented: per-device keys.** The plan's long-term item (a stable user identity, separate device keys, a device registry and per-device revocation) changes how every message is addressed and verified, and needs multi-device key distribution. Done superficially, it would look like revocation without being one. It is left as the next architectural step. Until then, the identity reset above is the way to recover from a copied identity, and it meets the acceptance criteria ("a revoked device cannot obtain future circle keys", "users can recover from a compromised identity").

**Remaining limitations.**

- A clone that acts before the owner resets can do anything the owner could, including (for an admin) racing the reset with its own rotation.
- Someone who knows the PIN, or who can wipe the app and restore a backup they made earlier, isn't stopped.

## QM-06: covert location sharing (Phase 2)

**Vulnerability.** Sharing started with location permission alone. On Android 13+ a denied notification permission, or a turned-off channel, hid the "Sharing your location" notification while sharing kept running.

**Changes (Android).**

- **Checks.** `QuietmapApp.canShowSharingNotification()` checks that notifications are enabled for the app, that the Android 13+ permission is granted, and that the "Location sharing" channel isn't turned off. It looks at the actual channel, not just the permission.
- **Before starting.** `ensureSharingService` (used at app start, on resume, after boot and after app updates) won't start sharing unless the notification can show.
- **While running.** `LocationService` checks again every 5 seconds and stops itself, without publishing, the moment the notification can't show.
- **Telling the user.** A banner on the home screen says sharing is paused because the notification is off, with a button that asks for the permission or opens the app's notification settings. Sharing resumes once it's visible again.
- **After a restart.** A reboot goes through the same check, so it can't restart sharing invisibly.
- **Monthly reminder.** At least once a month the app shows who receives your location: each circle, the number of people, and exact or approximate. It offers Keep sharing or Stop sharing with everyone; swiping it away decides nothing. While the service runs it also posts a "You're still sharing your location" notification once a day after the month is up, until the user confirms. Turning sharing on counts as confirming.
- The home screen already shows Sharing/Paused at all times.

**iOS.** The system's blue background-location indicator stays on (`showsBackgroundLocationIndicator = true`), and nothing tries to hide it. The in-app monthly reminder works the same on iOS.

**Tests.** `SecurityTest.sharingIsReconfirmedMonthly`. The notification checks are Android framework calls. The emulator smoke test runs with notifications granted; a test that turns the channel off would need system UI automation, so it is listed under open items.

**Remaining limitations.**

- A determined abuser can uninstall the app's notification access and also uninstall Quietmap, or root the phone. Neither leaves Quietmap sharing invisibly.
- Android's own location indicator and background-location reminders vary by manufacturer.

## QM-14 and QM-17: membership transparency and app protection (Phase 11)

**Changes.**

- **Membership history.** Every join, departure or removal and admin change is recorded per circle: who, who approved or removed them, and when. It's shown under Settings, Circles, then a circle. Kept on the phone only.
- **Join notifications.** When a roster adds or removes someone, everyone else in the circle gets a notification ("Cal joined Family", "Approved by Ana"; "Cal is no longer in Family"), on a separate "Circle changes" channel.
- **Hide in recent apps.** An option in Settings, Security sets Android's `FLAG_SECURE`, which blanks the app-switcher thumbnail and blocks screenshots. Turning it off needs the PIN. The setting's text doesn't claim it protects against someone holding the unlocked phone.
- **No app lock.** As the owner asked, opening the app and viewing locations never need the PIN.

**Tests.** `CircleSecurityTest.everyoneHearsWhenSomeoneJoinsOrLeaves`.

**Remaining limitations.** Membership history is local, so a phone that joins later sees only what happened since. iOS doesn't post join notifications yet (the history is there); that needs the iOS notification permission flow in the Xcode shell.

## QM-08: invitation and membership verification (Phase 8)

**Vulnerability.** Whoever redeemed a link first took the single-use slot. A link with the inviter's key and relays swapped made the joiner send their request to, and trust a roster from, the attacker. Checking device codes was optional.

**Changes.**

- **Signed links (v2).** The inviter signs every field (key, names, circle, relays, secret, expiry). `InviteLinks.decode` rejects a v2 link whose fields don't match the signature, so a relay list or expiry can't be changed on the way. v1 links from older apps still decode.
- **Required verification.** Joining needs one of:
  - the QR code scanned in the app (the inviter was physically there),
  - the inviter's full 16-character device code, read out by them and typed by the joiner (`Engine.deviceCodeMatches`, constant-time),
  - an inviter already verified.

  The engine enforces this, not just the screen. A re-signed attacker link decodes, but it carries the attacker's key, so the real inviter's code doesn't match and the join is refused. The invite screen shows the inviter's code with instructions to read it out.
- **Nothing shared until confirmed.** A circle you've just been let into starts unconfirmed: no location and no profile go to it. The home screen shows who is in it and asks "Start sharing". This applies even after a verified join.
- **Shorter invites.** Invites last 1 hour by default (24 hours is an option), and stay single-use.
- **Private inboxes.** v2 join requests and the approval go to an inbox tag derived from the invite secret, never to either person's public key.
- **Relays from invites (QM-09).** Relays named in an invite are shown on the join screen and used only while that join is in progress. They are dropped when the join completes or expires, unless the user turns on "Keep using these relays". Only `wss://` relays are accepted, at most four.

**Tests.** `CircleSecurityTest`: `aTamperedInviteIsRejected` (swapped key, relays or expiry; a re-signed attacker invite; a missing or wrong code; the right code typed in any case or spacing), `joiningDoesntShareUntilTheUserSeesTheCircleAndConfirms`, `anInviteWorksOnceAndJoinTrafficNeverNamesPublicKeys`, `inviteRelaysAreUsedOnlyWhileJoining`.

**Remaining limitations.**

- The device code is 64 bits (audit QM-13, rated Low, left unchanged).
- A joiner who types a code read out by an attacker on the phone is still fooled; the instructions say to call the person they know.

## QM-10: backup encryption (Phase 9)

**Vulnerability.** Backups used PBKDF2-SHA256 (600,000 iterations), which GPUs can attack offline at scale.

**Changes.**

- **Argon2id.** New backups (`quietmap-backup-v2`) use Argon2id v1.3 with 64 MiB of memory, 3 passes and one lane. The salt is a fresh random 16 bytes. The parameters are stored in the file's second line.
- **Libraries.** BouncyCastle (`Argon2BytesGenerator`) on Android, libsodium `crypto_pwhash` on iOS. The passphrase's UTF-8 bytes are hex-encoded before hashing, so both platforms hash identical bytes (the iOS bindings pass the string length as the byte count).
- **Old backups.** v1 files still restore through an explicit branch on the header.
- **Crafted files.** A file can't demand absurd work: memory is limited to 8 MiB–1 GiB and passes to 1–10, and other algorithms are refused.
- **Wrong passwords.** Decryption happens before anything is changed, so a wrong passphrase never overwrites the existing identity (tested under QM-11).

**Tests.** `CryptoTest.argon2idMatchesTheReferenceImplementation` (vectors from argon2-cffi, the reference implementation, including the 64 MiB setting), `SecurityTest.newBackupsUseArgon2idAndOldOnesStillRestore`, `backupFilesCantDemandAbsurdWork`, `EngineTest.backupRejectsWrongPassphraseAndShortOnes`.

**Not done.** Generated 128-bit recovery codes ("consider" in the plan) are a reasonable next step. The PIN uses PBKDF2, which is enough because its hash only exists inside the Keystore-encrypted state.

## QM-12: location privacy controls (Phase 10)

**Changes.**

- **Per-circle precision:** exact, neighbourhood (about 2 km) or city (about 20 km), under Settings, Circles, then a circle.
- **Grid snapping.** Approximate modes snap to the centre of a fixed grid cell (`LocationPrecision.apply`); longitude steps widen toward the poles to keep cells square. Every update from inside a cell is identical, so averaging many updates reveals nothing more. No random noise is used. Speed, heading and the recent path are dropped in approximate modes, since they narrow the position again.
- **Recent path** can be turned off per circle.
- **Battery** sharing is off by default for new installs. Existing installs keep their saved choice.
- **Telling people.** Receivers see "area only" or "city only" in the person's status. The monthly reminder lists each circle's precision.
- **PIN.** Making sharing more precise, or turning the path on, needs the PIN when one is set. Making it less precise never does.

**Tests.** `ProtocolTest.approximateLocationsSnapToAFixedGridAndCantBeAveragedBack`, `CircleSecurityTest.eachCircleGetsOnlyThePrecisionChosenForIt`.

**Remaining limitations.** Crossing a cell boundary reveals that you're near it; a bigger cell trades usefulness for privacy. Your own phone still shows your exact position to you.

## QM-02: metadata visible to relays (Phase 12)

**Vulnerability.** Relays can't read anything, but they see IP addresses, which tags each connection follows, timing, and permanent public keys on join and roster traffic.

**Changes.**

- **Inboxes instead of public keys** (see "Circle protocol v2" above). Once a circle is on v2, relays no longer see who messages whom.
- **Tor (Android).** Settings, Privacy, "Connect through Tor" routes every relay connection through Orbot's SOCKS proxy (`127.0.0.1:9050`, Ktor `ProxyBuilder.socks`). It fails closed: if Orbot isn't running nothing connects, and there is no direct fallback. Turning it off needs the PIN when one is set. iOS doesn't offer it yet (`PlatformBridge.supportsTor`).
- **Blurred timestamps.** A location event's outer `created_at` is moved back by a random 0–15 seconds, but never earlier than the previous update, so relays still keep the newest one. The exact time is inside the encryption.
- **What's visible, written down.** `PRIVACY.md` lists exactly what relays, the map tile host, the phone's network location provider and invite carriers can see, and what Tor does and doesn't hide.

**Tests.** `CircleSecurityTest.torRoutesRelaysThroughOrbotAndTurningItOffNeedsThePin`, `relaysSeeBlurredTimestampsThatNeverGoBackwards`, `anInviteWorksOnceAndJoinTrafficNeverNamesPublicKeys`, `relaysDontSeePermanentKeysOnceJoined`.

**Investigated, not implemented.**

- **Separate connections per role** (reading vs publishing). Without Tor they share an IP address, so a relay links them anyway; with Tor, Orbot's per-destination circuits already separate relays from each other. Not worth the extra sockets and battery yet.
- **Decoy subscriptions.** They would cost bandwidth and battery on every phone for a gain that's hard to measure. Revisit with measurements.
- **Map tiles over Tor.** Tiles are fetched by MapLibre's own HTTP client, which isn't routed through Orbot yet. Tile requests reveal the areas viewed, not who is where.

**Remaining limitations.** One connection subscribes to all of a phone's circle tags, so a relay can still group the tags followed by the same connection, and see the two update rates (moving or still).

## Supply chain and CI (Phase 13)

**Changes.**

- **Pinned actions.** Every GitHub Action in `android.yml` is pinned to a full commit SHA (checked against its release tag), so a moved tag can't change what runs with the workflow's write token. The token's write access is used only to push the run summary to `ci-logs`.
- **Dependabot** (`.github/dependabot.yml`) proposes weekly updates for Gradle dependencies and Actions.
- **Dependency updates.** Ktor 3.0.3 → 3.4.1 (relay websockets) and BouncyCastle 1.80 → 1.84 (Argon2id, AES-GCM). Other libraries were already current.
- **APK checks.** CI verifies each APK's signature with `apksigner` and installs the R8 staging build on the emulator, so a missing ProGuard rule fails the run.

**Not done.**

- **Release signing.** Test builds share a CI-only key kept in the Actions cache. A real release key, kept offline by the owner, should sign Play and GitHub releases. That needs the owner.
- **Reproducible builds and Gradle dependency verification** (`verification-metadata.xml`). Both need a machine with Maven access to generate and check hashes; this session can't reach Maven.
