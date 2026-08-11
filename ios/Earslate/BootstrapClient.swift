import Foundation

struct BootstrapClient: Sendable {
    private let workerURL: URL
    private let session: URLSession

    init(workerURL: URL = AppConstants.workerURL, session: URLSession = .shared) {
        self.workerURL = workerURL
        self.session = session
    }

    func bootstrap(provider: TranslationProvider, targetLanguage: String) async throws -> BootstrapResponse {
        let url = AppConstants.workerEndpoint("v1/earslate/session", base: workerURL)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 25
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(InstallationID.value, forHTTPHeaderField: "X-Earslate-Install-Id")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "provider": provider.rawValue,
            "target_language": targetLanguage,
        ])

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let code = json?["code"] as? String ?? "HTTP_\(status)"
            let message = json?["message"] as? String ?? "Translation service is unavailable."
            throw HTTPError(status: status, code: code, message: message)
        }
        return try JSONDecoder().decode(BootstrapResponse.self, from: data)
    }
}

private enum InstallationID {
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
