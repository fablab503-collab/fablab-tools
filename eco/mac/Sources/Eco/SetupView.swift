import EcoCore
import SwiftUI

struct SetupView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage(Pref.acceptedXTTSLicence) private var acceptedXTTSLicence = false
    @State private var needsTools = Engine.needsCommandLineTools

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Label("Set up Eco", systemImage: "waveform.badge.mic").font(.largeTitle.bold())

            Text("Eco dubs your videos into other languages in your own voice, entirely on this Mac. "
                + "Before the first dub it downloads its engine: speech recognition, voice separation "
                + "and your voice's cloning model. That is about \(Engine.isAppleSilicon ? "7" : "6") GB, once.")
                .fixedSize(horizontal: false, vertical: true)

            if Engine.isAppleSilicon {
                Label("This Mac has Apple silicon: dubbing runs on its graphics chip with the Chatterbox voice, "
                    + "licensed MIT, so it is fine for monetised channels.", systemImage: "cpu")
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                GroupBox {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("This Mac has an Intel processor. The fast Chatterbox voice needs Apple silicon, "
                            + "so Eco uses the XTTS voice, which runs on the processor: expect roughly one to two "
                            + "hours per ten minutes of video, per language.")
                        Text("XTTS is licensed for non-commercial use only (Coqui Public Model License). A "
                            + "channel that earns money from its videos is commercial.")
                            .bold()
                        Toggle("I will use it only for videos that earn no money", isOn: $acceptedXTTSLicence)
                        if needsTools {
                            Divider()
                            Text("Installing the XTTS voice on an Intel Mac also needs Apple's free "
                                + "Command Line Tools (about 1 GB).")
                            HStack {
                                Button("Install Command Line Tools") { Engine.installCommandLineTools() }
                                Button("Check Again") { needsTools = Engine.needsCommandLineTools }
                            }
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(4)
                }
            }

            HStack {
                Button(model.busy ? "Installing…" : (model.errorMessage == nil ? "Install" : "Try Again")) {
                    model.install()
                }
                    .keyboardShortcut(.defaultAction)
                    .controlSize(.large)
                    .disabled(model.busy || (!Engine.isAppleSilicon && (!acceptedXTTSLicence || needsTools)))
                Text("Takes 10–30 minutes depending on your connection.").foregroundStyle(.secondary)
            }

            ProgressPanel()
            Spacer()
        }
        .padding(28)
        .onAppear {
            // Nothing to decide on Apple silicon, so setup starts straight away. Intel Macs wait
            // for the licence answer and the Command Line Tools.
            if Engine.isAppleSilicon && !model.busy && model.errorMessage == nil { model.install() }
        }
    }
}
