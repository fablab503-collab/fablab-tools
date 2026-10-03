import SwiftUI

/// Only there because a UI test needs an app of its own; the test drives Bouclier, Settings and Safari.
@main
struct HostApp: App {
    var body: some Scene {
        WindowGroup { Text("Bouclier iPhone tests") }
    }
}
