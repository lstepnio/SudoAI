import AppKit
import CoreImage
import ImageIO
import ScreenCaptureKit
import UniformTypeIdentifiers
import Vision

// Actor isolation serializes ownership while generation checks handle reentrancy at awaits.
// A stream that finishes starting after stop/reselection is explicitly stopped again.
protocol ScreenCaptureSession: AnyObject {
  func startCapture() async throws
  func stopCapture() async throws
}
extension SCStream: ScreenCaptureSession {}

actor ScreenCaptureLifecycle<Stream: ScreenCaptureSession> {
  private var generation: UInt64 = 0
  private var active: Stream?
  private let changed: (Stream?) -> Void
  private let status: (String) -> Void
  init(changed: @escaping (Stream?) -> Void, status: @escaping (String) -> Void) {
    self.changed = changed
    self.status = status
  }
  func replace(make: () throws -> Stream) async {
    generation &+= 1
    let request = generation
    let previous = active
    active = nil
    changed(nil)
    if let previous { try? await previous.stopCapture() }
    guard generation == request else { return }
    var candidate: Stream?
    do {
      let stream = try make()
      candidate = stream
      active = stream
      try await stream.startCapture()
      guard generation == request else {
        try? await stream.stopCapture()
        return
      }
      changed(stream)
      status("Screen moments active")
    } catch {
      if let candidate { try? await candidate.stopCapture() }
      guard generation == request else { return }
      active = nil
      changed(nil)
      status("Screen blocked: \(error.localizedDescription)")
    }
  }
  func stop() async {
    generation &+= 1
    let previous = active
    active = nil
    changed(nil)
    if let previous { try? await previous.stopCapture() }
  }
}

// Picker ownership must end before a stream stop yields to a new selection.
@MainActor func endScreenSelection(
  endPicker: () -> Void, stopStream: () async -> Void
) async {
  endPicker()
  await stopStream()
}

final class ScreenMoments: NSObject, SCContentSharingPickerObserver, SCStreamOutput,
  SCStreamDelegate
{
  private var stream: SCStream?
  private lazy var lifecycle = ScreenCaptureLifecycle<SCStream>(
    changed: { [weak self] stream in
      guard let self else { return }
      self.queue.sync { self.stream = stream }
    },
    status: { [weak self] text in self?.status?(text) })
  private let queue = DispatchQueue(label: "com.majjix.sudoai.screen", qos: .utility)
  private let context = CIContext()
  private var signature: [UInt8] = []
  private var lastMoment = 0.0
  private var enabled = false
  private var paused = false
  var receive: ((Data, String) -> Void)?
  var status: ((String) -> Void)?
  func choose() {
    queue.sync { enabled = true }
    let picker = SCContentSharingPicker.shared
    picker.add(self)
    var config = SCContentSharingPickerConfiguration()
    config.allowedPickerModes = [.singleWindow, .singleDisplay]
    config.excludedBundleIDs = [Bundle.main.bundleIdentifier ?? "com.majjix.sudoai"]
    picker.defaultConfiguration = config
    picker.isActive = true
    picker.present()
    status?("Choose a window or display; only changed moments are saved")
  }
  func setPaused(_ value: Bool) {
    queue.sync {
      paused = value
      signature = []
    }
  }
  func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) {
    status?("Screen selection cancelled")
  }
  func contentSharingPickerStartDidFailWithError(_ error: Error) {
    status?("Screen blocked: \(error.localizedDescription)")
  }
  func contentSharingPicker(
    _ picker: SCContentSharingPicker, didUpdateWith filter: SCContentFilter, for stream: SCStream?
  ) {
    guard queue.sync(execute: { enabled }) else { return }
    Task {
      await lifecycle.replace {
        // Stop can invalidate a picker callback before this task begins.
        guard self.queue.sync(execute: { self.enabled }) else {
          throw CancellationError()
        }
        let config = SCStreamConfiguration()
        config.width = 1600
        config.height = 1000
        config.minimumFrameInterval = CMTime(value: 2, timescale: 1)
        config.queueDepth = 3
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.showsCursor = false
        config.capturesAudio = false
        let stream = SCStream(filter: filter, configuration: config, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        return stream
      }
    }
  }
  func stream(_ stream: SCStream, didStopWithError error: Error) {
    guard queue.sync(execute: { self.stream === stream }) else { return }
    status?("Screen gap: \(error.localizedDescription)")
  }
  func stream(
    _ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
    of outputType: SCStreamOutputType
  ) {
    guard enabled, !paused, self.stream === stream, outputType == .screen, sampleBuffer.isValid,
      let attachments = CMSampleBufferGetSampleAttachmentsArray(
        sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
      let raw = attachments.first?[.status] as? Int,
      let frameStatus = SCFrameStatus(rawValue: raw)
    else { return }
    guard frameStatus == .complete else {
      if frameStatus != .idle { status?("Screen unavailable or hidden; capture gap") }
      return
    }
    guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    let image = CIImage(cvPixelBuffer: pixel)
    let scaled = image.transformed(
      by: CGAffineTransform(scaleX: 32 / image.extent.width, y: 20 / image.extent.height))
    var pixels = [UInt8](repeating: 0, count: 32 * 20 * 4)
    context.render(
      scaled, toBitmap: &pixels, rowBytes: 32 * 4,
      bounds: CGRect(x: 0, y: 0, width: 32, height: 20), format: .RGBA8,
      colorSpace: CGColorSpaceCreateDeviceRGB())
    let delta =
      signature.count == pixels.count
      ? zip(signature, pixels).reduce(0.0) { $0 + Double(abs(Int($1.0) - Int($1.1))) }
        / Double(pixels.count) : 255
    guard delta > 9, ProcessInfo.processInfo.systemUptime - lastMoment >= 4 else { return }
    signature = pixels
    lastMoment = ProcessInfo.processInfo.systemUptime
    guard let cg = context.createCGImage(image, from: image.extent) else { return }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = true
    do { try VNImageRequestHandler(cgImage: cg).perform([request]) } catch {
      status?("OCR unavailable: \(error.localizedDescription)")
    }
    let text =
      request.results?.compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
      ?? ""
    let output = NSMutableData()
    if let destination = CGImageDestinationCreateWithData(
      output, UTType.jpeg.identifier as CFString, 1, nil)
    {
      CGImageDestinationAddImage(
        destination, cg, [kCGImageDestinationLossyCompressionQuality: 0.65] as CFDictionary)
      if CGImageDestinationFinalize(destination), output.length <= 2 * 1024 * 1024 {
        receive?(output as Data, text)
      } else {
        status?("Screen image exceeded size limit; text moment saved")
        receive?(Data(), text)
      }
    }
  }
  func stop() async {
    await endScreenSelection(
      endPicker: {
        self.queue.sync {
          self.enabled = false
          self.signature = []
        }
        SCContentSharingPicker.shared.remove(self)
        SCContentSharingPicker.shared.isActive = false
      },
      stopStream: { await self.lifecycle.stop() })
  }
}
