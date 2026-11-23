import AppIntents
import SwiftUI
import WidgetKit

// Opaq's Control Center controls (also on the Lock Screen and the Action button).
// The actions run in the app (see Shared/OpaqControlIntents.swift); this extension only draws them.

@main
struct OpaqControls: WidgetBundle {
    var body: some Widget {
        SharingControl()
        UpdateLocationControl()
    }
}

/// On: sharing with your circles. Off: paused until you turn it back on.
struct SharingControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: OpaqControlKind.sharing, provider: Provider()) { isOn in
            ControlWidgetToggle("Opaq", isOn: isOn, action: SetSharingControlIntent()) { on in
                Label(on ? "Sharing" : "Paused", systemImage: on ? "location.fill" : "location.slash.fill")
            }
            .tint(.blue)
        }
        .displayName("Share My Location")
        .description("Pause sharing your location, or start it again.")
    }

    struct Provider: ControlValueProvider {
        var previewValue: Bool { true }
        func currentValue() async throws -> Bool { OpaqSharedState.sharing }
    }
}

struct UpdateLocationControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: OpaqControlKind.updateLocation) {
            ControlWidgetButton(action: UpdateLocationControlIntent()) {
                Label("Update My Location", systemImage: "location.magnifyingglass")
            }
        }
        .displayName("Update My Location")
        .description("Send a fresh location if you've moved.")
    }
}
