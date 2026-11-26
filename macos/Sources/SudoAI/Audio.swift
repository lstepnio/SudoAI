import AVFoundation
import AppKit
import CoreAudio
import Foundation

func audioCheck(_ status: OSStatus, _ context: String) throws {
  if status != noErr {
    throw AppError.message(
      "\(context) failed (Core Audio \(status)). Check Privacy & Security permissions.")
  }
}
func address(
  _ selector: AudioObjectPropertySelector,
  scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal
) -> AudioObjectPropertyAddress {
  AudioObjectPropertyAddress(
    mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
}
func audioList(_ object: AudioObjectID, selector: AudioObjectPropertySelector) throws
  -> [AudioObjectID]
{
  var a = address(selector)
  var size: UInt32 = 0
  try audioCheck(AudioObjectGetPropertyDataSize(object, &a, 0, nil, &size), "Read audio list")
  var result = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
  try audioCheck(
    AudioObjectGetPropertyData(object, &a, 0, nil, &size, &result), "Read audio objects")
  return result
}
func audioString(_ object: AudioObjectID, selector: AudioObjectPropertySelector) -> String {
  var a = address(selector)
  var value: Unmanaged<CFString>?
  var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
  guard AudioObjectGetPropertyData(object, &a, 0, nil, &size, &value) == noErr else { return "" }
  return value?.takeRetainedValue() as String? ?? ""
}
struct Microphone: Identifiable, Hashable {
  let id: AudioObjectID
  let name: String
}
func microphones() -> [Microphone] {
  ((try? audioList(AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyDevices))
    ?? []).compactMap { id in
      var a = address(kAudioDevicePropertyStreams, scope: kAudioDevicePropertyScopeInput)
      var size: UInt32 = 0
      guard AudioObjectGetPropertyDataSize(id, &a, 0, nil, &size) == noErr, size > 0 else {
        return nil
      }
      return Microphone(id: id, name: audioString(id, selector: kAudioObjectPropertyName))
    }
}
final class AudioInput {
  private var engine: AVAudioEngine?
  private var generation = 0
  private let stateLock = NSLock()
  private let queue = DispatchQueue(label: "com.majjix.sudoai.mic")
  var receive: (([Int16], Int) -> Void)?
  func start(device: AudioObjectID?) async throws {
    stop()
    let requested = stateLock.withLock { generation }
    let allowed = await AVCaptureDevice.requestAccess(for: .audio)
    try stateLock.withLock {
      guard requested == generation else { throw AppError.message("Microphone start cancelled") }
      guard allowed else { throw AppError.message("Microphone permission blocked") }
      let e = AVAudioEngine()
      let node = e.inputNode
      if var device {
        if let unit = node.audioUnit {
          try audioCheck(
            AudioUnitSetProperty(
              unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &device,
              UInt32(MemoryLayout<AudioObjectID>.size)), "Select microphone")
        }
      }
      let format = node.outputFormat(forBus: 0)
      guard format.sampleRate > 0, format.channelCount > 0 else {
        throw AppError.message("Microphone unavailable")
      }
      node.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
        guard let self, let floats = buffer.floatChannelData else { return }
        let n = Int(buffer.frameLength)
        let channels = Int(buffer.format.channelCount)
        var mono = [Int16](repeating: 0, count: n)
        for i in 0..<n {
          var sum: Float = 0
          for c in 0..<channels { sum += floats[c][i] }
          let value = max(-1, min(1, sum / Float(channels)))
          mono[i] = Int16(value * 32767)
        }
        let rate = Int(buffer.format.sampleRate)
        self.queue.async { self.receive?(mono, rate) }
      }
      try e.start()
      engine = e
    }
  }
  func stop() {
    stateLock.withLock {
      generation += 1
      engine?.inputNode.removeTap(onBus: 0)
      engine?.stop()
      engine = nil
    }
    queue.sync {}
  }
  deinit { stop() }
}
// Tracks the last successfully activated membership. A failed rebuild must be retried
// even if Teams keeps the same process IDs on the following poll.
struct TeamsTapMembership {
  private(set) var processes: [AudioObjectID] = []
  private(set) var active = false

  mutating func refresh(
    _ current: [AudioObjectID], stop: () -> Void,
    start: ([AudioObjectID]) throws -> Void
  ) throws -> String? {
    guard current != processes || (!current.isEmpty && !active) else { return nil }
    stop()
    active = false
    if current.isEmpty {
      processes = []
      return "Teams stopped; capture gap"
    }
    try start(current)
    processes = current
    active = true
    return "Teams audio process list refreshed; transition gap"
  }
}

final class TeamsTap {
  private var tap: AudioObjectID = 0
  private var aggregate: AudioObjectID = 0
  private var io: AudioDeviceIOProcID?
  private let queue = DispatchQueue(label: "com.majjix.sudoai.teams")
  private var timer: DispatchSourceTimer?
  private var membership = TeamsTapMembership()
  var receive: (([Int16], Int) -> Void)?
  var status: ((String) -> Void)?
  private func teamsProcesses() throws -> [AudioObjectID] {
    try audioList(
      AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyProcessObjectList
    ).filter { id in
      let bundle = audioString(id, selector: kAudioProcessPropertyBundleID).lowercased()
      var a = address(kAudioProcessPropertyPID)
      var pid: pid_t = 0
      var size = UInt32(MemoryLayout<pid_t>.size)
      _ = AudioObjectGetPropertyData(id, &a, 0, nil, &size, &pid)
      let app = NSRunningApplication(processIdentifier: pid)
      let path = app?.bundleURL?.path.lowercased() ?? ""
      // Include embedded Teams audio helpers, never unrelated Electron/Edge processes.
      return bundle == "com.microsoft.teams2" || bundle.hasPrefix("com.microsoft.teams2.")
        || bundle == "com.microsoft.teams" || bundle.hasPrefix("com.microsoft.teams.")
        || path.contains("/microsoft teams.app/")
    }.sorted()
  }
  func start() throws {
    stop()
    guard #available(macOS 14.2, *) else {
      throw AppError.message("Teams process audio needs macOS 14.2 or later")
    }
    let processes = try teamsProcesses()
    guard !processes.isEmpty else {
      throw AppError.message("Open Microsoft Teams first; no Teams audio processes found")
    }
    _ = try membership.refresh(processes, stop: {}, start: createTap)
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now() + 2, repeating: 2)
    timer.setEventHandler { [weak self] in
      guard let self else { return }
      do {
        let current = try self.teamsProcesses()
        if let text = try self.membership.refresh(
          current, stop: self.destroyTap, start: self.createTap)
        {
          self.status?(text)
        }
      } catch { self.status?(error.localizedDescription) }
    }
    self.timer = timer
    timer.resume()
  }
  @available(macOS 14.2, *) private func createTap(_ processes: [AudioObjectID]) throws {
    let description = CATapDescription(stereoMixdownOfProcesses: processes)
    description.name = "SudoAI Teams"
    description.isPrivate = true
    description.muteBehavior = .unmuted
    try audioCheck(AudioHardwareCreateProcessTap(description, &tap), "Create Teams process tap")
    do {
      var a = address(kAudioTapPropertyFormat)
      var asbd = AudioStreamBasicDescription()
      var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
      try audioCheck(AudioObjectGetPropertyData(tap, &a, 0, nil, &size, &asbd), "Read tap format")
      guard let format = AVAudioFormat(streamDescription: &asbd),
        asbd.mFormatID == kAudioFormatLinearPCM, asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0,
        asbd.mBitsPerChannel == 32
      else { throw AppError.message("Unsupported Teams tap PCM format") }
      let spec: [String: Any] = [
        kAudioAggregateDeviceNameKey: "SudoAI Private Teams Tap",
        kAudioAggregateDeviceUIDKey: UUID().uuidString, kAudioAggregateDeviceIsPrivateKey: true,
        kAudioAggregateDeviceTapAutoStartKey: true,
        kAudioAggregateDeviceTapListKey: [
          [kAudioSubTapUIDKey: description.uuid.uuidString, kAudioSubTapDriftCompensationKey: true]
        ],
      ]
      try audioCheck(
        AudioHardwareCreateAggregateDevice(spec as CFDictionary, &aggregate),
        "Create private tap device")
      try audioCheck(
        AudioDeviceCreateIOProcIDWithBlock(&io, aggregate, queue) { [weak self] _, input, _, _, _ in
          guard let self,
            let buffer = AVAudioPCMBuffer(
              pcmFormat: format, bufferListNoCopy: input, deallocator: nil),
            let data = buffer.floatChannelData
          else { return }
          let list = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input))
          guard let first = list.first else { return }
          let interleaved = format.isInterleaved
          let channels = Int(format.channelCount)
          let frames = Int(first.mDataByteSize) / 4 / (interleaved ? channels : 1)
          var mono = [Int16](repeating: 0, count: frames)
          for i in 0..<frames {
            var sum: Float = 0
            for c in 0..<channels { sum += interleaved ? data[0][i * channels + c] : data[c][i] }
            mono[i] = Int16(max(-1, min(1, sum / Float(channels))) * 32767)
          }
          self.receive?(mono, Int(format.sampleRate))
        }, "Install process tap reader")
      try audioCheck(AudioDeviceStart(aggregate, io), "Start Teams tap")
    } catch {
      destroyTap()
      throw error
    }
  }
  private func destroyTap() {
    if aggregate != 0 {
      if let io {
        AudioDeviceStop(aggregate, io)
        AudioDeviceDestroyIOProcID(aggregate, io)
      }
      AudioHardwareDestroyAggregateDevice(aggregate)
    }
    if tap != 0 { if #available(macOS 14.2, *) { AudioHardwareDestroyProcessTap(tap) } }
    aggregate = 0
    tap = 0
    io = nil
  }
  func stop() {
    timer?.cancel()
    timer = nil
    queue.sync {
      destroyTap()
      membership = TeamsTapMembership()
    }
  }
  deinit { stop() }
}
