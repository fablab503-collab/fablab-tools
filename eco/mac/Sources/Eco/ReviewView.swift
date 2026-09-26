import EcoCore
import SwiftUI

/// Every line, original and translation side by side, editable before anything is voiced.
struct ReviewView: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = ""

    private static let transcriptTab = "transcript"

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Picker("", selection: $tab) {
                    ForEach(model.scripts, id: \.language) { script in
                        Text(Languages.name(script.language)).tag(script.language)
                    }
                    Text("Original").tag(Self.transcriptTab)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            .padding()

            Text(tab == Self.transcriptTab
                 ? "Fix anything the speech recognition misheard. Changed lines are translated again."
                 : "Edit any translation. A line you change is voiced again and is never rephrased automatically. "
                    + "“New take” re-records a line without changing its words.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .padding(.horizontal)

            List {
                if tab == Self.transcriptTab {
                    transcriptRows
                } else if let index = model.scripts.firstIndex(where: { $0.language == tab }) {
                    ForEach($model.scripts[index].lines) { $line in
                        DubLineRow(line: $line)
                    }
                }
            }

            Divider()
            HStack {
                Button("Back") { model.screen = .main }
                Spacer()
                Button("Save") { _ = model.saveReview() }
                Button("Dub \(model.scripts.count == 1 ? "It" : "All \(model.scripts.count)")") {
                    model.dubReviewed()
                }
                .keyboardShortcut(.defaultAction)
            }
            .controlSize(.large)
            .padding()
        }
        .onAppear { if tab.isEmpty { tab = model.scripts.first?.language ?? Self.transcriptTab } }
    }

    @ViewBuilder private var transcriptRows: some View {
        if model.transcript != nil {
            ForEach(Binding(
                get: { model.transcript?.lines ?? [] },
                set: { model.transcript?.lines = $0 })) { $line in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(clock(line.start)).font(.system(.body, design: .monospaced)).foregroundStyle(.secondary)
                        .frame(width: 48, alignment: .trailing)
                    TextField("", text: $line.text, axis: .vertical)
                }
                .padding(.vertical, 3)
            }
        }
    }
}

struct DubLineRow: View {
    @Binding var line: DubLine

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(clock(line.start))
                .font(.system(.body, design: .monospaced))
                .foregroundStyle(.secondary)
                .frame(width: 48, alignment: .trailing)
            VStack(alignment: .leading, spacing: 4) {
                Text(line.source).foregroundStyle(.secondary).textSelection(.enabled)
                TextField("", text: $line.text, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
            }
            VStack(alignment: .trailing, spacing: 4) {
                Text(String(format: "%.1fs", line.end - line.start))
                    .font(.caption).foregroundStyle(.secondary)
                Button("New take") { line.take = (line.take ?? 1) + 1 }
                    .buttonStyle(.link)
                    .font(.caption)
                if line.editedByHand {
                    Text("edited").font(.caption).foregroundStyle(.orange)
                }
            }
            .frame(width: 70)
        }
        .padding(.vertical, 4)
    }
}
