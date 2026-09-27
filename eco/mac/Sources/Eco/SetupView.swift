import AppKit
import EcoCore
import SwiftUI

/// First launch: one screen, one progress bar, nothing to fill in.
struct SetupView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage(Pref.acceptedXTTSLicence) private var acceptedXTTSLicence = false
    @State private var needsTools = Engine.needsCommandLineTools

    private var canStart: Bool { Engine.isAppleSilicon || (acceptedXTTSLicence && !needsTools) }

    var body: some View {
        VStack(spacing: 18) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 96, height: 96)
            Text("Getting Eco ready").font(.largeTitle.bold())
            Text("This happens only once. Eco downloads everything it needs to dub videos on this Mac "
                + "(about 7 GB), so it can take 15 to 30 minutes. You can leave it running.")
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 520)

            if !Engine.isAppleSilicon && !acceptedXTTSLicence {
                intelQuestion
            } else if needsTools {
                toolsQuestion
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    if model.busy {
                        Text("Step \(model.setupStep) of 2: "
                            + (model.setupStep == 1 ? "installing the dubbing engine" : "downloading the voice models"))
                            .font(.headline)
                    }
                    ProgressPanel(showsStop: false)
                    if !model.busy && model.errorMessage != nil {
                        Button("Try Again") { model.install() }
                            .controlSize(.large)
                            .keyboardShortcut(.defaultAction)
                    }
                }
                .frame(maxWidth: 520)
            }
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .onAppear(perform: startIfReady)
        .onChange(of: acceptedXTTSLicence) { _ in startIfReady() }
    }

    private func startIfReady() {
        if canStart && !model.busy && model.errorMessage == nil { model.install() }
    }

    /// Intel Macs get the XTTS voice, whose licence allows only non-commercial use.
    private var intelQuestion: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 10) {
                Text("One thing first").font(.headline)
                Text("On this Mac (Intel), Eco uses a voice that is free for personal videos only: "
                    + "not for channels that earn money. Dubbing also takes longer here, about an hour "
                    + "for every ten minutes of video.")
                    .fixedSize(horizontal: false, vertical: true)
                Button("I understand, continue") { acceptedXTTSLicence = true }
                    .controlSize(.large)
                    .keyboardShortcut(.defaultAction)
            }
            .padding(6)
        }
        .frame(maxWidth: 520)
    }

    /// Only for a copy of Eco built without the ready-made Intel part.
    private var toolsQuestion: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 10) {
                Text("This copy of Eco needs Apple's free Command Line Tools to finish setting up.")
                    .fixedSize(horizontal: false, vertical: true)
                HStack {
                    Button("Install Them") { Engine.installCommandLineTools() }
                    Button("Done, Continue") {
                        needsTools = Engine.needsCommandLineTools
                        startIfReady()
                    }
                }
            }
            .padding(6)
        }
        .frame(maxWidth: 520)
    }
}
