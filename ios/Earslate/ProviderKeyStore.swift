import Foundation
import Security

/// Which providers exist, and a deliberately shallow check on what the user
/// pasted.
///
/// This mirrors the Android `KeyProvider` enum, including its most important
/// property: **it does not validate key formats and must not start.** An
/// earlier Android release refused anything that did not begin with `AIza` for
/// Gemini or `sk-` for OpenAI. Google then changed what its keys look like and
/// the app rejected perfectly good keys with a confident, wrong error — users
/// could not get past setup at all. A provider can change its key format
/// whenever it likes, and a hardcoded allowlist turns that into an app that is
/// broken until it ships an update.
///
/// Only mistakes that are *definitely* mistakes regardless of format are
/// rejected. Everything else goes to the provider, because the provider is the
/// only thing that actually knows.
enum KeyProvider: String, CaseIterable, Identifiable, Sendable {
    case gemini
    case openai

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .gemini: return "Google Gemini"
        case .openai: return "OpenAI"
        }
    }

    /// Historical prefix. A display and detection hint only — never a gate.
    var prefixHint: String {
        switch self {
        case .gemini: return "AIza"
        case .openai: return "sk-"
        }
    }

    var consoleName: String {
        switch self {
        case .gemini: return "Google AI Studio"
        case .openai: return "the OpenAI dashboard"
        }
    }

    var consoleURL: URL {
        switch self {
        case .gemini: return URL(string: "https://aistudio.google.com/apikey")!
        case .openai: return URL(string: "https://platform.openai.com/api-keys")!
        }
    }

    var translationProvider: TranslationProvider {
        switch self {
        case .gemini: return .gemini
        case .openai: return .openai
        }
    }

    static func forProvider(_ provider: TranslationProvider) -> KeyProvider? {
        switch provider {
        case .gemini: return .gemini
        case .openai: return .openai
        case .automatic: return nil
        }
    }

    /// Best guess at which provider a pasted key belongs to. A hint, never a gate.
    static func detect(_ candidate: String) -> KeyProvider? {
        let key = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        return allCases.first { key.hasPrefix($0.prefixHint) }
    }

    /// Returns nil when `candidate` is worth sending to the provider, or a
    /// sentence naming a definite mistake when it is not.
    func rejectionReason(_ candidate: String) -> String? {
        let key = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        if key.isEmpty {
            return "Paste your \(displayName) key first — it's the value \(consoleName) showed you."
        }
        let lowered = key.lowercased()
        if lowered.hasPrefix("http://") || lowered.hasPrefix("https://") {
            return "That's a web address, not a key. Open it, then copy the key itself."
        }
        if lowered.hasPrefix("bearer ") {
            return "Remove the word “Bearer ” from the front — paste only the key."
        }
        if key.rangeOfCharacter(from: .whitespacesAndNewlines) != nil {
            return "That key has a space or line break in it. Copy it again without the surrounding text."
        }
        if key.count < 8 {
            return "That looks too short to be a key. Copy the whole value from \(consoleName)."
        }
        return nil
    }
}

/// The user's provider API keys, held in the iOS Keychain.
///
/// The Android counterpart seals keys with an AES-256-GCM key that lives in the
/// Android keystore and never leaves it. The Keychain is the platform-equivalent
/// guarantee, with two attributes that matter and are easy to get wrong:
///
///  - `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. `ThisDeviceOnly` is
///    what keeps the key out of iCloud Keychain and out of encrypted backups,
///    matching Android's `allowBackup=false` and its data-extraction excludes.
///    `AfterFirstUnlock` rather than `WhenUnlocked` because a session may be
///    running while the screen is locked; `WhenPasscodeSet...` is not used
///    because it makes the key unreadable on a device with no passcode, which
///    is a silent failure rather than a security win here.
///  - No access group and no synchronizable flag, so nothing shares it.
///
/// Reads and writes are cheap enough to go straight to the Keychain each time
/// rather than caching plaintext in memory for the process lifetime.
struct ProviderKeyStore: Sendable {

    private let service: String

    init(service: String = "com.classeve.earslate.providerkeys") {
        self.service = service
    }

    func key(for provider: KeyProvider) -> String? {
        var query = baseQuery(for: provider)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8),
              !value.isEmpty
        else { return nil }
        return value
    }

    func has(_ provider: KeyProvider) -> Bool { key(for: provider) != nil }

    var hasAnyKey: Bool { KeyProvider.allCases.contains(where: has) }

    func configured() -> [KeyProvider] { KeyProvider.allCases.filter(has) }

    @discardableResult
    func save(_ rawKey: String, for provider: KeyProvider) -> Bool {
        let trimmed = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else {
            forget(provider)
            return true
        }
        // Delete-then-add rather than SecItemUpdate: an update against a
        // partially-written or attribute-mismatched item fails with
        // errSecItemNotFound and would leave the old key in place while
        // reporting success.
        forget(provider)
        var attributes = baseQuery(for: provider)
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    func forget(_ provider: KeyProvider) {
        SecItemDelete(baseQuery(for: provider) as CFDictionary)
    }

    /// The provider a session should actually use, given the user's preference
    /// and which keys exist. Returns nil when nothing is usable.
    ///
    /// "Automatic" prefers Gemini because it is the only provider that can run
    /// both translation directions; OpenAI's translation endpoint has a single
    /// output language and no echo suppression, so it is one-directional by
    /// design.
    func resolve(_ preference: TranslationProvider) -> KeyProvider? {
        if let explicit = KeyProvider.forProvider(preference) {
            return has(explicit) ? explicit : nil
        }
        return KeyProvider.allCases.first(where: has)
    }

    /// Masked form for display. A whole key is never rendered back to the screen.
    func masked(_ provider: KeyProvider) -> String? {
        guard let key = key(for: provider) else { return nil }
        guard key.count > 10 else { return provider.prefixHint + "…" }
        return key.prefix(6) + "…" + key.suffix(4)
    }

    private func baseQuery(for provider: KeyProvider) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: provider.rawValue,
        ]
    }
}

/// Per-installation identifier. Random, generated locally, never sent anywhere
/// except as a SHA-256 hash in OpenAI's safety-identifier header. It is not an
/// account, carries no entitlement, and identifies a device rather than a person.
enum InstallationID {
    private static let key = "earslate.anonymous-install-id"

    static let value: String = {
        if let saved = UserDefaults.standard.string(forKey: key), UUID(uuidString: saved) != nil {
            return saved
        }
        let generated = UUID().uuidString.lowercased()
        UserDefaults.standard.set(generated, forKey: key)
        return generated
    }()
}
