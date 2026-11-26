import AppKit
import CryptoKit
import Foundation
import Security
import SudoCore

final class OriginRedirects: NSObject, URLSessionTaskDelegate {
  func urlSession(
    _ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
    completionHandler: @escaping (URLRequest?) -> Void
  ) {
    guard let original = task.originalRequest?.url, let next = request.url,
      next.scheme == "https", original.host == next.host, original.port == next.port
    else {
      completionHandler(nil)
      return
    }
    completionHandler(request)
  }
}
enum HTTP {
  static let session = URLSession(
    configuration: .ephemeral, delegate: OriginRedirects(), delegateQueue: nil)
}
struct Credential: Codable {
  var token: String
  var owner: String
  var name: String
  var server: String
}
enum AppError: LocalizedError {
  case message(String)
  var errorDescription: String? {
    if case .message(let s) = self { return s }
    return nil
  }
}
enum Vault {
  private static var query: [String: Any] {
    [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "com.majjix.sudoai.native",
      kSecAttrAccount as String: "native-token",
    ]
  }
  static func load() throws -> Credential? {
    var q = query
    q[kSecReturnData as String] = true
    q[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    let status = SecItemCopyMatching(q as CFDictionary, &result)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess, let data = result as? Data else {
      throw AppError.message("Keychain unavailable (\(status))")
    }
    return try JSONDecoder().decode(Credential.self, from: data)
  }
  static func save(_ credential: Credential) throws {
    let data = try JSONEncoder().encode(credential)
    let status = SecItemUpdate(
      query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
    if status == errSecItemNotFound {
      var q = query
      q[kSecValueData as String] = data
      q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
      let s = SecItemAdd(q as CFDictionary, nil)
      guard s == errSecSuccess else { throw AppError.message("Cannot save Keychain token (\(s))") }
    } else if status != errSecSuccess {
      throw AppError.message("Cannot update Keychain token (\(status))")
    }
  }
  static func clear() { SecItemDelete(query as CFDictionary) }
}
@MainActor final class Auth: ObservableObject {
  @Published var credential: Credential?
  @Published var server: String =
    UserDefaults.standard.string(forKey: "server") ?? "https://sudoai.example.com"
  @Published var status = "Sign in to bind captures to your account"
  private var flowServer: String?
  private var verifier: String?
  private var state: String?
  private var began: Date?
  init() {
    do {
      credential = try Vault.load()
      if let credential {
        server = credential.server
        status = "Signed in as \(credential.name)"
      }
    } catch { status = error.localizedDescription }
  }
  func validatedServer() throws -> URL {
    guard let url = URL(string: server), url.scheme == "https", url.host != nil, url.user == nil,
      url.password == nil, url.query == nil, url.fragment == nil,
      url.path.isEmpty || url.path == "/"
    else {
      throw AppError.message("Enter an HTTPS service origin, such as https://sudoai.example.com")
    }
    return url
  }
  func signIn() {
    do {
      let base = try validatedServer()
      server = base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
      UserDefaults.standard.set(server, forKey: "server")
      var bytes = [UInt8](repeating: 0, count: 32)
      guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
        throw AppError.message("Secure randomness unavailable")
      }
      let v = base64url(Data(bytes))
      verifier = v
      flowServer = server
      guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
        throw AppError.message("Secure randomness unavailable")
      }
      let s = base64url(Data(bytes))
      state = s
      began = Date()
      var components = URLComponents(
        url: base.appendingPathComponent("auth/native/start"), resolvingAgainstBaseURL: false)!
      components.queryItems = [
        URLQueryItem(name: "platform", value: "macos"),
        URLQueryItem(name: "challenge", value: base64url(Data(SHA256.hash(data: Data(v.utf8))))),
        URLQueryItem(name: "state", value: s),
      ]
      NSWorkspace.shared.open(components.url!)
      status = "Complete sign-in in your browser"
    } catch { status = error.localizedDescription }
  }
  func callback(_ url: URL) async {
    do {
      guard url.scheme == "sudoai", url.host == "auth", url.path == "/callback",
        let c = URLComponents(url: url, resolvingAgainstBaseURL: false), let v = verifier,
        let expected = state, let flowServer, let base = URL(string: flowServer), let began,
        Date().timeIntervalSince(began) < 600
      else { throw AppError.message("Expired or unsolicited sign-in callback") }
      let items = c.queryItems ?? []
      guard items.filter({ $0.name == "state" }).count == 1,
        items.filter({ $0.name == "code" }).count == 1,
        items.first(where: { $0.name == "state" })?.value == expected,
        let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty
      else { throw AppError.message("Sign-in state mismatch") }
      verifier = nil
      state = nil
      var request = URLRequest(url: base.appendingPathComponent("api/v1/auth/exchange"))
      request.httpMethod = "POST"
      request.setValue("application/json", forHTTPHeaderField: "Content-Type")
      request.httpBody = try JSONSerialization.data(withJSONObject: ["code": code, "verifier": v])
      let (data, response) = try await HTTP.session.data(for: request)
      guard (response as? HTTPURLResponse)?.statusCode == 200,
        let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
        let token = value["access_token"] as? String
      else { throw AppError.message("Sign-in exchange failed") }
      var me = URLRequest(url: base.appendingPathComponent("api/v1/me"))
      me.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
      let (profile, r) = try await HTTP.session.data(for: me)
      guard (r as? HTTPURLResponse)?.statusCode == 200,
        let p = try JSONSerialization.jsonObject(with: profile) as? [String: Any],
        let id = p["id"] as? String
      else { throw AppError.message("Cannot verify account owner") }
      let cred = Credential(
        token: token, owner: id, name: p["name"] as? String ?? "Account", server: flowServer)
      try Vault.save(cred)
      credential = cred
      status = "Signed in as \(cred.name)"
    } catch { status = error.localizedDescription }
  }
  func signOut() async {
    guard let c = credential else { return }
    do {
      var r = URLRequest(url: URL(string: c.server + "/api/v1/auth/revoke")!)
      r.httpMethod = "POST"
      r.setValue("Bearer \(c.token)", forHTTPHeaderField: "Authorization")
      let (_, response) = try await HTTP.session.data(for: r)
      guard let http = response as? HTTPURLResponse,
        (200..<300).contains(http.statusCode) || http.statusCode == 401
      else { throw AppError.message("Token revocation failed; retry when connected") }
      Vault.clear()
      credential = nil
      status = "Signed out; pending captures retain their original owner"
    } catch { status = error.localizedDescription }
  }
}
actor Uploader {
  let store: CaptureStore
  let session: URLSession
  private var running = false
  init(store: CaptureStore, session: URLSession = HTTP.session) {
    self.store = store
    self.session = session
  }
  func run(_ credential: Credential) async -> String {
    guard !running else { return "Upload already running" }
    running = true
    defer { running = false }
    do {
      for session in store.history().reversed()
      where session.owner == credential.owner && session.server == credential.server
        && session.retired != true
      {
        for item in session.uploads where !item.acknowledged {
          guard let url = URL(string: credential.server + item.path) else {
            throw AppError.message("Invalid queued endpoint")
          }
          let body = try store.body(item)
          var request = URLRequest(url: url)
          request.httpMethod = item.method
          request.httpBody = body
          request.timeoutInterval = 45
          request.setValue("Bearer \(credential.token)", forHTTPHeaderField: "Authorization")
          request.setValue(item.mime, forHTTPHeaderField: "Content-Type")
          if item.mime == "audio/wav" {
            request.setValue(sha256(body), forHTTPHeaderField: "X-Content-SHA256")
          }
          let (_, response) = try await self.session.data(for: request)
          guard let http = response as? HTTPURLResponse else {
            throw AppError.message("No HTTP response")
          }
          if http.statusCode == 410 {
            try store.retire(session.id)
            break
          }
          if http.statusCode == 401 { return "Sign-in expired; local captures retained" }
          guard (200..<300).contains(http.statusCode) else {
            throw AppError.message("Upload HTTP \(http.statusCode); local bytes retained")
          }
          try store.acknowledge(session: session.id, upload: item.id)
        }
      }
      return "Uploads up to date"
    } catch { return error.localizedDescription }
  }
}
