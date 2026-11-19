import SwiftUI
import UIKit
import OpaqUI

/// Hosts the shared Compose screens. Everything else lives in Kotlin (`OpaqIos`).
@main
struct OpaqApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .onOpenURL { url in _ = OpaqIos.shared.handleUrl(url: url.absoluteString) }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Also runs when iOS relaunches the app in the background for a significant location change.
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        OpaqIos.shared.start()
        return true
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { OpaqIosKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
