import AppIntents
import UserNotifications
import OpaqUI

// Shortcuts and Siri actions. The work is done in Kotlin (`OpaqShortcuts` in ui/src/iosMain);
// these only describe the actions to iOS.
//
// Every action needs the phone unlocked: someone holding a locked phone shouldn't be able to
// stop sharing (or start it) by asking Siri. Starting sharing from a shortcut also posts a
// notification, so an automation can't turn it back on unnoticed.
//
// None of them read anyone's location. A shortcut can hand its output to any other action
// (a message, a web request, a note), which would take the location out of the encryption.

// MARK: - Circles

struct CircleEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Circle"
    static let defaultQuery = CircleQuery()

    let id: String
    let name: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct CircleQuery: EntityQuery {
    @MainActor
    func entities(for identifiers: [String]) async throws -> [CircleEntity] {
        allCircles().filter { identifiers.contains($0.id) }
    }

    @MainActor
    func suggestedEntities() async throws -> [CircleEntity] { allCircles() }

    @MainActor
    private func allCircles() -> [CircleEntity] {
        OpaqShortcuts.shared.circles().map { CircleEntity(id: $0.id, name: $0.name) }
    }
}

// MARK: - How long

enum PauseLength: String, AppEnum {
    case untilResumed, halfHour, oneHour, twoHours, fourHours, eightHours

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "How long"
    static let caseDisplayRepresentations: [PauseLength: DisplayRepresentation] = [
        .untilResumed: "Until I turn it back on",
        .halfHour: "30 minutes",
        .oneHour: "1 hour",
        .twoHours: "2 hours",
        .fourHours: "4 hours",
        .eightHours: "8 hours",
    ]

    var minutes: Int32 {
        switch self {
        case .untilResumed: 0
        case .halfHour: 30
        case .oneHour: 60
        case .twoHours: 120
        case .fourHours: 240
        case .eightHours: 480
        }
    }
}

// MARK: - All sharing

struct StartSharingIntent: AppIntent {
    static let title: LocalizedStringResource = "Start Sharing My Location"
    static let description = IntentDescription("Turns sharing back on for every circle you haven't paused.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let wasSharing = OpaqShortcuts.shared.isSharing()
        let message = try await OpaqShortcuts.shared.setSharing(on: true, minutes: 0)
        if !wasSharing && OpaqShortcuts.shared.isSharing() {
            ShortcutNotices.cancelPauseEnd()
            ShortcutNotices.sharingStarted()
        }
        return .result(dialog: "\(message)")
    }
}

struct PauseSharingIntent: AppIntent {
    static let title: LocalizedStringResource = "Pause Sharing My Location"
    static let description = IntentDescription("Stops sending your location to every circle. You still see everyone.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "For", default: .untilResumed)
    var length: PauseLength

    static var parameterSummary: some ParameterSummary { Summary("Pause sharing for \(\.$length)") }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = try await OpaqShortcuts.shared.setSharing(on: false, minutes: length.minutes)
        if !OpaqShortcuts.shared.isSharing() { ShortcutNotices.schedulePauseEnd(afterMinutes: length.minutes) }
        return .result(dialog: "\(message)")
    }
}

/** For the Action button or a Control Center shortcut: one tap pauses, the next starts again. */
struct ToggleSharingIntent: AppIntent {
    static let title: LocalizedStringResource = "Turn Sharing On or Off"
    static let description = IntentDescription("Pauses sharing if it's on, and starts it again if it's paused.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let on = !OpaqShortcuts.shared.isSharing()
        let message = try await OpaqShortcuts.shared.setSharing(on: on, minutes: 0)
        if on && OpaqShortcuts.shared.isSharing() {
            ShortcutNotices.cancelPauseEnd()
            ShortcutNotices.sharingStarted()
        }
        return .result(dialog: "\(message)")
    }
}

// MARK: - One circle

struct PauseCircleIntent: AppIntent {
    static let title: LocalizedStringResource = "Pause Sharing With a Circle"
    static let description = IntentDescription("Stops sending your location to one circle. You still see everyone in it.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "Circle", requestValueDialog: "Which circle?")
    var circle: CircleEntity

    @Parameter(title: "For", default: .untilResumed)
    var length: PauseLength

    static var parameterSummary: some ParameterSummary { Summary("Pause sharing with \(\.$circle) for \(\.$length)") }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = try await OpaqShortcuts.shared.setCirclePaused(circleId: circle.id, paused: true, minutes: length.minutes)
        return .result(dialog: "\(message)")
    }
}

struct ResumeCircleIntent: AppIntent {
    static let title: LocalizedStringResource = "Resume Sharing With a Circle"
    static let description = IntentDescription("Starts sending your location to a circle you paused.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "Circle", requestValueDialog: "Which circle?")
    var circle: CircleEntity

    static var parameterSummary: some ParameterSummary { Summary("Resume sharing with \(\.$circle)") }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = try await OpaqShortcuts.shared.setCirclePaused(circleId: circle.id, paused: false, minutes: 0)
        return .result(dialog: "\(message)")
    }
}

// MARK: - Location

struct UpdateLocationIntent: AppIntent {
    static let title: LocalizedStringResource = "Update My Location"
    static let description = IntentDescription("Gets a fresh GPS fix, and sends it straight away if you've moved since your last update.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        .result(dialog: "\(OpaqShortcuts.shared.updateLocation())")
    }
}

// MARK: - Siri phrases

struct OpaqAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: PauseSharingIntent(),
            phrases: ["Pause \(.applicationName)", "Pause sharing in \(.applicationName)", "Stop sharing in \(.applicationName)"],
            shortTitle: "Pause Sharing",
            systemImageName: "location.slash"
        )
        AppShortcut(
            intent: StartSharingIntent(),
            phrases: ["Resume \(.applicationName)", "Start sharing in \(.applicationName)"],
            shortTitle: "Start Sharing",
            systemImageName: "location"
        )
        AppShortcut(
            intent: ToggleSharingIntent(),
            phrases: ["Toggle \(.applicationName)", "Turn \(.applicationName) sharing on or off"],
            shortTitle: "Sharing On or Off",
            systemImageName: "location.circle"
        )
        AppShortcut(
            intent: PauseCircleIntent(),
            phrases: ["Pause a circle in \(.applicationName)", "Pause \(\.$circle) in \(.applicationName)", "Pause sharing with \(\.$circle) in \(.applicationName)"],
            shortTitle: "Pause a Circle",
            systemImageName: "person.2.slash"
        )
        AppShortcut(
            intent: ResumeCircleIntent(),
            phrases: ["Resume a circle in \(.applicationName)", "Resume \(\.$circle) in \(.applicationName)", "Resume sharing with \(\.$circle) in \(.applicationName)"],
            shortTitle: "Resume a Circle",
            systemImageName: "person.2"
        )
        AppShortcut(
            intent: UpdateLocationIntent(),
            phrases: ["Update my location in \(.applicationName)", "Send my location in \(.applicationName)"],
            shortTitle: "Update My Location",
            systemImageName: "location.magnifyingglass"
        )
    }
}

// MARK: - Notifications

enum ShortcutNotices {
    private static let pauseEndId = "opaq-pause-end"

    /** Sharing started from a shortcut, automation or Control Center: always say so. */
    static func sharingStarted() {
        post(id: UUID().uuidString, title: "Opaq is sharing your location",
             body: "Sharing was turned on from Shortcuts or Control Center. Open Opaq to see who can see you.", after: nil)
    }

    /**
     * iOS doesn't run Opaq while sharing is paused, so a timed pause ends the next time Opaq
     * runs. This reminds you when it's over; opening Opaq (or the notification) starts sharing.
     */
    static func schedulePauseEnd(afterMinutes minutes: Int32) {
        cancelPauseEnd()
        guard minutes > 0 else { return }
        post(id: pauseEndId, title: "Your Opaq pause is over",
             body: "Open Opaq to start sharing your location again.", after: TimeInterval(minutes) * 60)
    }

    static func cancelPauseEnd() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [pauseEndId])
    }

    private static func post(id: String, title: String, body: String, after seconds: TimeInterval?) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let trigger = seconds.map { UNTimeIntervalNotificationTrigger(timeInterval: max(1, $0), repeats: false) }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }
}
