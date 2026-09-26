import EcoCore
import SwiftUI

@main
struct EcoApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup("Eco") {
            ContentView()
                .environmentObject(model)
                .frame(minWidth: 760, minHeight: 600)
        }
        .windowResizability(.contentMinSize)

        Settings {
            SettingsView()
                .environmentObject(model)
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        switch model.screen {
        case .setup: SetupView()
        case .main: MainView()
        case .review: ReviewView()
        case .finished: FinishedView()
        }
    }
}

/// What the engine is doing, with its full output one click away.
struct ProgressPanel: View {
    @EnvironmentObject var model: AppModel
    @State private var showLog = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.busy {
                HStack {
                    Text(model.stepText).font(.headline)
                    Spacer()
                    Button("Stop") { model.stop() }
                }
                if let progress = model.progress {
                    ProgressView(value: progress)
                } else {
                    ProgressView().progressViewStyle(.linear)
                }
            }
            if let error = model.errorMessage {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
            }
            if !model.log.isEmpty {
                DisclosureGroup("Details", isExpanded: $showLog) {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 1) {
                                ForEach(Array(model.log.enumerated()), id: \.offset) { index, line in
                                    Text(line).font(.system(.caption, design: .monospaced)).id(index)
                                }
                            }
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(height: 160)
                        .onChange(of: model.log.count) { count in
                            proxy.scrollTo(count - 1, anchor: .bottom)
                        }
                    }
                }
            }
        }
    }
}
