import CryptoKit
import Foundation

public struct Upload: Codable, Identifiable {
  public var id: UUID
  public var method: String
  public var path: String
  public var file: String
  public var mime: String
  public var acknowledged: Bool = false
  public init(method: String, path: String, file: String, mime: String) {
    id = UUID()
    self.method = method
    self.path = path
    self.file = file
    self.mime = mime
  }
}
public struct Session: Codable, Identifiable {
  public var id: UUID
  public var owner: String
  public var server: String
  public var title: String
  public var started: Date
  public var duration: Int = 0
  public var finalized: Bool = false
  public var retired: Bool? = nil
  public var uploads: [Upload] = []
  public var counts: [String: Int] = [:]
  public var pending: Int { uploads.filter { !$0.acknowledged }.count }
}
public struct OpenChunk: Codable {
  var session: UUID
  var track: String
  var sequence: Int
  var start: Int
  var end: Int
  var rate: Int
  var samples: Int
  var file: String
}
public enum StoreError: Error { case missingSession, closedSession, invalidAudio }
public final class CaptureStore: @unchecked Sendable {
  public let root: URL
  private let lock = NSRecursiveLock()
  private var sessions: [Session] = []
  private var active: [String: OpenChunk] = [:]
  private let encoder = JSONEncoder()
  private var lastCheckpoint = 0.0
  public init(root: URL) throws {
    self.root = root
    try FileManager.default.createDirectory(
      at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    encoder.dateEncodingStrategy = .iso8601
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    let index = root.appendingPathComponent("index.json")
    if FileManager.default.fileExists(atPath: index.path) {
      sessions = try decoder.decode([Session].self, from: Data(contentsOf: index))
    }
    let journal = root.appendingPathComponent("active.json")
    if FileManager.default.fileExists(atPath: journal.path) {
      active = try decoder.decode([String: OpenChunk].self, from: Data(contentsOf: journal))
    }
    // Acknowledgement is persisted before unlink. A crash can leave safe-to-delete leftovers.
    for item in sessions.flatMap({ $0.uploads }) where item.acknowledged {
      try? FileManager.default.removeItem(at: root.appendingPathComponent(item.file))
    }
    // A terminated capture cannot resume its monotonic clock. Seal durable samples and close it.
    for key in Array(active.keys) { try seal(key) }
    for s in sessions where !s.finalized && s.retired != true {
      let end = max(
        s.duration,
        s.uploads.compactMap {
          URLComponents(string: $0.path)?.queryItems?.first(where: { $0.name == "end_ms" })?.value
            .flatMap(Int.init)
        }.max() ?? 0)
      try gap(
        s.id, track: "mic", start: end, end: end,
        reason: "Application interrupted; only durable samples recovered")
      try finish(s.id, duration: end)
    }
  }
  public func history() -> [Session] {
    lock.lock()
    defer { lock.unlock() }
    return sessions.sorted { $0.started > $1.started }
  }
  public func history(owner: String, server: String) -> [Session] {
    history().filter { $0.owner == owner && $0.server == server }
  }
  private func persist() throws {
    try encoder.encode(sessions).write(
      to: root.appendingPathComponent("index.json"), options: .atomic)
    try checkpoint(force: true)
  }
  private func checkpoint(force: Bool = false) throws {
    let now = ProcessInfo.processInfo.systemUptime
    guard force || now - lastCheckpoint >= 1 else { return }
    for chunk in active.values {
      let handle = try FileHandle(forWritingTo: root.appendingPathComponent(chunk.file))
      try handle.write(contentsOf: waveHeader(samples: chunk.samples, rate: chunk.rate))
      try handle.synchronize()
      try handle.close()
    }
    try encoder.encode(active).write(
      to: root.appendingPathComponent("active.json"), options: .atomic)
    lastCheckpoint = now
  }
  private func index(_ id: UUID) throws -> Int {
    guard let i = sessions.firstIndex(where: { $0.id == id }) else {
      throw StoreError.missingSession
    }
    return i
  }
  private func enqueue(
    _ id: UUID, method: String = "PUT", path: String, data: Data, mime: String = "application/json"
  ) throws {
    let i = try index(id)
    let name = UUID().uuidString + (mime == "audio/wav" ? ".wav" : ".json")
    try data.write(to: root.appendingPathComponent(name), options: .atomic)
    sessions[i].uploads.append(Upload(method: method, path: path, file: name, mime: mime))
    try persist()
  }
  private func json(_ value: [String: Any]) throws -> Data {
    try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
  }
  public func begin(owner: String, server: String, title: String) throws -> UUID {
    lock.lock()
    defer { lock.unlock() }
    let s = Session(id: UUID(), owner: owner, server: server, title: title, started: Date())
    sessions.append(s)
    try enqueue(
      s.id, path: "/api/v1/sessions/\(s.id)",
      data: json([
        "title": title, "started_at": ISO8601DateFormatter().string(from: s.started),
        "device": "macos",
      ]))
    return s.id
  }
  public func moment(_ id: UUID, at: Int, text: String, bookmark: Bool, jpeg: Data? = nil) throws {
    lock.lock()
    defer { lock.unlock() }
    let i = try index(id)
    guard !sessions[i].finalized, sessions[i].retired != true else {
      throw StoreError.closedSession
    }
    sessions[i].duration = max(sessions[i].duration, at)
    var value: [String: Any] = ["at_ms": at, "text": text, "bookmarked": bookmark]
    if let jpeg {
      guard jpeg.count <= 2 * 1024 * 1024 else { throw StoreError.invalidAudio }
      value["image_base64"] = jpeg.base64EncodedString()
    }
    try enqueue(id, path: "/api/v1/sessions/\(id)/moments/\(UUID())", data: json(value))
  }
  public func gap(_ id: UUID, track: String, start: Int, end: Int, reason: String) throws {
    lock.lock()
    defer { lock.unlock() }
    let i = try index(id)
    guard !sessions[i].finalized, sessions[i].retired != true else {
      throw StoreError.closedSession
    }
    sessions[i].duration = max(sessions[i].duration, end)
    try enqueue(
      id, path: "/api/v1/sessions/\(id)/gaps/\(UUID())",
      data: json([
        "track": track, "start_ms": max(0, start), "end_ms": max(start, end), "reason": reason,
      ]))
  }
  public func append(_ id: UUID, track: String, pcm: [Int16], rate: Int, at: Int) throws {
    lock.lock()
    defer { lock.unlock() }
    guard !pcm.isEmpty, rate > 0 else { return }
    let i = try index(id)
    guard !sessions[i].finalized, sessions[i].retired != true else {
      throw StoreError.closedSession
    }
    let key = "\(id)-\(track)"
    if let old = active[key], old.rate != rate || at > old.end + 500 {
      try seal(key)
      if at > old.end + 500 {
        try gap(id, track: track, start: old.end, end: at, reason: "Audio sample discontinuity")
      }
    }
    var offset = 0
    while offset < pcm.count {
      if active[key] == nil {
        let seq = sessions[i].counts[track, default: 0]
        let filename = "\(id)-\(track)-\(seq).wav"
        active[key] = OpenChunk(
          session: id, track: track, sequence: seq, start: max(0, at + offset * 1000 / rate),
          end: at, rate: rate, samples: 0, file: filename)
        try waveHeader(samples: 0, rate: rate).write(
          to: root.appendingPathComponent(filename), options: .atomic)
        try persist()
      }
      var chunk = active[key]!
      let count = min(pcm.count - offset, rate * 20 - chunk.samples)
      let bytes = pcm[offset..<offset + count].flatMap { value -> [UInt8] in
        let x = UInt16(bitPattern: value)
        return [UInt8(x & 255), UInt8(x >> 8)]
      }
      let handle = try FileHandle(forWritingTo: root.appendingPathComponent(chunk.file))
      try handle.seekToEnd()
      try handle.write(contentsOf: Data(bytes))
      chunk.samples += count
      chunk.end = chunk.start + chunk.samples * 1000 / rate
      try handle.close()
      active[key] = chunk
      offset += count
      sessions[i].duration = max(sessions[i].duration, chunk.end)
      try checkpoint()
      if chunk.samples >= rate * 20 { try seal(key) }
    }
  }
  private func seal(_ key: String) throws {
    guard let c = active[key] else { return }
    let i = try index(c.session)
    // Reconcile the file length after a crash between append and journal persistence.
    let url = root.appendingPathComponent(c.file)
    let length = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 44
    let samples = max(0, (length - 44) / 2)
    if samples > 0 {
      let handle = try FileHandle(forWritingTo: url)
      try handle.write(contentsOf: waveHeader(samples: samples, rate: c.rate))
      try handle.synchronize()
      try handle.close()
      let end = c.start + samples * 1000 / c.rate
      let path =
        "/api/v1/sessions/\(c.session)/tracks/\(c.track)/chunks/\(c.sequence)?start_ms=\(c.start)&end_ms=\(end)"
      if !sessions[i].uploads.contains(where: { $0.path == path }) {
        sessions[i].uploads.append(
          Upload(method: "PUT", path: path, file: c.file, mime: "audio/wav"))
      }
      sessions[i].counts[c.track] = c.sequence + 1
    }
    active.removeValue(forKey: key)
    try persist()
  }
  public func flush(_ id: UUID, track: String? = nil) throws {
    lock.lock()
    defer { lock.unlock() }
    for key in Array(active.keys)
    where active[key]?.session == id && (track == nil || active[key]?.track == track) {
      try seal(key)
    }
  }
  public func finish(_ id: UUID, duration: Int) throws {
    lock.lock()
    defer { lock.unlock() }
    let i = try index(id)
    guard !sessions[i].finalized, sessions[i].retired != true else { return }
    try flush(id)
    let finalDuration = max(duration, sessions[i].duration)
    let data = try json(["duration_ms": finalDuration, "tracks": sessions[i].counts])
    sessions[i].duration = finalDuration
    sessions[i].finalized = true
    // The final queue item and closed state must share one atomic index write.
    try enqueue(id, method: "POST", path: "/api/v1/sessions/\(id)/finalize", data: data)
  }
  public func body(_ item: Upload) throws -> Data {
    try Data(contentsOf: root.appendingPathComponent(item.file))
  }
  public func acknowledge(session: UUID, upload: UUID) throws {
    lock.lock()
    defer { lock.unlock() }
    let i = try index(session)
    if let j = sessions[i].uploads.firstIndex(where: { $0.id == upload }) {
      sessions[i].uploads[j].acknowledged = true
      try persist()
      try? FileManager.default.removeItem(
        at: root.appendingPathComponent(sessions[i].uploads[j].file))
    }
  }
  public func retire(_ id: UUID) throws {
    lock.lock()
    defer { lock.unlock() }
    let i = try index(id)
    try flush(id)
    sessions[i].retired = true
    try persist()
    // Unacknowledged local bytes remain preserved for the owner, but are never retried.
  }
}
public func waveHeader(samples: Int, rate: Int) -> Data {
  var data = Data()
  func str(_ s: String) { data.append(s.data(using: .ascii)!) }
  func u16(_ n: Int) {
    var x = UInt16(n).littleEndian
    withUnsafeBytes(of: &x) { data.append(contentsOf: $0) }
  }
  func u32(_ n: Int) {
    var x = UInt32(n).littleEndian
    withUnsafeBytes(of: &x) { data.append(contentsOf: $0) }
  }
  str("RIFF")
  u32(36 + samples * 2)
  str("WAVEfmt ")
  u32(16)
  u16(1)
  u16(1)
  u32(rate)
  u32(rate * 2)
  u16(2)
  u16(16)
  str("data")
  u32(samples * 2)
  return data
}
public func sha256(_ data: Data) -> String {
  SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}
public func base64url(_ data: Data) -> String {
  data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(
    of: "/", with: "_"
  ).replacingOccurrences(of: "=", with: "")
}
public struct OmiFramer {
  private var previous: UInt16?
  private var frame = Data()
  private var expectedFragment: UInt8 = 0
  public init() {}
  public mutating func receive(_ packet: Data) -> (frame: Data?, lost: Bool) {
    guard packet.count > 3 else {
      previous = nil
      frame.removeAll()
      expectedFragment = 0
      return (nil, true)
    }
    let p = Array(packet)
    let sequence = UInt16(p[0]) | UInt16(p[1]) << 8
    let index = p[2]
    let lost = previous.map { sequence != $0 &+ 1 } ?? false
    previous = sequence
    if lost {
      frame.removeAll()
      expectedFragment = 0
    }
    var complete: Data?
    if index == 0 {
      if !frame.isEmpty && !lost { complete = frame }
      frame = Data(p.dropFirst(3))
      expectedFragment = 1
    } else if index == expectedFragment && !frame.isEmpty {
      frame.append(contentsOf: p.dropFirst(3))
      expectedFragment &+= 1
    } else {
      frame.removeAll()
      expectedFragment = 0
      return (nil, true)
    }
    if frame.count > 4096 {
      frame.removeAll()
      return (nil, true)
    }
    return (complete, lost)
  }
  public mutating func drain() -> Data? {
    defer { frame.removeAll() }
    return frame.isEmpty ? nil : frame
  }
}
