// swift-tools-version: 5.9
import PackageDescription

let package = Package(
  name: "SudoAI", platforms: [.macOS(.v14)],
  products: [.executable(name: "SudoAI", targets: ["SudoAI"])],
  targets: [
    .target(name: "SudoCore"),
    .executableTarget(
      name: "SudoAI", dependencies: ["SudoCore"],
      linkerSettings: [
        .linkedFramework("CoreAudio"), .linkedFramework("ScreenCaptureKit"),
        .linkedFramework("Vision"), .linkedFramework("CoreBluetooth"),
      ]), .testTarget(name: "SudoCoreTests", dependencies: ["SudoCore"]),
    .testTarget(name: "NativeTests", dependencies: ["SudoAI", "SudoCore"]),
  ])
