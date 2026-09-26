import EcoCore
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage(Pref.translator) private var translator = "claude"
    @AppStorage(Pref.style) private var style = "line"
    @AppStorage(Pref.maxSpeed) private var maxSpeed = 1.25
    @AppStorage(Pref.exaggeration) private var exaggeration = 0.5
    @AppStorage(Pref.quality) private var quality = "best"
    @AppStorage(Pref.noMusic) private var noMusic = false
    @State private var apiKey = Keychain.apiKey() ?? ""
    @State private var saved = false

    var body: some View {
        Form {
            Section("Translation") {
                Picker("Translator", selection: $translator) {
                    Text("Claude (best, needs an API key)").tag("claude")
                    Text("Offline (free, literal)").tag("argos")
                }
                if translator == "claude" {
                    HStack {
                        SecureField("Anthropic API key", text: $apiKey)
                        Button(saved ? "Saved" : "Save") {
                            Keychain.setAPIKey(apiKey)
                            saved = true
                            model.objectWillChange.send()
                        }
                    }
                    .onChange(of: apiKey) { _ in saved = false }
                    Link("Get a key at console.anthropic.com", destination: URL(string: "https://console.anthropic.com/")!)
                        .font(.caption)
                }
            }

            Section("Voice") {
                Picker("Tone", selection: $style) {
                    Text("Follow each line of the original").tag("line")
                    Text("One steady voice").tag("steady")
                }
                if Engine.isAppleSilicon {
                    LabeledContent("Emotion") {
                        Slider(value: $exaggeration, in: 0.25...1.0) {
                            EmptyView()
                        } minimumValueLabel: { Text("Calm") } maximumValueLabel: { Text("Dramatic") }
                    }
                }
                LabeledContent("Speed-up allowed") {
                    Slider(value: $maxSpeed, in: 1.0...1.5, step: 0.05) {
                        EmptyView()
                    } minimumValueLabel: { Text("1×") } maximumValueLabel: { Text("1.5×") }
                }
                Text(String(format: "Long lines may be sped up to %.2f× to stay in sync.", maxSpeed))
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("Speed") {
                Picker("Models", selection: $quality) {
                    Text("Best quality").tag("best")
                    Text("Faster").tag("fast")
                }
                Toggle("My videos have no music (skips the slowest step)", isOn: $noMusic)
            }

            Section("Engine") {
                LabeledContent("Voice engine", value: Engine.voiceEngine == "chatterbox"
                    ? "Chatterbox (Apple silicon)" : "XTTS (Intel, non-commercial)")
                Button("Reinstall Engine…") { model.screen = .setup }
            }
        }
        .formStyle(.grouped)
        .frame(width: 520)
        .padding()
    }
}
