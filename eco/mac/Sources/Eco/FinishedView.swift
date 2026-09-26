import EcoCore
import SwiftUI

struct FinishedView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Label("Your dubs are ready", systemImage: "checkmark.circle.fill")
                .font(.largeTitle.bold())
                .foregroundStyle(.green)

            List(model.files, id: \.self) { file in
                Label(file.lastPathComponent, systemImage: icon(for: file))
            }
            .frame(minHeight: 140)

            GroupBox("Putting them on YouTube") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("• **One video, several languages:** in YouTube Studio open the video › Languages › Add language › Dub, and upload the .m4a file for that language.")
                    Text("• **Subtitles:** Languages › Add language › Subtitles, and upload the .srt file.")
                    Text("• **Or** upload each .mp4 as its own video.")
                    Text("• YouTube asks creators to disclose realistic AI-made audio: check its altered content setting when you upload.")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(4)
            }

            HStack {
                Button("Show in Finder") { model.showInFinder() }
                    .keyboardShortcut(.defaultAction)
                Button("Edit Lines and Dub Again") { model.openReview() }
                Spacer()
                Button("Dub Another Video") { model.startOver() }
            }
            .controlSize(.large)
        }
        .padding(28)
    }

    private func icon(for file: URL) -> String {
        switch file.pathExtension {
        case "mp4": return "film"
        case "m4a": return "waveform"
        default: return "captions.bubble"
        }
    }
}
