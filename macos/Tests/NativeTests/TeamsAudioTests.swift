import AVFoundation
import CoreAudio
import SudoCore
import XCTest

@testable import SudoAI

final class TeamsAudioTests: XCTestCase {
  func testSyntheticHourKeepsTracksAndRouteChangePlayableAcrossPause() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "fixture", server: "https://fixture.test", title: "Virtual hour")
    // Accelerated media time, not a wall-clock hardware soak. Two recognizable,
    // independent sample values prove that track payloads never mix.
    for second in stride(from: 0, to: 3600, by: 10) {
      if second == 1800 {
        try store.flush(id)
        for track in ["mic", "system"] {
          try store.gap(id, track: track, start: 1_800_000, end: 1_810_000, reason: "Paused")
        }
        continue
      }
      let rate = second == 900 ? 48000 : 8000
      try store.append(
        id, track: "mic", pcm: [Int16](repeating: 111, count: rate * 10), rate: rate,
        at: second * 1000)
      try store.append(
        id, track: "system", pcm: [Int16](repeating: 222, count: rate * 10), rate: rate,
        at: second * 1000)
    }
    try store.finish(id, duration: 3_600_000)
    let session = try XCTUnwrap(store.history().first)
    XCTAssertEqual(session.duration, 3_600_000)
    var frames: [String: Int64] = [:]
    var rates = Set<Double>()
    for chunk in session.uploads where chunk.mime == "audio/wav" {
      let track = chunk.path.contains("/tracks/mic/") ? "mic" : "system"
      let file = try AVAudioFile(forReading: root.appendingPathComponent(chunk.file))
      frames[track, default: 0] += file.length
      rates.insert(file.fileFormat.sampleRate)
      XCTAssertLessThanOrEqual(Double(file.length) / file.fileFormat.sampleRate, 20)
      let data = try store.body(chunk)
      XCTAssertLessThan(data.count, 8 * 1024 * 1024)
      XCTAssertEqual(data[44], track == "mic" ? 111 : 222)
      XCTAssertEqual(data[45], 0)
    }
    XCTAssertEqual(frames, ["mic": 29_120_000, "system": 29_120_000])
    XCTAssertEqual(rates, [8000, 48000])
    let gaps = session.uploads.filter { $0.path.contains("/gaps/") }
    XCTAssertEqual(gaps.count, 2)
    XCTAssertTrue(session.uploads.last!.path.hasSuffix("/finalize"))
  }

  func testFailedHelperRefreshRetriesSameMembershipAndDoesNotChurnHealthyTap() throws {
    var membership = TeamsTapMembership()
    var starts: [[AudioObjectID]] = []
    var stops = 0
    let start: ([AudioObjectID]) throws -> Void = { ids in starts.append(ids) }
    XCTAssertNotNil(try membership.refresh([11], stop: { stops += 1 }, start: start))
    XCTAssertNil(try membership.refresh([11], stop: { stops += 1 }, start: start))
    XCTAssertEqual(starts, [[11]])
    XCTAssertThrowsError(
      try membership.refresh([11, 12], stop: { stops += 1 }, start: { _ in
        throw NSError(domain: "SyntheticTapFailure", code: 1)
      }))
    XCTAssertFalse(membership.active)
    XCTAssertNotNil(try membership.refresh([11, 12], stop: { stops += 1 }, start: start))
    XCTAssertTrue(membership.active)
    XCTAssertEqual(starts, [[11], [11, 12]])
    XCTAssertEqual(stops, 3)
    XCTAssertEqual(
      try membership.refresh([], stop: { stops += 1 }, start: start),
      "Teams stopped; capture gap")
    XCTAssertFalse(membership.active)
    XCTAssertNil(try membership.refresh([], stop: { stops += 1 }, start: start))
    XCTAssertNotNil(try membership.refresh([13], stop: { stops += 1 }, start: start))
    XCTAssertEqual(starts, [[11], [11, 12], [13]])
  }

  func testPauseDiscardsBothSourcesBeforeResume() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "fixture", server: "https://fixture.test", title: "Pause")
    let sink = CaptureSink(store: store)
    let origin = ProcessInfo.processInfo.systemUptime
    sink.configure(id: id, origin: origin, paused: false)
    sink.receive([101, 102], rate: 16000, track: "mic")
    sink.receive([201, 202], rate: 48000, track: "system")
    sink.configure(id: id, origin: origin, paused: true)
    sink.receive([30001], rate: 16000, track: "mic")
    sink.receive([30002], rate: 48000, track: "system")
    sink.drain()
    sink.configure(id: id, origin: origin, paused: false)
    sink.receive([103, 104], rate: 16000, track: "mic")
    sink.receive([203, 204], rate: 48000, track: "system")
    sink.drain()
    try store.finish(id, duration: 1000)
    let chunks = store.history()[0].uploads.filter { $0.mime == "audio/wav" }
    XCTAssertEqual(chunks.count, 2)
    for (track, expected) in [("mic", [101, 102, 103, 104]), ("system", [201, 202, 203, 204])] {
      let chunk = try XCTUnwrap(chunks.first { $0.path.contains("/tracks/\(track)/") })
      let data = try store.body(chunk)
      let samples = data.dropFirst(44).withUnsafeBytes { bytes in
        Array(bytes.bindMemory(to: Int16.self)).map { Int(Int16(littleEndian: $0)) }
      }
      XCTAssertEqual(samples, expected)
    }
  }
}
