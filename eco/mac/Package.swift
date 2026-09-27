// swift-tools-version:5.9
// Eco for Mac: a SwiftUI front end for the eco dubbing engine.
// Build the app with ./build.sh; `swift test` runs the EcoCore tests.
import PackageDescription

let package = Package(
    name: "Eco",
    platforms: [.macOS(.v13)],
    targets: [
        // Everything that is not a view: engine paths, the process runner, progress events,
        // the review files and the Keychain. Kept apart so it can be unit tested.
        .target(name: "EcoCore"),
        .executableTarget(name: "Eco", dependencies: ["EcoCore"]),
        .testTarget(name: "EcoCoreTests", dependencies: ["EcoCore"]),
    ]
)
