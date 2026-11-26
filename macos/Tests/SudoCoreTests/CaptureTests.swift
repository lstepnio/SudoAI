import AVFoundation
import CryptoKit
import XCTest

@testable import SudoCore

final class CaptureTests: XCTestCase {
  var root: URL!
  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent("SudoAI-test-\(UUID())")
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
  }
  override func tearDownWithError() throws { try FileManager.default.removeItem(at: root) }
  func testPlayableBoundedChunksAndFinalizeOrdering() throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(
      owner: "alice", server: "https://example.test", title: "Synthetic fixture")
    let pcm = (0..<320_320).map { Int16(sin(Double($0) * 0.05) * 2000) }
    try store.append(id, track: "mic", pcm: pcm, rate: 16000, at: 0)
    try store.moment(id, at: 12000, text: "Bookmark", bookmark: true)
    try store.gap(id, track: "mic", start: 20020, end: 25000, reason: "Muted by user")
    try store.append(
      id, track: "mic", pcm: [Int16](repeating: 100, count: 160), rate: 16000, at: 25000)
    try store.finish(id, duration: 26000)
    let session = try XCTUnwrap(store.history().first)
    XCTAssertEqual(session.counts, ["mic": 3])
    XCTAssertTrue(session.finalized)
    XCTAssertTrue(session.uploads.last!.path.hasSuffix("/finalize"))
    let chunks = session.uploads.filter { $0.mime == "audio/wav" }
    XCTAssertEqual(chunks.count, 3)
    for item in chunks {
      let data = try store.body(item)
      XCTAssertLessThan(data.count, 8 * 1024 * 1024)
      let audio = try AVAudioFile(forReading: root.appendingPathComponent(item.file))
      XCTAssertEqual(audio.fileFormat.sampleRate, 16000)
      XCTAssertLessThanOrEqual(audio.length, 320000)
      XCTAssertEqual(audio.fileFormat.channelCount, 1)
    }
    XCTAssertTrue(chunks[2].path.contains("start_ms=25000&end_ms=25010"))
    let body =
      try JSONSerialization.jsonObject(with: store.body(session.uploads.last!)) as! [String: Any]
    XCTAssertEqual(body["tracks"] as? [String: Int], ["mic": 3])
  }
  func testTracksRemainSeparateAcrossPauseAndSampleRateChange() throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Separate sources")
    try store.append(id, track: "mic", pcm: [1, 2, 3], rate: 16000, at: 0)
    try store.append(id, track: "system", pcm: [4, 5, 6], rate: 48000, at: 0)
    try store.flush(id)
    try store.gap(id, track: "mic", start: 1, end: 1000, reason: "Paused")
    try store.append(id, track: "mic", pcm: [7, 8, 9], rate: 48000, at: 1000)
    try store.finish(id, duration: 1001)
    XCTAssertEqual(store.history()[0].counts, ["mic": 2, "system": 1])
  }
  func testInterruptedCaptureRecoversDurablePlayableBytes() throws {
    let original = try CaptureStore(root: root)
    let id = try original.begin(
      owner: "original-owner", server: "https://original.test", title: "Recovery")
    try original.append(
      id, track: "omi", pcm: [Int16](repeating: 2, count: 320), rate: 16000, at: 1000)
    let recovered = try CaptureStore(root: root)
    let session = recovered.history()[0]
    XCTAssertEqual(session.owner, "original-owner")
    XCTAssertEqual(session.server, "https://original.test")
    XCTAssertTrue(session.finalized)
    XCTAssertEqual(session.counts, ["omi": 1])
    let chunk = try XCTUnwrap(session.uploads.first { $0.mime == "audio/wav" })
    XCTAssertEqual(try AVAudioFile(forReading: root.appendingPathComponent(chunk.file)).length, 320)
  }
  func testAcknowledgmentPersistsThenPrunesOnlyAcknowledgedBytes() throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Retry")
    try store.finish(id, duration: 0)
    for item in store.history()[0].uploads { try store.acknowledge(session: id, upload: item.id) }
    let recovered = try CaptureStore(root: root)
    XCTAssertEqual(recovered.history()[0].pending, 0)
    for item in recovered.history()[0].uploads {
      XCTAssertFalse(
        FileManager.default.fileExists(atPath: root.appendingPathComponent(item.file).path))
    }
  }
  func testRecoverySweepsAcknowledgedLeftoversAndKeepsPending() throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Ack sweep")
    try store.finish(id, duration: 0)
    let entries = store.history()[0].uploads
    try store.acknowledge(session: id, upload: entries[0].id)
    try Data("leftover".utf8).write(to: root.appendingPathComponent(entries[0].file))
    let recovered = try CaptureStore(root: root)
    XCTAssertFalse(
      FileManager.default.fileExists(atPath: root.appendingPathComponent(entries[0].file).path))
    XCTAssertFalse(try recovered.body(entries[1]).isEmpty)
  }
  func testRecoveredDurationCoversTextMomentsAndGapsWithoutAudio() throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Timeline")
    try store.moment(id, at: 40000, text: "Late bookmark", bookmark: true)
    try store.gap(id, track: "screen", start: 42000, end: 60000, reason: "Screen unavailable")
    let recovered = try CaptureStore(root: root)
    let session = recovered.history()[0]
    XCTAssertEqual(session.duration, 60000)
    let body =
      try JSONSerialization.jsonObject(with: recovered.body(session.uploads.last!))
      as! [String: Any]
    XCTAssertEqual(body["duration_ms"] as? Int, 60000)
  }
  func testRejectAudioAfterFinalize() throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Closed")
    try store.finish(id, duration: 0)
    XCTAssertThrowsError(try store.append(id, track: "mic", pcm: [1], rate: 16000, at: 0))
    let count = store.history()[0].uploads.count
    try store.finish(id, duration: 0)
    XCTAssertEqual(store.history()[0].uploads.count, count)
  }
  func testOversizedImageRejected() throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Image limits")
    XCTAssertThrowsError(
      try store.moment(
        id, at: 0, text: "", bookmark: false, jpeg: Data(repeating: 0, count: 2 * 1024 * 1024 + 1)))
  }
  func testVisibleHistoryScopesAccountAndService() throws {
    let store = try CaptureStore(root: root)
    let own = try store.begin(owner: "alice", server: "https://a.test", title: "Visible")
    _ = try store.begin(owner: "bob", server: "https://a.test", title: "Other account")
    _ = try store.begin(owner: "alice", server: "https://b.test", title: "Other service")
    XCTAssertEqual(store.history(owner: "alice", server: "https://a.test").map(\.id), [own])
  }
  func testDigestAndPKCEEncoding() {
    XCTAssertEqual(
      sha256(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    XCTAssertEqual(base64url(Data([255, 254, 253])), "__79")
    let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    XCTAssertEqual(
      base64url(Data(SHA256.hash(data: Data(verifier.utf8)))),
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  }
  func testOmiFragmentAssemblyAndSequenceWrap() {
    var framer = OmiFramer()
    XCTAssertNil(framer.receive(Data([254, 255, 0, 10, 11])).frame)
    XCTAssertNil(framer.receive(Data([255, 255, 1, 12])).frame)
    let next = framer.receive(Data([0, 0, 0, 20]))
    XCTAssertEqual(next.frame, Data([10, 11, 12]))
    XCTAssertFalse(next.lost)
    XCTAssertEqual(framer.drain(), Data([20]))
  }
  func testOmiLossDropsIncompletePacket() {
    var framer = OmiFramer()
    _ = framer.receive(Data([0, 0, 0, 1]))
    let lost = framer.receive(Data([2, 0, 1, 3]))
    XCTAssertTrue(lost.lost)
    XCTAssertNil(lost.frame)
    XCTAssertNil(framer.receive(Data([3, 0, 0, 4])).frame)
    XCTAssertEqual(framer.receive(Data([4, 0, 0, 5])).frame, Data([4]))
  }
  func testOmiMalformedNotificationDropsPendingFrame() {
    var framer = OmiFramer()
    _ = framer.receive(Data([0, 0, 0, 1, 2]))
    let malformed = framer.receive(Data([1, 0, 1]))
    XCTAssertTrue(malformed.lost)
    XCTAssertNil(malformed.frame)
    XCTAssertNil(framer.drain())
    let resumed = framer.receive(Data([2, 0, 0, 9]))
    XCTAssertFalse(resumed.lost)
    XCTAssertNil(resumed.frame)
    XCTAssertEqual(framer.receive(Data([3, 0, 0, 10])).frame, Data([9]))
  }
  func testCorruptIndexFailsClosed() throws {
    try Data("broken".utf8).write(to: root.appendingPathComponent("index.json"))
    XCTAssertThrowsError(try CaptureStore(root: root))
  }
}
