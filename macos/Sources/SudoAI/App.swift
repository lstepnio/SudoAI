import AppKit
import CoreAudio
import SudoCore
import SwiftUI

final class CaptureSink: @unchecked Sendable {
  let store: CaptureStore
  private let queue = DispatchQueue(label: "com.majjix.sudoai.spool", qos: .utility)
  private var id: UUID?
  private var origin = 0.0
  private var paused = false
  var failure: ((String) -> Void)?
  init(store: CaptureStore) { self.store = store }
  func configure(id: UUID?, origin: Double, paused: Bool) {
    queue.sync {
      self.id = id
      self.origin = origin
      self.paused = paused
    }
  }
  func receive(_ pcm: [Int16], rate: Int, track: String) {
    let now = ProcessInfo.processInfo.systemUptime
    queue.async {
      guard let id = self.id, !self.paused else { return }
      let at = max(0, Int((now - self.origin) * 1000) - pcm.count * 1000 / rate)
      do { try self.store.append(id, track: track, pcm: pcm, rate: rate, at: at) } catch {
        self.failure?(
          "Local storage failed: \(error.localizedDescription). Stop capture and check free space.")
      }
    }
  }
  func drain() { queue.sync {} }
}
@MainActor final class Model: ObservableObject {
  let auth = Auth()
  let store: CaptureStore
  let sink: CaptureSink
  let uploader: Uploader
  private let mic = AudioInput()
  private let teams = TeamsTap()
  private let screen = ScreenMoments()
  private let omi = Omi()
  @Published var session: UUID?
  @Published var paused = false
  @Published var title = "Meeting"
  @Published var micEnabled = true
  @Published var teamsEnabled = false
  @Published var omiEnabled = false
  @Published var screenEnabled = false
  @Published var devices: [Microphone] = []
  @Published var selectedMic: AudioObjectID = 0
  @Published var status = "Ready"
  @Published var screenStatus = "Off; choose a window or display to opt in"
  @Published var omiStatus = "Released"
  @Published var omiDevices: [OmiDevice] = []
  @Published var states: [String: String] = [:]
  @Published var levels: [String: Double] = [:]
  @Published var history: [Session] = []
  @Published var elapsed = 0
  @Published var busy = false
  @Published var bookmarkText = ""
  private var origin = 0.0
  private var pauseAt = 0
  private var pauseTracks: [String] = []
  private var gapStarts: [String: Int] = [:]
  private var gapReasons: [String: String] = [:]
  private var lastAudio: [String: Double] = [:]
  private var lastUpdate: [String: Double] = [:]
  private var timer: Timer?
  private var retryTask: Task<Void, Never>?
  private var fatalStorageError = false
  init() {
    do {
      let path = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("SudoAI/Captures")
      store = try CaptureStore(root: path)
    } catch {
      fatalError("Cannot safely open SudoAI capture storage: \(error.localizedDescription)")
    }
    sink = CaptureSink(store: store)
    uploader = Uploader(store: store)
    devices = microphones()
    history = auth.credential.map { store.history(owner: $0.owner, server: $0.server) } ?? []
    sink.failure = { [weak self] message in
      Task { @MainActor in
        self?.status = message
        self?.fatalStorageError = true
        await self?.stop()
      }
    }
    mic.receive = { [weak self] pcm, rate in self?.audio(pcm, rate: rate, track: "mic") }
    teams.receive = { [weak self] pcm, rate in self?.audio(pcm, rate: rate, track: "system") }
    omi.found = { [weak self] devices in Task { @MainActor in self?.omiDevices = devices } }
    omi.receive = { [weak self] pcm, rate in self?.audio(pcm, rate: rate, track: "omi") }
    teams.status = { [weak self] text in
      Task { @MainActor in self?.setSourceStatus("system", text) }
    }
    omi.status = { [weak self] text in
      Task { @MainActor in
        self?.omiStatus = text
        if text.lowercased().contains("gap") || text.lowercased().contains("blocked")
          || text.lowercased().contains("disconnected")
        {
          self?.setSourceStatus("omi", text)
        }
      }
    }
    screen.status = { [weak self] text in
      Task { @MainActor in
        self?.screenStatus = text
        if text.lowercased().contains("gap") || text.lowercased().contains("blocked")
          || text.lowercased().contains("unavailable")
        {
          self?.setSourceStatus("screen", text)
        }
      }
    }
    screen.receive = { [weak self] image, text in
      Task { @MainActor in
        guard let self, let id = self.session, !self.paused, self.screenEnabled else { return }
        do {
          try self.store.moment(
            id, at: self.offset, text: text, bookmark: false, jpeg: image.isEmpty ? nil : image)
          self.closeGap("screen")
          self.refresh()
        } catch { self.status = error.localizedDescription }
      }
    }
    timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
      Task { @MainActor in self?.tick() }
    }
    retryTask = Task { [weak self] in
      var delay: UInt64 = 5
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
        guard let self else { return }
        if let c = self.auth.credential {
          let result = await self.uploader.run(c)
          self.status = result
          self.refresh()
          delay = result == "Uploads up to date" ? 15 : min(300, delay * 2)
        }
      }
    }
  }
  nonisolated private func audio(_ pcm: [Int16], rate: Int, track: String) {
    sink.receive(pcm, rate: rate, track: track)
    let sum = pcm.reduce(0.0) { $0 + pow(Double($1) / 32768, 2) }
    let level = min(1, sqrt(sum / Double(max(1, pcm.count))) * 5)
    Task { @MainActor in
      let now = ProcessInfo.processInfo.systemUptime
      self.lastAudio[track] = now
      guard now - (self.lastUpdate[track] ?? 0) > 0.15 else { return }
      self.lastUpdate[track] = now
      self.levels[track] = level
      if self.session != nil, !self.paused {
        self.states[track] = level < 0.005 ? "Silent or source muted" : "Receiving"
        self.closeGap(track)
      }
    }
  }
  var offset: Int { max(0, Int((ProcessInfo.processInfo.systemUptime - origin) * 1000)) }
  private func tick() {
    if let session {
      if store.history().first(where: { $0.id == session })?.retired == true {
        status = "This session was deleted on the service; capture stopped"
        Task { await stop() }
        return
      }
      elapsed = offset / 1000
      if !paused {
        for (track, on) in [("mic", micEnabled), ("system", teamsEnabled), ("omi", omiEnabled)]
        where on && ProcessInfo.processInfo.systemUptime - (lastAudio[track] ?? origin) > 5 {
          setSourceStatus(track, "No samples; capture gap")
        }
      }
    }
  }
  func refresh() {
    history = auth.credential.map { store.history(owner: $0.owner, server: $0.server) } ?? []
  }
  func start() async {
    guard session == nil, !busy, let credential = auth.credential else {
      status = "Sign in before recording"
      return
    }
    guard micEnabled || teamsEnabled || omiEnabled || screenEnabled else {
      status = "Choose at least one source"
      return
    }
    busy = true
    defer { busy = false }
    do {
      let id = try store.begin(
        owner: credential.owner, server: credential.server, title: title.isEmpty ? "Meeting" : title
      )
      session = id
      origin = ProcessInfo.processInfo.systemUptime
      elapsed = 0
      paused = false
      fatalStorageError = false
      lastAudio = [:]
      sink.configure(id: id, origin: origin, paused: false)
      if micEnabled { await enableMic(true) }
      if teamsEnabled { enableTeams(true) }
      if omiEnabled { enableOmi(true) }
      if screenEnabled { screen.choose() }
      status = "Recording locally"
      refresh()
    } catch { status = error.localizedDescription }
  }
  func stop() async {
    guard let id = session else { return }
    busy = true
    defer { busy = false }
    let duration = offset
    mic.stop()
    teams.stop()
    omi.release()
    screenEnabled = false
    await screen.stop()
    sink.drain()
    sink.configure(id: nil, origin: origin, paused: true)
    do {
      if paused {
        for track in pauseTracks {
          try store.gap(id, track: track, start: pauseAt, end: duration, reason: "Paused by user")
        }
      }
      for (track, start) in gapStarts {
        try store.gap(
          id, track: track, start: start, end: duration,
          reason: gapReasons[track] ?? "Source unavailable")
      }
      try store.finish(id, duration: duration)
      if !fatalStorageError { status = "Saved locally; queued for upload" }
    } catch { status = "Local save error: \(error.localizedDescription)" }
    session = nil
    paused = false
    screenEnabled = false
    gapStarts = [:]
    gapReasons = [:]
    levels = [:]
    refresh()
  }
  var enabledTracks: [String] {
    [("mic", micEnabled), ("system", teamsEnabled), ("omi", omiEnabled), ("screen", screenEnabled)]
      .filter { $0.1 }.map { $0.0 }
  }
  func togglePause() {
    guard let id = session else { return }
    paused.toggle()
    sink.configure(id: id, origin: origin, paused: paused)
    screen.setPaused(paused)
    if paused {
      pauseAt = offset
      pauseTracks = enabledTracks
      try? store.flush(id)
      status = "Paused; elapsed timeline continues"
    } else {
      for track in pauseTracks {
        try? store.gap(id, track: track, start: pauseAt, end: offset, reason: "Paused by user")
      }
      status = "Recording locally"
    }
  }
  func bookmark() {
    guard let id = session else { return }
    do {
      try store.moment(
        id, at: offset, text: bookmarkText.isEmpty ? "Bookmark" : bookmarkText, bookmark: true)
      bookmarkText = ""
      status = "Bookmark saved"
      refresh()
    } catch { status = error.localizedDescription }
  }
  private func setSourceStatus(_ track: String, _ text: String) {
    states[track] = text
    if session != nil, gapStarts[track] == nil {
      gapStarts[track] = offset
      gapReasons[track] = text
    }
  }
  private func closeGap(_ track: String) {
    if let id = session, let start = gapStarts.removeValue(forKey: track) {
      try? store.gap(
        id, track: track, start: start, end: offset,
        reason: gapReasons.removeValue(forKey: track) ?? "Source unavailable")
    }
  }
  func enableMic(_ on: Bool) async {
    micEnabled = on
    guard session != nil else { return }
    if on {
      do {
        try await mic.start(device: selectedMic == 0 ? nil : selectedMic)
        states["mic"] = "Waiting for microphone"
      } catch { setSourceStatus("mic", error.localizedDescription) }
    } else {
      mic.stop()
      sink.drain()
      if let id = session { try? store.flush(id, track: "mic") }
      setSourceStatus("mic", "Microphone muted by user")
    }
  }
  func enableTeams(_ on: Bool) {
    teamsEnabled = on
    guard session != nil else { return }
    if on {
      do {
        try teams.start()
        states["system"] = "Waiting for Teams audio"
      } catch { setSourceStatus("system", error.localizedDescription) }
    } else {
      teams.stop()
      sink.drain()
      if let id = session { try? store.flush(id, track: "system") }
      setSourceStatus("system", "Teams capture muted by user")
    }
  }
  func connectOmi(_ id: UUID) { omi.connect(id) }
  func enableOmi(_ on: Bool) {
    omiEnabled = on
    guard session != nil else { return }
    if on {
      do { try omi.claim() } catch {
        omi.release()
        setSourceStatus("omi", error.localizedDescription)
        omiStatus = error.localizedDescription
      }
    } else {
      omi.release()
      sink.drain()
      if let id = session { try? store.flush(id, track: "omi") }
      setSourceStatus("omi", "Omi released by user")
    }
  }
  func enableScreen(_ on: Bool) async {
    screenEnabled = on
    if on, session != nil {
      screen.choose()
    } else if !on {
      await screen.stop()
      setSourceStatus("screen", "Screen capture disabled by user")
    }
  }
  func sync() async {
    guard let c = auth.credential else { return }
    status = await uploader.run(c)
    refresh()
  }
  func open(_ s: Session) { NSWorkspace.shared.open(URL(string: s.server + "/sessions/\(s.id)")!) }
  func folder() { NSWorkspace.shared.open(store.root) }
}
struct MainView: View {
  @ObservedObject var model: Model
  @ObservedObject var auth: Auth
  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      HStack {
        Text("SudoAI").font(.title2.bold())
        Spacer()
        if model.session != nil {
          Text(String(format: "%02d:%02d", model.elapsed / 60, model.elapsed % 60))
            .monospacedDigit().foregroundStyle(model.paused ? .orange : .red)
        }
      }
      if model.session == nil { TextField("Meeting title", text: $model.title) }
      Toggle(
        "Microphone / headset",
        isOn: Binding(
          get: { model.micEnabled }, set: { value in Task { await model.enableMic(value) } }))
      Picker("Input", selection: $model.selectedMic) {
        Text("Current default input").tag(AudioObjectID(0))
        ForEach(model.devices) { Text($0.name).tag($0.id) }
      }.disabled(model.session != nil)
      sourceMeter("mic")
      Toggle(
        "Microsoft Teams output",
        isOn: Binding(get: { model.teamsEnabled }, set: { model.enableTeams($0) }))
      sourceMeter("system")
      Toggle(
        "Omi direct Bluetooth",
        isOn: Binding(get: { model.omiEnabled }, set: { model.enableOmi($0) }))
      if model.omiEnabled {
        Text(
          "Disconnect stock Omi and phone first. This Mac owns the BLE connection until you switch this off or stop."
        ).font(.caption).foregroundStyle(.secondary)
        Text(model.omiStatus).font(.caption)
        ForEach(model.omiDevices) { device in
          Button("Connect \(device.name) (\(device.id.uuidString.suffix(4)))") {
            model.connectOmi(device.id)
          }
        }
        sourceMeter("omi")
      }
      Toggle(
        "Screen moments + local OCR",
        isOn: Binding(
          get: { model.screenEnabled }, set: { value in Task { await model.enableScreen(value) } }))
      if model.screenEnabled {
        Text(model.screenStatus).font(.caption)
        if model.session != nil {
          Button("Choose window or display") { Task { await model.enableScreen(true) } }
        }
      }
      HStack {
        if model.session == nil {
          Button("Start recording") { Task { await model.start() } }.buttonStyle(.borderedProminent)
            .disabled(auth.credential == nil || model.busy)
        } else {
          Button(model.paused ? "Resume" : "Pause") { model.togglePause() }
          Button("Stop & save") { Task { await model.stop() } }.tint(.red)
        }
        Spacer()
        Button("Sync") { Task { await model.sync() } }
      }.disabled(model.busy)
      if model.session != nil {
        HStack {
          TextField("Bookmark note", text: $model.bookmarkText)
          Button {
            model.bookmark()
          } label: {
            Image(systemName: "bookmark.fill")
          }
        }
      }
      Text(model.status).font(.caption).textSelection(.enabled)
      Divider()
      DisclosureGroup("History (\(model.history.count))") {
        ScrollView {
          VStack(alignment: .leading, spacing: 8) {
            ForEach(model.history) { s in
              HStack {
                VStack(alignment: .leading) {
                  Text(s.title)
                  Text(
                    "\(s.started.formatted(date:.abbreviated,time:.shortened)) · \(s.retired == true ? "Deleted on service" : "\(s.pending) pending") · \(s.duration/1000)s"
                  ).font(.caption).foregroundStyle(.secondary)
                  if s.owner != auth.credential?.owner || s.server != auth.credential?.server {
                    Text("Waiting for original account").font(.caption).foregroundStyle(.orange)
                  }
                }
                Spacer()
                Button("Open") { model.open(s) }
              }
            }
          }
        }.frame(maxHeight: 170)
        Button("Show local files") { model.folder() }
      }
      DisclosureGroup("Account & service") {
        VStack(alignment: .leading) {
          TextField("HTTPS service", text: $auth.server).disabled(model.session != nil)
          Text(auth.status).font(.caption)
          HStack {
            Button("Sign in") { auth.signIn() }.disabled(model.session != nil)
            if auth.credential != nil {
              Button("Sign out") {
                Task {
                  await auth.signOut()
                  model.refresh()
                }
              }.disabled(model.session != nil)
            }
            Button("Open library") {
              if let url = try? auth.validatedServer() { NSWorkspace.shared.open(url) }
            }
          }
        }
      }
      HStack {
        Text("Sources remain separate. System defaults stay unchanged.").font(.caption2)
          .foregroundStyle(.secondary)
        Spacer()
        Button("Quit") {
          Task {
            await model.stop()
            NSApp.terminate(nil)
          }
        }
      }
    }.padding(18).frame(width: 430)
  }
  @ViewBuilder func sourceMeter(_ track: String) -> some View {
    if model.session != nil {
      HStack {
        ProgressView(value: model.levels[track] ?? 0).frame(width: 100)
        Text(model.states[track] ?? "Off").font(.caption).lineLimit(2)
      }
    }
  }
}
final class Delegate: NSObject, NSApplicationDelegate {
  var model: Model?
  func application(_ application: NSApplication, open urls: [URL]) {
    guard let model else { return }
    for url in urls {
      Task { @MainActor in
        await model.auth.callback(url)
        await model.sync()
      }
    }
  }
  func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
    guard let model, model.session != nil else { return .terminateNow }
    Task { @MainActor in
      await model.stop()
      sender.reply(toApplicationShouldTerminate: true)
    }
    return .terminateLater
  }
}
@main struct SudoApp: App {
  @NSApplicationDelegateAdaptor(Delegate.self) var delegate
  @StateObject private var model = Model()
  var body: some Scene {
    MenuBarExtra {
      MainView(model: model, auth: model.auth).onAppear { delegate.model = model }
    } label: {
      Image(
        systemName: model.session == nil
          ? "waveform.circle" : (model.paused ? "pause.circle.fill" : "record.circle.fill"))
    }.menuBarExtraStyle(.window)
  }
}
