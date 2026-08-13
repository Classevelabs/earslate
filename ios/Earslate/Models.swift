import Foundation

enum TranslationProvider: String, Codable, CaseIterable, Identifiable, Sendable {
    case automatic = "auto"
    case gemini
    case openai

    var id: String { rawValue }
    var title: String {
        switch self {
        case .automatic: return "Automatic"
        case .gemini: return "Gemini"
        case .openai: return "OpenAI"
        }
    }
}

/// Everything needed to open one provider socket.
///
/// This was `BootstrapResponse`, a `Decodable` of the JSON the ClassEve session
/// broker returned. There is no broker and no JSON: the values are produced
/// on-device by `ProviderSessionMinter` from the user's own API key, so the
/// snake_case `CodingKeys` and the `Decodable` conformance went with it. A type
/// that still knew how to decode a server response would be the last thread
/// tying the app to an endpoint that returns 404.
struct SessionBootstrap: Sendable {
    let credential: String
    let provider: TranslationProvider
    let wssURL: String
    let model: String
    let expiresAt: String?

    init(
        credential: String,
        provider: TranslationProvider,
        wssURL: String,
        model: String,
        expiresAt: String? = nil
    ) {
        self.credential = credential
        self.provider = provider
        self.wssURL = wssURL
        self.model = model
        self.expiresAt = expiresAt
    }
}

enum LiveState: String {
    case idle = "Ready"
    case bootstrapping = "Bootstrapping"
    case connecting = "Connecting"
    case listening = "Listening"
    case playing = "Speaking"
    case reconnecting = "Reconnecting"
    case failed = "Needs attention"
}

struct HTTPError: LocalizedError, Sendable {
    let status: Int
    let code: String
    let message: String

    var errorDescription: String? { message }
}
