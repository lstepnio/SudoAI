import Foundation
import XCTest

@testable import SudoAI

private actor DeferredScreenStream: ScreenCaptureSession {
  private var started = false
  private var starter: CheckedContinuation<Void, Error>?
  private var waiting: CheckedContinuation<Void, Never>?
  private(set) var stops = 0
  func startCapture() async throws {
    try await withCheckedThrowingContinuation { continuation in
      starter = continuation
      started = true
      waiting?.resume()
      waiting = nil
    }
  }
  func waitForStart() async {
    if started { return }
    await withCheckedContinuation { waiting = $0 }
  }
  func finishStart() {
    starter?.resume()
    starter = nil
  }
  func stopCapture() async throws { stops += 1 }
}

private final class ScreenLifecycleEvents: @unchecked Sendable {
  private let lock = NSLock()
  private var active: [ObjectIdentifier?] = []
  private var messages: [String] = []
  func changed(_ stream: DeferredScreenStream?) {
    lock.lock()
    defer { lock.unlock() }
    active.append(stream.map(ObjectIdentifier.init))
  }
  func status(_ text: String) {
    lock.lock()
    defer { lock.unlock() }
    messages.append(text)
  }
  func snapshot() -> ([ObjectIdentifier?], [String]) {
    lock.lock()
    defer { lock.unlock() }
    return (active, messages)
  }
}

private final class SyntheticPicker: @unchecked Sendable {
  private let lock = NSLock()
  private var enabled = true
  func setEnabled(_ value: Bool) {
    lock.lock()
    defer { lock.unlock() }
    enabled = value
  }
  func isEnabled() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return enabled
  }
}

final class ScreenLifecycleTests: XCTestCase {
  func testReenabledPickerSurvivesPreviousAsynchronousStop() async {
    let picker = SyntheticPicker()
    let stopping = DeferredScreenStream()
    let stop = Task {
      await endScreenSelection(
        endPicker: { picker.setEnabled(false) },
        stopStream: { try? await stopping.startCapture() })
    }
    // The fake stream suspends cleanup just as stopCapture can suspend.
    await stopping.waitForStart()
    XCTAssertFalse(picker.isEnabled())
    picker.setEnabled(true)
    await stopping.finishStart()
    await stop.value
    XCTAssertTrue(picker.isEnabled(), "An older stop must not tear down a new picker lease")
  }

  func testStopWhileStartingCleansUpLateStreamWithoutAnnouncingActive() async {
    let events = ScreenLifecycleEvents()
    let lifecycle = ScreenCaptureLifecycle<DeferredScreenStream>(
      changed: events.changed, status: events.status)
    let stream = DeferredScreenStream()
    let start = Task { await lifecycle.replace { stream } }
    await stream.waitForStart()
    await lifecycle.stop()
    await stream.finishStart()
    await start.value
    let stops = await stream.stops
    XCTAssertEqual(stops, 2, "A late start must be stopped again after completion")
    let (changes, statuses) = events.snapshot()
    XCTAssertTrue(changes.allSatisfy { $0 == nil })
    XCTAssertTrue(statuses.isEmpty, "A cancelled start must not claim screen capture is active")
  }

  func testReselectionKeepsNewStreamWhenOlderStartCompletesLast() async {
    let events = ScreenLifecycleEvents()
    let lifecycle = ScreenCaptureLifecycle<DeferredScreenStream>(
      changed: events.changed, status: events.status)
    let old = DeferredScreenStream()
    let current = DeferredScreenStream()
    let first = Task { await lifecycle.replace { old } }
    await old.waitForStart()
    let second = Task { await lifecycle.replace { current } }
    await current.waitForStart()
    await current.finishStart()
    await second.value
    await old.finishStart()
    await first.value
    let (changes, statuses) = events.snapshot()
    XCTAssertEqual(changes.compactMap { $0 }, [ObjectIdentifier(current)])
    XCTAssertEqual(statuses, ["Screen moments active"])
    let oldStops = await old.stops
    let currentStops = await current.stops
    XCTAssertEqual(oldStops, 2)
    XCTAssertEqual(currentStops, 0)
    await lifecycle.stop()
    let finalStops = await current.stops
    XCTAssertEqual(finalStops, 1)
  }
}
