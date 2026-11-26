import CoreBluetooth
import Darwin
import Foundation
import SudoCore

// Dynamic loading keeps the app independent of Homebrew. Packaging embeds pinned libopus.
final class OpusDecoder {
  typealias Create = @convention(c) (Int32, Int32, UnsafeMutablePointer<Int32>?) -> OpaquePointer?
  typealias Decode =
    @convention(c) (
      OpaquePointer?, UnsafePointer<UInt8>?, Int32, UnsafeMutablePointer<Int16>?, Int32, Int32
    ) -> Int32
  typealias Destroy = @convention(c) (OpaquePointer?) -> Void
  private var library: UnsafeMutableRawPointer
  private var decoder: OpaquePointer
  private var decode: Decode
  private var destroy: Destroy
  init(libraryURL: URL? = nil) throws {
    let path =
      libraryURL?.path ?? Bundle.main.privateFrameworksPath.map { $0 + "/libopus.0.dylib" } ?? ""
    guard let lib = dlopen(path, RTLD_NOW | RTLD_LOCAL), let c = dlsym(lib, "opus_decoder_create"),
      let d = dlsym(lib, "opus_decode"), let x = dlsym(lib, "opus_decoder_destroy")
    else { throw AppError.message("Bundled libopus unavailable. Build with scripts/package.sh.") }
    library = lib
    decode = unsafeBitCast(d, to: Decode.self)
    destroy = unsafeBitCast(x, to: Destroy.self)
    var error: Int32 = 0
    guard let instance = unsafeBitCast(c, to: Create.self)(16000, 1, &error), error == 0 else {
      dlclose(lib)
      throw AppError.message("Opus decoder initialization failed")
    }
    decoder = instance
  }
  func pcm(_ packet: Data) throws -> [Int16] {
    var result = [Int16](repeating: 0, count: 1920)
    let count = packet.withUnsafeBytes { bytes in
      decode(
        decoder, bytes.bindMemory(to: UInt8.self).baseAddress, Int32(packet.count), &result, 1920, 0
      )
    }
    guard count > 0 else { throw AppError.message("Invalid Omi Opus packet (\(count))") }
    return Array(result.prefix(Int(count)))
  }
  deinit {
    destroy(decoder)
    dlclose(library)
  }
}
struct OmiDevice: Identifiable {
  let id: UUID
  let name: String
}
final class Omi: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
  static let service = CBUUID(string: "19b10000-e8f2-537e-4f6c-d104768a1214")
  private let audio = CBUUID(string: "19b10001-e8f2-537e-4f6c-d104768a1214")
  private let codecID = CBUUID(string: "19b10002-e8f2-537e-4f6c-d104768a1214")
  private var central: CBCentralManager?
  private var peripheral: CBPeripheral?
  private var discovered: [UUID: CBPeripheral] = [:]
  private var selected: UUID?
  var found: (([OmiDevice]) -> Void)?
  private var codec: UInt8?
  private var decoder: OpusDecoder?
  private var framer = OmiFramer()
  private var audioCharacteristic: CBCharacteristic?
  private var ownership = false
  private var lockHandle: Int32 = -1
  private var watchdog: Timer?
  private var lastPacket = Date()
  var receive: (([Int16], Int) -> Void)?
  var status: ((String) -> Void)?
  func claim() throws {
    guard !ownership else { return }
    // Advisory cross-process ownership. Other platform owners must be explicitly released by the user.
    let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("SudoAI")
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    lockHandle = open(root.appendingPathComponent("omi-owner.lock").path, O_CREAT | O_RDWR, 0o600)
    guard lockHandle >= 0, flock(lockHandle, LOCK_EX | LOCK_NB) == 0 else {
      if lockHandle >= 0 { close(lockHandle) }
      lockHandle = -1
      throw AppError.message("Another SudoAI process owns Omi. Release it there first.")
    }
    do {
      decoder = try OpusDecoder()
    } catch {
      flock(lockHandle, LOCK_UN)
      close(lockHandle)
      lockHandle = -1
      throw error
    }
    ownership = true
    central = CBCentralManager(delegate: self, queue: .main)
    status?("Omi ownership claimed. Disconnect stock Omi/phone before connecting here.")
    watchdog = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
      guard let self, self.ownership, self.peripheral != nil else { return }
      if Date().timeIntervalSince(self.lastPacket) > 5 {
        self.status?("Omi audio gap: no packets for five seconds")
      }
    }
  }
  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    guard ownership else { return }
    if central.state == .poweredOn {
      central.scanForPeripherals(
        withServices: [Self.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
      )
      status?("Scanning for Omi; this Mac is the connection owner")
    } else {
      status?("Omi Bluetooth blocked or unavailable (\(central.state.rawValue))")
    }
  }
  func centralManager(
    _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
    advertisementData: [String: Any], rssi: NSNumber
  ) {
    guard ownership else { return }
    discovered[peripheral.identifier] = peripheral
    found?(
      discovered.values.map { OmiDevice(id: $0.identifier, name: $0.name ?? "Omi") }.sorted {
        $0.name < $1.name
      })
    if selected == peripheral.identifier, self.peripheral == nil { connect(peripheral.identifier) }
  }
  func connect(_ id: UUID) {
    guard ownership, peripheral == nil, let target = discovered[id] else { return }
    selected = id
    peripheral = target
    central?.stopScan()
    target.delegate = self
    central?.connect(target)
    status?("Connecting to \(target.name ?? "Omi")")
  }
  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    guard ownership, self.peripheral === peripheral else { return }
    lastPacket = Date()
    peripheral.discoverServices([Self.service])
  }
  func centralManager(
    _ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?
  ) {
    guard ownership, self.peripheral === peripheral else { return }
    self.peripheral = nil
    status?("Omi connection failed; release and retry: \(error?.localizedDescription ?? "unknown")")
  }
  func centralManager(
    _ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?
  ) {
    guard ownership, self.peripheral === peripheral else { return }
    codec = nil
    audioCharacteristic = nil
    self.peripheral = nil
    framer = OmiFramer()
    status?("Omi disconnected; capture gap")
    if ownership { central.scanForPeripherals(withServices: [Self.service]) }
  }
  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    guard ownership, self.peripheral === peripheral else { return }
    if let error {
      status?(error.localizedDescription)
      return
    }
    for service in peripheral.services ?? [] where service.uuid == Self.service {
      peripheral.discoverCharacteristics([audio, codecID], for: service)
    }
  }
  func peripheral(
    _ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?
  ) {
    guard ownership, self.peripheral === peripheral else { return }
    if let error {
      status?(error.localizedDescription)
      return
    }
    audioCharacteristic = service.characteristics?.first { $0.uuid == audio }
    if let codec = service.characteristics?.first(where: { $0.uuid == codecID }) {
      peripheral.readValue(for: codec)
    } else {
      status?("Omi codec characteristic missing; stream blocked")
    }
  }
  func peripheral(
    _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
    error: Error?
  ) {
    guard ownership, self.peripheral === peripheral else { return }
    if let error { status?("Omi notifications blocked: \(error.localizedDescription)") }
  }
  func peripheral(
    _ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?
  ) {
    guard ownership, self.peripheral === peripheral else { return }
    if let error {
      status?("Omi gap: \(error.localizedDescription)")
      return
    }
    guard let data = characteristic.value else { return }
    if characteristic.uuid == codecID {
      guard let value = data.first, [UInt8(0), 20, 21].contains(value) else {
        status?("Unsupported Omi codec; blocked")
        return
      }
      codec = value
      if let audioCharacteristic { peripheral.setNotifyValue(true, for: audioCharacteristic) }
      status?("Omi codec \(value) active (16 kHz mono)")
      return
    }
    guard characteristic.uuid == audio, codec != nil else { return }
    lastPacket = Date()
    let result = framer.receive(data)
    if result.lost { status?("Omi packet loss; capture gap") }
    if let frame = result.frame { decode(frame) }
  }
  private func decode(_ frame: Data) {
    do {
      if codec == 0 {
        let bytes = Array(frame)
        guard bytes.count % 2 == 0 else { throw AppError.message("Omi PCM alignment gap") }
        receive?(
          stride(from: 0, to: bytes.count, by: 2).map {
            Int16(bitPattern: UInt16(bytes[$0]) | UInt16(bytes[$0 + 1]) << 8)
          }, 16000)
      } else if let decoder {
        receive?(try decoder.pcm(frame), 16000)
      }
    } catch { status?("Omi decode gap: \(error.localizedDescription)") }
  }
  func release() {
    ownership = false
    selected = nil
    discovered = [:]
    found?([])
    watchdog?.invalidate()
    watchdog = nil
    if let frame = framer.drain() { decode(frame) }
    central?.stopScan()
    if let peripheral {
      if let audioCharacteristic { peripheral.setNotifyValue(false, for: audioCharacteristic) }
      central?.cancelPeripheralConnection(peripheral)
    }
    peripheral = nil
    codec = nil
    decoder = nil
    audioCharacteristic = nil
    framer = OmiFramer()
    central = nil
    if lockHandle >= 0 {
      flock(lockHandle, LOCK_UN)
      close(lockHandle)
      lockHandle = -1
    }
    status?("Omi released. Connect from stock Omi or your phone now.")
  }
}
