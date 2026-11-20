# What others can see

Opaq has no server, and every location, name, photo and battery level is end-to-end encrypted. Only the phones in a circle can read them. But data still travels through other people's computers, and they can see some things about it (metadata). This page lists exactly what, so the privacy claims stay accurate.

## Relays

Phones exchange encrypted messages through public [Nostr](https://nostr.com) relays (four by default; you can change them in Settings, Privacy). A relay operator **can** see:

| What | Details |
| --- | --- |
| Your IP address, and when you're online | Unless you use Tor (below). Every phone keeps a connection open to each of its relays. |
| Which circle tags your phone follows | Tags change every hour (locations) or every day (heartbeats and inboxes), and come from keys the relay doesn't have. But one connection asks for all of a phone's circles at once, so a relay can tell that the same phone follows those tags, and that other connections follow the same ones: roughly who shares a circle with whom, and how many people are in it. |
| When you publish, and how often | Updates go out about every 30 seconds while moving and every 5 minutes while still, plus one when you start or stop moving. A relay can tell roughly whether you're moving. Timestamps it sees are blurred by up to 15 seconds. |
| The size of each event | Location updates are padded to one fixed size, so their size reveals nothing. |
| When a circle changes | Key rotations, joins and removals send a burst of messages. |

A relay **can't** see where you are, your name or photo, who sent a message (messages are signed by throwaway or hourly keys, and addressed to rotating inbox tags rather than your public key), your activity, speed or battery, or whether you share exactly or approximately.

Things that help:

- **Connect through Tor** (Android, Settings, Privacy; needs the Orbot app). Relays then see a Tor exit address instead of yours. If Orbot isn't running, nothing connects: it never falls back to a direct connection. Tor hides your IP; it doesn't stop a relay from linking the tags one connection asks for.
- **Approximate location** for a circle (Settings, Circles). It's applied before encryption, so it protects you from everyone, including the people in the circle.
- **Choosing relays you trust.** Relays only ever get encrypted data, but fewer, trusted relays mean fewer operators who see the metadata above.

Not done yet, and why:

- **Separate connections** for reading and publishing. They would still come from the same IP address, so a relay could link them anyway unless Tor is on.
- **Decoy subscriptions.** Asking for extra tags to hide the real ones costs bandwidth and battery on every phone. It's worth revisiting with measurements.

## The map

Map tiles come from [OpenFreeMap](https://openfreemap.org). Opening the app loads the area around you and around the people you look at, up to street level. The tile server (and any network in front of it) sees your IP address and which areas you view, but not who is where. Tor currently covers relays only, not the map.

## Your phone's operating system

- **Android:** while you're still, Opaq uses the phone's network location, which on phones with Google services sends nearby Wi-Fi and cell information to Google if "Google Location Accuracy" is on. That's a system setting, outside Opaq.
- **iOS motion:** with the Motion & Fitness permission, Opaq reads the phone's own guess at whether you're walking, cycling or in a vehicle, only to notice sooner that you've started moving. iOS works this out on the phone. Opaq doesn't store it or send it anywhere; your circle sees only the activity worked out from location, as before. Opaq also asks iOS to watch a small area around where you stopped, which is handled on the phone like any other geofence.
- **Notifications:** Opaq doesn't use Google or Apple push notifications, so neither company is told when you get an update.

## Invite links

An invite link contains your name, the circle's name, your relays, your public key and a one-time secret. Whatever app carries the link (a chat app, email) can read it. Scanning the QR code in person avoids that. Shared links open a static page on GitHub Pages that hands the invite to the app. The invite is in the part after `#`, which browsers never send, so GitHub only sees that someone opened an invite page, from which IP address. Links are signed, so they can't be changed on the way, and someone who joins from a link has to type in your device code, which you read out to them.

## Location history

Each phone keeps a history of the updates it receives (and its own), so people can look back at where someone has been from their profile. It's kept only on that phone, encrypted with a key protected by the Keystore (Android) or Keychain (iOS), and is never sent anywhere or put in backups. It holds what the updates already contained: position, time and activity (still, walking, cycling, driving), thinned to about one point every 15 m or 2 minutes while moving and every 15 minutes while still. Approximate (area or city only) updates aren't kept.

How long it's kept is each phone's own setting: 1 week by default, up to 3 months, or off. That means the people in your circles can look back at your movements for as long as *their* setting says. Opaq deletes someone's history from a phone once they're no longer in any circle with it. Like any app that receives your location, a modified copy could keep more; Opaq can't prevent that.

## Backups

A backup file contains your identity and circle keys, encrypted with your passphrase using Argon2id. Where you save it (your phone, a cloud drive) decides who could try to guess the passphrase. Making a backup needs your PIN and is recorded in Settings, Security log.

See [SECURITY_IMPLEMENTATION_LOG.md](SECURITY_IMPLEMENTATION_LOG.md) for how each of these protections was built and tested.
