import EcoCore
import SwiftUI
import UniformTypeIdentifiers

/// Drop a video, tick languages, press Dub.
struct MainView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage(Pref.checkFirst) private var checkFirst = false
    @State private var choosing = false
    @State private var dropTargeted = false

    private let columns = [GridItem(.adaptive(minimum: 130), alignment: .leading)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                step(1, "Your video")
                videoPicker

                step(2, "Languages to dub into")
                LazyVGrid(columns: columns, alignment: .leading, spacing: 6) {
                    ForEach(Languages.available) { language in
                        Toggle(language.name, isOn: Binding(
                            get: { model.selected.contains(language.code) },
                            set: { on in
                                if on { model.selected.insert(language.code) } else { model.selected.remove(language.code) }
                            }))
                    }
                }

                step(3, "Dub")
                Toggle("Let me read and fix the translation before it is spoken", isOn: $checkFirst)
                if model.usesClaude {
                    TextField("Notes for the translator (optional), e.g. casual cycling vlog, Latin American Spanish",
                              text: $model.notes, axis: .vertical)
                        .lineLimit(1...3)
                        .textFieldStyle(.roundedBorder)
                }
                Button {
                    model.start()
                } label: {
                    Text(checkFirst ? "Translate" : "Dub")
                        .font(.title3.bold())
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .keyboardShortcut(.defaultAction)
                .disabled(model.busy || model.video == nil || model.selected.isEmpty)

                if model.hasPreviousWork && !model.busy {
                    Button("Open the lines from last time") { model.openReview() }
                        .buttonStyle(.link)
                }

                ProgressPanel()
            }
            .padding(28)
        }
    }

    private func step(_ number: Int, _ title: String) -> some View {
        HStack(spacing: 8) {
            Text("\(number)")
                .font(.headline)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Color.accentColor.opacity(0.2)))
            Text(title).font(.headline)
        }
    }

    private var videoPicker: some View {
        RoundedRectangle(cornerRadius: 14)
            .strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [7]))
            .foregroundStyle(dropTargeted ? Color.accentColor : Color.secondary.opacity(0.5))
            .frame(height: 120)
            .overlay {
                VStack(spacing: 8) {
                    if let video = model.video {
                        Label(video.lastPathComponent, systemImage: "film").font(.title3.bold())
                        Button("Choose Another…") { choosing = true }.disabled(model.busy)
                    } else {
                        Image(systemName: "film.stack").font(.system(size: 30)).foregroundStyle(.secondary)
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
