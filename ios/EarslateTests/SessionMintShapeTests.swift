import XCTest
@testable import Earslate

/// The on-device credential mint, pinned.
///
/// iOS used to get its credential from a ClassEve broker, so none of this
/// existed here — and when the broker was deleted the app could not start a
/// session at all while its CI stayed green. These assertions are the reason
/// that cannot recur silently: they cover the request we send to the provider,
/// which is the part with no server of ours left to catch a mistake.
///
/// Every shape here mirrors an Android test (`GeminiAuthTokenShapeTest`,
/// `LanguageCodesTest`, `KeyProviderTest`). The two clients must agree, because
/// the last time they did not, iOS shipped a setup frame Android had already
/// fixed and nobody found out for a month.
final class SessionMintShapeTests: XCTestCase {

    // MARK: - Request interception

    /// Captures the outbound request instead of hitting the network.
    ///
    /// `URLProtocol` sees `httpBodyStream` rather than `httpBody` once
    /// URLSession has taken the request, so the body is read off the stream —
    /// reading `httpBody` here returns nil and would silently assert nothing.
    final class CapturingProtocol: URLProtocol {
        nonisolated(unsafe) static var lastRequest: URLRequest?
        nonisolated(unsafe) static var lastBody: [String: Any]?
        nonisolated(unsafe) static var responseJSON: [String: Any] = [:]
        nonisolated(unsafe) static var statusCode = 200

        static func reset() {
            lastRequest = nil
            lastBody = nil
            responseJSON = [:]
            statusCode = 200
        }

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            Self.lastRequest = request
            if let stream = request.httpBodyStream {
                stream.open()
                var data = Data()
                let size = 4096
                let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: size)
                defer { buffer.deallocate(); stream.close() }
                while stream.hasBytesAvailable {
                    let read = stream.read(buffer, maxLength: size)
                    if read <= 0 { break }
                    data.append(buffer, count: read)
                }
                Self.lastBody = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            }
            let response = HTTPURLResponse(
                url: request.url!, statusCode: Self.statusCode,
                httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"]
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            let body = (try? JSONSerialization.data(withJSONObject: Self.responseJSON)) ?? Data()
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        }

        override func stopLoading() {}
    }

    private func makeMinter() -> ProviderSessionMinter {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CapturingProtocol.self]
        return ProviderSessionMinter(
            session: URLSession(configuration: configuration),
            installID: "00000000-0000-4000-8000-000000000000"
        )
    }

    override func setUp() {
        super.setUp()
        CapturingProtocol.reset()
    }

    // MARK: - Gemini

    func testGeminiMintBodyHasNoAuthTokenWrapperAndCorrectFieldPlacement() async throws {
        CapturingProtocol.responseJSON = ["name": "authTokens/abc123"]
        let bootstrap = try await makeMinter()
            .mint(provider: .gemini, apiKey: "AIzaTESTKEY", targetLanguage: "es")

        let body = try XCTUnwrap(CapturingProtocol.lastBody)

        // 1. No wrapper. Sending one returns
        //    'Unknown name "authToken" at auth_token: Cannot find field'.
        XCTAssertNil(body["authToken"], "AuthToken fields sit at the top level, not in a wrapper")
        XCTAssertEqual(body["uses"] as? Int, 1)
        XCTAssertNotNil(body["expireTime"])
        XCTAssertNotNil(body["newSessionExpireTime"])

        let setup = try XCTUnwrap(body["bidiGenerateContentSetup"] as? [String: Any])
        XCTAssertEqual(setup["model"] as? String, "models/\(ProviderSessionMinter.geminiModel)")

        // 2. Placement is asymmetric and load-bearing. Transcription belongs on
        //    the setup; translation belongs inside generationConfig. Swapping
        //    them closes the socket with 1007.
        XCTAssertNotNil(setup["inputAudioTranscription"], "transcription belongs on setup")
        XCTAssertNotNil(setup["outputAudioTranscription"], "transcription belongs on setup")

        let generationConfig = try XCTUnwrap(setup["generationConfig"] as? [String: Any])
        XCTAssertNil(generationConfig["inputAudioTranscription"], "must NOT be nested here — 1007")
        XCTAssertNil(generationConfig["outputAudioTranscription"], "must NOT be nested here — 1007")
        XCTAssertEqual(generationConfig["responseModalities"] as? [String], ["AUDIO"])

        let translation = try XCTUnwrap(generationConfig["translationConfig"] as? [String: Any])
        XCTAssertEqual(translation["targetLanguageCode"] as? String, "es")
        XCTAssertEqual(translation["echoTargetLanguage"] as? Bool, false)

        XCTAssertEqual(bootstrap.credential, "authTokens/abc123")
        XCTAssertEqual(bootstrap.provider, .gemini)
        XCTAssertTrue(bootstrap.wssURL.hasPrefix("wss://"))
    }

    func testGeminiKeyTravelsInTheQueryStringAndNotAHeader() async throws {
        CapturingProtocol.responseJSON = ["name": "authTokens/abc123"]
        _ = try await makeMinter().mint(provider: .gemini, apiKey: "AIzaSECRET", targetLanguage: "en")

        let request = try XCTUnwrap(CapturingProtocol.lastRequest)
        let query = try XCTUnwrap(request.url?.query)
        XCTAssertTrue(query.contains("key=AIzaSECRET"), "the token endpoint accepts the key only as a query item")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
    }

    func testGeminiMintFailsLoudlyWhenNoCredentialComesBack() async {
        CapturingProtocol.responseJSON = ["somethingElse": true]
        do {
            _ = try await makeMinter().mint(provider: .gemini, apiKey: "AIzaX", targetLanguage: "en")
            XCTFail("a response with no credential must not produce a bootstrap")
        } catch let error as HTTPError {
            XCTAssertEqual(error.code, "no_credential")
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    // MARK: - OpenAI

    func testOpenAIMintSendsBearerSafetyIdentifierAndOutputLanguage() async throws {
        CapturingProtocol.responseJSON = ["value": "ek_live_abc", "expires_at": 1_800_000_000]
        let bootstrap = try await makeMinter()
            .mint(provider: .openai, apiKey: "sk-TESTKEY", targetLanguage: "pt-BR")

        let request = try XCTUnwrap(CapturingProtocol.lastRequest)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer sk-TESTKEY")

        // Hashed, never raw: it attributes abuse signals to a device rather
        // than to the user's whole OpenAI account, and it must not be a value
        // that can be correlated back to the install.
        let safety = try XCTUnwrap(request.value(forHTTPHeaderField: "OpenAI-Safety-Identifier"))
        XCTAssertTrue(safety.hasPrefix("earslate_"))
        XCTAssertEqual(safety.count, "earslate_".count + 64, "SHA-256 hex")
        XCTAssertFalse(safety.contains("0000-4000"), "the raw install id must never be sent")

        let body = try XCTUnwrap(CapturingProtocol.lastBody)
        let session = try XCTUnwrap(body["session"] as? [String: Any])
        XCTAssertEqual(session["model"] as? String, ProviderSessionMinter.openAIModel)
        let output = try XCTUnwrap((session["audio"] as? [String: Any])?["output"] as? [String: Any])
        XCTAssertEqual(output["language"] as? String, "pt-BR")

        XCTAssertEqual(bootstrap.credential, "ek_live_abc")
        XCTAssertTrue(bootstrap.wssURL.contains("model="))
    }

    func testProviderErrorsBecomeSentencesAUserCanActOn() async {
        CapturingProtocol.statusCode = 402
        do {
            _ = try await makeMinter().mint(provider: .openai, apiKey: "sk-X", targetLanguage: "en")
            XCTFail("402 must not succeed")
        } catch let error as HTTPError {
            XCTAssertTrue(error.message.lowercased().contains("billing"))
            // The provider's own wording is written for API developers and can
            // echo the key back; it must never reach the user.
            XCTAssertFalse(error.message.contains("sk-X"))
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    // MARK: - Language codes (must match Android exactly)

    func testLanguageNormalisationMatchesAndroid() {
        XCTAssertEqual(LanguageCodes.normalize("en-US"), "en")
        XCTAssertEqual(LanguageCodes.normalize("ES"), "es")
        XCTAssertEqual(LanguageCodes.normalize("zh-CN"), "zh-Hans")
        XCTAssertEqual(LanguageCodes.normalize("zh-TW"), "zh-Hant")
        XCTAssertEqual(LanguageCodes.normalize("zh-Hant"), "zh-Hant")
        XCTAssertEqual(LanguageCodes.normalize("pt-PT"), "pt-PT")
        XCTAssertEqual(LanguageCodes.normalize("pt-BR"), "pt-BR")
        XCTAssertEqual(LanguageCodes.normalize("hi-IN"), "hi")

        // A blank or malformed tag must be refused rather than sent as an empty
        // targetLanguageCode, which the model rejects.
        XCTAssertNil(LanguageCodes.normalize(""))
        XCTAssertNil(LanguageCodes.normalize("   "))
        XCTAssertNil(LanguageCodes.normalize(nil))
        XCTAssertNil(LanguageCodes.normalize("english"))
        XCTAssertNil(LanguageCodes.normalize("e"))
        XCTAssertNil(LanguageCodes.normalize("en-US-extra"))
        XCTAssertNil(LanguageCodes.normalize("e1"))
    }

    // MARK: - Key checks are shallow on purpose

    func testOnlyUnambiguousMistakesAreRejected() {
        let gemini = KeyProvider.gemini
        XCTAssertNotNil(gemini.rejectionReason(""))
        XCTAssertNotNil(gemini.rejectionReason("https://aistudio.google.com/apikey"))
        XCTAssertNotNil(gemini.rejectionReason("Bearer AIzaSomething"))
        XCTAssertNotNil(gemini.rejectionReason("AIza has a space"))
        XCTAssertNotNil(gemini.rejectionReason("short"))

        // The whole point: an unfamiliar shape goes to the provider, because
        // the provider is the only thing that actually knows. Hardcoding the
        // AIza prefix once locked users out of their own valid keys.
        XCTAssertNil(gemini.rejectionReason("totally-new-google-key-format-2027"))
        XCTAssertNil(gemini.rejectionReason("sk-an-openai-key-pasted-under-gemini"))
        XCTAssertNil(KeyProvider.openai.rejectionReason("AIzaLooksLikeGemini"))
    }

    func testProviderDetectionIsAHintNotAGate() {
        XCTAssertEqual(KeyProvider.detect("AIzaXXXXXXXX"), .gemini)
        XCTAssertEqual(KeyProvider.detect("sk-XXXXXXXX"), .openai)
        XCTAssertNil(KeyProvider.detect("something-else-entirely"))
    }

    func testAutomaticNeverResolvesToAKeyProviderDirectly() {
        // AUTOMATIC is a preference, not a provider; it must be resolved
        // against which keys exist before any socket opens.
        XCTAssertNil(KeyProvider.forProvider(.automatic))
        XCTAssertEqual(KeyProvider.forProvider(.gemini), .gemini)
        XCTAssertEqual(KeyProvider.forProvider(.openai), .openai)
    }
}
