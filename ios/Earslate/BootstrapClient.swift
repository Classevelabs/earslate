import CryptoKit
import Foundation

/// Exchanges the user's own long-lived API key for a short-lived, single-use
/// session credential, then hands back everything needed to open the provider
/// socket.
///
/// **This replaces a client that POSTed to `https://api.classeve.com/v1/earslate/session`.**
/// That broker was removed when Android moved to bring-your-own-key in 0.4.0 —
/// the Worker has no earslate handler and migration `00052_remove_earslate.sql`
/// dropped the product — so the endpoint answered 404 and this app could not
/// start a single session. It compiled, its tests passed and its CI was green
/// the entire time, because the tests asserted the *shape* of the setup frame
/// and had no opinion about whether the credential source existed.
///
/// The exchange itself matters, and is worth keeping rather than putting the
/// raw key on the socket: the long-lived key is used once, over HTTPS, to mint
/// a credential that expires in minutes and is scoped to one session. The
/// socket never carries the real key, so no log, crash report or proxy that
/// sees the connection ever holds it.
///
/// Everything runs on the device. Requests go from the phone directly to Google
/// or OpenAI, authenticated with the user's own key, billed to the user's own
/// account. There is no ClassEve server in this path, or in any other.
///
/// Kept deliberately in lockstep with Android's `ProviderSessionMinter`. When
/// one changes, the other is wrong until it does too — that divergence is
/// exactly what produced the 1007 setup-frame bug that only Android ever fixed.
struct ProviderSessionMinter: Sendable {

    // Pinned here rather than fetched, so the app has no configuration server
    // of any kind. Bump with a release when a provider moves.
    static let geminiModel = "gemini-3.5-live-translate-preview"
    static let openAIModel = "gpt-realtime-translate"

    static let geminiTokenURL = URL(string: "https://generativelanguage.googleapis.com/v1alpha/auth_tokens")!
    static let geminiWSS = URL(string:
        "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"
    )!
    static let openAISecretURL = URL(string: "https://api.openai.com/v1/realtime/translations/client_secrets")!
    static let openAIWSS = "wss://api.openai.com/v1/realtime/translations"

    private let session: URLSession
    private let installID: String

    init(session: URLSession? = nil, installID: String = InstallationID.value) {
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 20
            configuration.timeoutIntervalForResource = 25
            self.session = URLSession(configuration: configuration)
        }
        self.installID = installID
    }

    func mint(
        provider: KeyProvider,
        apiKey: String,
        targetLanguage: String
    ) async throws -> SessionBootstrap {
        guard let language = LanguageCodes.normalize(targetLanguage) else {
            throw HTTPError(status: 0, code: "bad_language", message: "Choose a supported target language.")
        }
        switch provider {
        case .gemini: return try await mintGemini(apiKey: apiKey, language: language)
        case .openai: return try await mintOpenAI(apiKey: apiKey, language: language)
        }
    }

    // MARK: - Gemini

    private func mintGemini(apiKey: String, language: String) async throws -> SessionBootstrap {
        let now = Date()
        let expiresAt = Self.iso8601(now.addingTimeInterval(30 * 60))
        let newSessionExpiresAt = Self.iso8601(now.addingTimeInterval(60))

        // Two things here are easy to get wrong and both produce a flat 400
        // that reads like a bad key rather than a bad request:
        //
        //  1. There is NO "authToken" wrapper. The AuthToken fields sit at the
        //     top level of the body. Sending the wrapper returns
        //     'Unknown name "authToken" at auth_token: Cannot find field'.
        //  2. inputAudioTranscription / outputAudioTranscription belong to
        //     bidiGenerateContentSetup, NOT to generationConfig.
        //     translationConfig is the opposite — it lives inside
        //     generationConfig. Getting this backwards is the 1007 close.
        let setup: [String: Any] = [
            "model": "models/\(Self.geminiModel)",
            "generationConfig": [
                "responseModalities": ["AUDIO"],
                "translationConfig": [
                    "targetLanguageCode": language,
                    // Silence this leg when the speaker is already speaking the
                    // target language.
                    "echoTargetLanguage": false,
                ],
            ],
            "inputAudioTranscription": [:],
            "outputAudioTranscription": [:],
        ]
        let body: [String: Any] = [
            "uses": 1,
            "expireTime": expiresAt,
            "newSessionExpireTime": newSessionExpiresAt,
            "bidiGenerateContentSetup": setup,
        ]

        // The key goes in the query string because that is the only form this
        // endpoint accepts. It is one HTTPS request; the socket that follows
        // carries the minted token instead.
        var components = URLComponents(url: Self.geminiTokenURL, resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "key", value: apiKey)]
        guard let url = components.url else {
            throw HTTPError(status: 0, code: "bad_request", message: "Could not build the Gemini request.")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let json = try await execute(request, provider: .gemini)
        guard let name = json["name"] as? String, !name.isEmpty else {
            throw HTTPError(
                status: 0, code: "no_credential",
                message: "Gemini returned a session without a credential. Try again."
            )
        }
        return SessionBootstrap(
            credential: name,
            provider: .gemini,
            wssURL: Self.geminiWSS.absoluteString,
            model: Self.geminiModel,
            expiresAt: (json["expireTime"] as? String) ?? expiresAt
        )
    }

    // MARK: - OpenAI

    private func mintOpenAI(apiKey: String, language: String) async throws -> SessionBootstrap {
        let body: [String: Any] = [
            "session": [
                "model": Self.openAIModel,
                "audio": ["output": ["language": language]],
            ],
        ]
        var request = URLRequest(url: Self.openAISecretURL)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(safetyIdentifier(), forHTTPHeaderField: "OpenAI-Safety-Identifier")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let json = try await execute(request, provider: .openai)
        guard let value = json["value"] as? String, !value.isEmpty else {
            throw HTTPError(
                status: 0, code: "no_credential",
                message: "OpenAI returned a session without a credential. Try again."
            )
        }
        let encodedModel = Self.openAIModel
            .addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? Self.openAIModel
        var expiresAt: String?
        if let epoch = json["expires_at"] as? Double, epoch > 0 {
            expiresAt = Self.iso8601(Date(timeIntervalSince1970: epoch))
        }
        return SessionBootstrap(
            credential: value,
            provider: .openai,
            wssURL: "\(Self.openAIWSS)?model=\(encodedModel)",
            model: Self.openAIModel,
            expiresAt: expiresAt
        )
    }

    // MARK: - Transport

    /// Runs the request and turns provider failures into sentences a user can
    /// act on. The provider's own error text is deliberately not surfaced: it
    /// is written for API developers, often mentions parameters the user has
    /// never heard of, and occasionally echoes the key back.
    private func execute(_ request: URLRequest, provider: KeyProvider) async throws -> [String: Any] {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw HTTPError(
                status: 0, code: "network",
                message: "Couldn't reach \(provider.displayName). Check your connection and try again."
            )
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw Self.explain(status: status, provider: provider)
        }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw HTTPError(
                status: status, code: "bad_response",
                message: "\(provider.displayName) sent a reply we couldn't read. Try again."
            )
        }
        return json
    }

    static func explain(status: Int, provider: KeyProvider) -> HTTPError {
        let name = provider.displayName
        let message: String
        switch status {
        case 400:
            message = "\(name) rejected the session request. Your key may not have access to the " +
                "live translation model yet."
        case 401, 403:
            message = "\(name) did not accept that key. Check it was copied in full from " +
                "\(provider.consoleName), and that it hasn't been revoked."
        case 402:
            message = "Your \(name) account needs billing set up before it can run live translation."
        case 404:
            message = "\(name) doesn't offer the live translation model on this key. It may not be " +
                "available in your account or region yet."
        case 429:
            message = "Your \(name) key is out of quota, or is being rate limited. Wait a moment, or " +
                "check your usage limits."
        case 500...599:
            message = "\(name) is having trouble right now. Try again in a moment."
        default:
            message = "\(name) couldn't start a translation session (error \(status))."
        }
        return HTTPError(status: status, code: "provider_\(status)", message: message)
    }

    private func safetyIdentifier() -> String {
        let digest = SHA256.hash(data: Data(installID.utf8))
        return "earslate_" + digest.map { String(format: "%02x", $0) }.joined()
    }

    static func iso8601(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        return formatter.string(from: date)
    }
}

/// Starts sessions from the key the user supplied, on the device.
///
/// Keeps the same shape the rest of the app already consumed from the deleted
/// broker client, so the live client, the audio path and reconnect did not have
/// to change: only where a credential comes from did.
struct BootstrapClient: Sendable {

    private let keys: ProviderKeyStore
    private let minter: ProviderSessionMinter

    init(keys: ProviderKeyStore = ProviderKeyStore(), minter: ProviderSessionMinter = ProviderSessionMinter()) {
        self.keys = keys
        self.minter = minter
    }

    func bootstrap(provider: TranslationProvider, targetLanguage: String) async throws -> SessionBootstrap {
        guard let chosen = keys.resolve(provider), let apiKey = keys.key(for: chosen) else {
            throw Self.missingKey(provider)
        }
        do {
            return try await minter.mint(provider: chosen, apiKey: apiKey, targetLanguage: targetLanguage)
        } catch let primaryFailure {
            // "Automatic" is a reliability promise, not a label. If the user has
            // a second key and the first provider is refusing sessions, use it
            // rather than failing the session in the user's face.
            guard provider == .automatic,
                  let fallback = KeyProvider.allCases.first(where: { $0 != chosen && keys.has($0) }),
                  let fallbackKey = keys.key(for: fallback)
            else { throw primaryFailure }
            do {
                return try await minter.mint(
                    provider: fallback, apiKey: fallbackKey, targetLanguage: targetLanguage
                )
            } catch {
                // Report the provider the user would have expected to be used.
                throw primaryFailure
            }
        }
    }

    static func missingKey(_ requested: TranslationProvider) -> HTTPError {
        if let named = KeyProvider.forProvider(requested) {
            return HTTPError(
                status: 0, code: "no_key",
                message: "No \(named.displayName) key is set up. Add one in Settings, or switch provider."
            )
        }
        return HTTPError(
            status: 0, code: "no_key",
            message: "No API key is set up yet. Add one to start translating."
        )
    }
}

/// Proves a key works before it is saved, by minting a real session with it and
/// throwing the session away.
///
/// A format check can only say a string is shaped like a key. This says the key
/// is accepted, the account is in good standing, and the live translation model
/// is actually reachable on it — the three things that otherwise fail later, in
/// the middle of a conversation, when the user is least able to do anything
/// about it.
struct ProviderKeyVerifier: Sendable {
    enum Result: Sendable {
        case valid
        case rejected(String)
    }

    private let minter: ProviderSessionMinter

    init(minter: ProviderSessionMinter = ProviderSessionMinter()) {
        self.minter = minter
    }

    func verify(provider: KeyProvider, apiKey: String, targetLanguage: String) async -> Result {
        do {
            _ = try await minter.mint(provider: provider, apiKey: apiKey, targetLanguage: targetLanguage)
            return .valid
        } catch let error as HTTPError {
            return .rejected(error.message)
        } catch {
            return .rejected("That key could not be verified.")
        }
    }
}

/// Language-code normalisation, matching what the provider APIs accept.
///
/// Chinese and Portuguese are the two cases where the region genuinely changes
/// the output rather than just the accent, so they keep a script/region suffix;
/// everything else reduces to the base language. Pure and side-effect free, and
/// identical in behaviour to Android's `LanguageCodes` — pinned by tests on
/// both sides.
enum LanguageCodes {
    static func normalize(_ raw: String?) -> String? {
        let value = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }

        let parts = value.split(separator: "-", omittingEmptySubsequences: false).map(String.init)
        guard (1...2).contains(parts.count) else { return nil }
        let languagePart = parts[0]
        guard (2...3).contains(languagePart.count),
              languagePart.allSatisfy({ $0.isASCII && $0.isLetter })
        else { return nil }
        if parts.count == 2 {
            let regionPart = parts[1]
            guard (2...4).contains(regionPart.count),
                  regionPart.allSatisfy({ $0.isASCII && $0.isLetter })
            else { return nil }
        }

        let language = languagePart.lowercased()
        let region = parts.count == 2 ? parts[1].lowercased() : nil

        if language == "zh", let region {
            return (region == "tw" || region == "hant") ? "zh-Hant" : "zh-Hans"
        }
        if language == "pt", let region {
            return region == "pt" ? "pt-PT" : "pt-BR"
        }
        return language
    }
}
