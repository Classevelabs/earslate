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

struct BootstrapResponse: Decodable, Sendable {
    let provider: TranslationProvider
    let credential: String
    let wssURL: String
    let expiresAt: String?
    let model: String

    enum CodingKeys: String, CodingKey {
        case provider, credential, model
        case wssURL = "wss_url"
        case expiresAt = "expires_at"
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
