import AppKit
import CoreText
import Foundation
import SudoCore
import Vision
import XCTest

@testable import SudoAI

final class MockHTTP: URLProtocol {
  static var code = 204
  static var requests: [URLRequest] = []
  override class func canInit(with request: URLRequest) -> Bool { true }
  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
  override func startLoading() {
    Self.requests.append(request)
    client?.urlProtocol(
      self,
      didReceive: HTTPURLResponse(
        url: request.url!, statusCode: Self.code, httpVersion: nil, headerFields: nil)!,
      cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: Data())
    client?.urlProtocolDidFinishLoading(self)
  }
  override func stopLoading() {}
}
final class NativeTests: XCTestCase {
  var root: URL!
  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent("SudoAI-native-\(UUID())")
    MockHTTP.requests = []
    MockHTTP.code = 204
  }
  override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }
  func uploader(_ store: CaptureStore) -> Uploader {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [MockHTTP.self]
    return Uploader(store: store, session: URLSession(configuration: config))
  }
  func testOwnerAndOriginBoundUploadAndDurableAck() async throws {
    let store = try CaptureStore(root: root)
    let alice = try store.begin(owner: "alice", server: "https://a.test", title: "Owned")
    try store.append(alice, track: "mic", pcm: [1, 2, 3, 4], rate: 16000, at: 0)
    try store.finish(alice, duration: 1)
    let other = try store.begin(owner: "bob", server: "https://a.test", title: "Other account")
    try store.finish(other, duration: 0)
    let origin = try store.begin(owner: "alice", server: "https://b.test", title: "Other service")
    try store.finish(origin, duration: 0)
    let result = await uploader(store).run(
      Credential(token: "fixture", owner: "alice", name: "Alice", server: "https://a.test"))
    XCTAssertEqual(result, "Uploads up to date")
    XCTAssertEqual(MockHTTP.requests.count, 3)
    XCTAssertTrue(
      MockHTTP.requests.allSatisfy { $0.url!.absoluteString.contains(alice.uuidString) })
    XCTAssertTrue(MockHTTP.requests.last!.url!.path.hasSuffix("/finalize"))
    let chunk = try XCTUnwrap(
      MockHTTP.requests.first { $0.value(forHTTPHeaderField: "Content-Type") == "audio/wav" })
    XCTAssertEqual(chunk.value(forHTTPHeaderField: "X-Content-SHA256")?.count, 64)
    XCTAssertEqual(chunk.value(forHTTPHeaderField: "Authorization"), "Bearer fixture")
    XCTAssertEqual(store.history().first { $0.id == alice }?.pending, 0)
    XCTAssertEqual(store.history().first { $0.id == other }?.pending, 2)
    XCTAssertEqual(store.history().first { $0.id == origin }?.pending, 2)
  }
  func testExpiredTokenPreservesAllQueueEntriesAndBytes() async throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Expired")
    try store.finish(id, duration: 0)
    MockHTTP.code = 401
    let result = await uploader(store).run(
      Credential(token: "expired", owner: "a", name: "A", server: "https://a.test"))
    XCTAssertTrue(result.contains("Sign-in expired"))
    XCTAssertEqual(MockHTTP.requests.count, 1)
    XCTAssertEqual(store.history()[0].pending, 2)
    for item in store.history()[0].uploads { XCTAssertFalse(try store.body(item).isEmpty) }
  }
  func testConflictStopsBeforeFinalizeAndPreservesQueue() async throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Conflict")
    try store.finish(id, duration: 0)
    MockHTTP.code = 409
    let result = await uploader(store).run(
      Credential(token: "x", owner: "a", name: "A", server: "https://a.test"))
    XCTAssertTrue(result.contains("409"))
    XCTAssertEqual(MockHTTP.requests.count, 1)
    XCTAssertEqual(store.history()[0].pending, 2)
  }
  func testVisionRecognizesSyntheticMeetingFixture() throws {
    let context = try XCTUnwrap(
      CGContext(
        data: nil, width: 1200, height: 200, bitsPerComponent: 8, bytesPerRow: 4800,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    context.setFillColor(CGColor(gray: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: 1200, height: 200))
    let font = CTFontCreateWithName("Helvetica" as CFString, 48, nil)
    let text = NSAttributedString(
      string: "Project Orion launch Monday",
      attributes: [
        NSAttributedString.Key(kCTFontAttributeName as String): font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(
          gray: 0, alpha: 1),
      ])
    context.textPosition = CGPoint(x: 30, y: 80)
    CTLineDraw(CTLineCreateWithAttributedString(text), context)
    let image = try XCTUnwrap(context.makeImage())
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    try VNImageRequestHandler(cgImage: image).perform([request])
    let recognized =
      request.results?.compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ") ?? ""
    XCTAssertTrue(recognized.contains("Project Orion"), recognized)
  }
  func testDeletedSessionIsNeverResurrectedAndPendingBytesRemain() async throws {
    let store = try CaptureStore(root: root)
    let id = try store.begin(owner: "a", server: "https://a.test", title: "Deleted")
    try store.moment(id, at: 100, text: "Pending", bookmark: true)
    try store.finish(id, duration: 100)
    MockHTTP.code = 410
    let client = uploader(store)
    let credential = Credential(token: "x", owner: "a", name: "A", server: "https://a.test")
    _ = await client.run(credential)
    XCTAssertEqual(store.history()[0].retired, true)
    XCTAssertEqual(MockHTTP.requests.count, 1)
    for item in store.history()[0].uploads { XCTAssertFalse(try store.body(item).isEmpty) }
    MockHTTP.code = 204
    _ = await client.run(credential)
    XCTAssertEqual(MockHTTP.requests.count, 1)
    let recovered = try CaptureStore(root: root)
    _ = await uploader(recovered).run(credential)
    XCTAssertEqual(MockHTTP.requests.count, 1)
  }
  func testPinnedOpusDecodesCV1AndDevKitDurations() throws {
    let macos = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
      .deletingLastPathComponent().deletingLastPathComponent()
    let bundled = macos.appendingPathComponent(
      "dist/SudoAI.app/Contents/Frameworks/libopus.0.dylib")
    let candidates = [
      bundled,
      macos.deletingLastPathComponent().appendingPathComponent(
        "dist/SudoAI.app/Contents/Frameworks/libopus.0.dylib"),
      macos.appendingPathComponent(".cache/opus-1.5.2/.libs/libopus.0.dylib"),
    ]
    guard let library = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) })
    else {
      throw XCTSkip("Run scripts/package.sh to build pinned libopus first")
    }
    let decoder = try OpusDecoder(libraryURL: library)
    XCTAssertEqual(try decoder.pcm(Data([0xf8, 0xff, 0xfe])).count, 320)
    XCTAssertEqual(try decoder.pcm(Data([0xf0, 0xff, 0xfe])).count, 160)
    XCTAssertThrowsError(try decoder.pcm(Data([255])))
  }
}
