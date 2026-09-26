import EcoCore
import SwiftUI
import UniformTypeIdentifiers

struct MainView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage(Pref.translator) private var translator = "claude"
    @State private var choosing = false
    @State private var dropTargeted = false

    private let columns = [GridItem(.adaptive(minimum: 130), alignment: .leading)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                videoPicker

                VStack(alignment: .leading, spacing: 8) {
                    Text("Dub into").font(.headline)
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 6) {
                        ForEach(Languages.available) { language in
                            Toggle(language.name, isOn: Binding(
                                get: { model.selected.contains(language.code) },
                                set: { on in
                                    if on { model.selected.insert(language.code) } else { model.selected.remove(language.code) }
                                }))
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("Notes for the translator").font(.headline)
                    TextField("e.g. cycling vlog, casual and funny, Latin American Spanish, keep bike part names in English",
                              text: $model.notes, axis: .vertical)
                        .lineLimit(2...4)
                        .textFieldStyle(.roundedBorder)
                }

                if translator == "claude" && model.needsAPIKey {
                    Label("Add your Anthropic API key in Eco › Settings, or switch Settings to offline translation.",
                          systemImage: "key.fill")
                        .foregroundStyle(.orange)
                }

                HStack(spacing: 12) {
                    Button("Translate and Review") { model.translate() }
                        .keyboardShortcut(.defaultAction)
                    Button("Dub Without Review") { model.dub() }
                    if model.hasPreviousWork {
                        Button("Open Review") { model.openReview() }
                    }
                }
                .controlSize(.large)
                .disabled(model.busy || model.video == nil || model.selected.isEmpty
                    || (translator == "claude" && model.needsAPIKey))

                ProgressPanel()
            }
            .padding(28)
        }
    }

    private var videoPicker: some View {
        RoundedRectangle(cornerRadius: 14)
            .strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [7]))
            .foregroundStyle(dropTargeted ? Color.accentColor : Color.secondary.opacity(0.5))
            .frame(height: 130)
            .overlay {
                VStack(spacing: 8) {
                    if let video = model.video {
                        Label(video.lastPathComponent, systemImage: "film").font(.title3.bold())
                        Button("Choose Another…") { choosing = true }.disabled(model.busy)
                    } else {
                        Image(systemName: "film.stack").font(.system(size: 32)).foregroundStyle(.secondary)
                        Text("Drop a video here").font(.title3)
                        Button("Choose…") { choosing = true }
                    }
                }
            }
            .dropDestination(for: URL.self) { urls, _ in
                guard let url = urls.first, !model.busy else { return false }
                model.video = url
                return true
            } isTargeted: { dropTargeted = $0 }
            .fileImporter(isPresented: $choosing, allowedContentTypes: [.movie, .audio]) { result in
                if case .success(let url) = result { model.video = url }
            }
    }
}
