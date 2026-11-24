import AppIntents
import Foundation
#if OPAQ_APP
import OpaqUI
#endif

// Control Center controls (iOS 18+). This file is in both the app and the OpaqControls extension:
// the extension needs the intent types to build its controls, but the work has to happen in the
// app, where the Kotlin engine is. Adopting LiveActivityIntent is how iOS is told to run an
// intent in the app's process (starting it in the background if needed) instead of the
// extension's; Opaq shows no Live Activity.
//
// The same rules as the Shortcuts actions (OpaqShortcuts.swift): the phone must be unlocked,
// starting sharing posts a notification, and nothing returns anyone's location.

/// What the extension may know about the app: whether sharing is on, and nothing else.
enum OpaqSharedState {
    static let appGroup = "group.me.opaq.app"
    private static let sharingKey = "sharing"

    /// Sharing is on for new installs, so that's the guess until the app has said otherwise.
    static var sharing: Bool {
        UserDefaults(suiteName: appGroup)?.object(forKey: sharingKey) as? Bool ?? true
    }

    static func save(sharing: Bool) {
        UserDefaults(suiteName: appGroup)?.set(sharing, forKey: sharingKey)
    }
}

enum OpaqControlKind {
    static let sharing = "me.opaq.app.control.sharing"
    static let updateLocation = "me.opaq.app.control.update-location"
}

@available(iOS 18.0, *)
struct SetSharingControlIntent: SetValueIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Share My Location"
    static let description = IntentDescription("Pauses sharing until you turn it back on, or starts it again.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
    // Shortcuts already has the same actions (Start, Pause, Turn Sharing On or Off).
    static let isDiscoverable = false

    @Parameter(title: "Sharing")
    var value: Bool

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if OPAQ_APP
        let wasSharing = OpaqShortcuts.shared.isSharing()
        _ = try await OpaqShortcuts.shared.setSharing(on: value, minutes: 0)
        let sharing = OpaqShortcuts.shared.isSharing()
        // A control pauses until turned back on, so no "pause is over" reminder either way.
        ShortcutNotices.cancelPauseEnd()
        if sharing && !wasSharing { ShortcutNotices.sharingStarted() }
        // The app also does this when sharing changes; here too in case nothing changed
        // (no circle yet), so the toggle goes back to what's true.
        OpaqSharedState.save(sharing: sharing)
        #endif
        return .result()
    }
}

@available(iOS 18.0, *)
struct UpdateLocationControlIntent: AppIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Update My Location"
    static let description = IntentDescription("Gets a fresh GPS fix, and sends it straight away if you've moved since your last update.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
    static let isDiscoverable = false

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if OPAQ_APP
        _ = OpaqShortcuts.shared.updateLocation()
        #endif
        return .result()
    }
}
