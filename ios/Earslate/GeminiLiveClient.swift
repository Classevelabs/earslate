import Foundation

actor LiveTranslationClient {
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var provider: TranslationProvider?
    private var ready = false
    private let session = URLSession(configuration: .default)

    func connect(
        bootstrap: SessionBootstrap,
        targetLanguage: String,
        onCaption: @escaping @Sendable (String) -> Void,
        onAudio: @escaping @Sendable (Data) -> Void,
        onDisconnect: @escaping @Sendable (String) -> Void
    ) async throws {
        await close()
        guard let url = URL(string: bootstrap.wssURL), url.scheme == "wss" else {
            throw HTTPError(status: 0, code: "bad_wss_url", message: "Live translation URL is invalid.")
        }
        var request = URLRequest(url: url)
        switch bootstrap.provider {
        case .gemini:
            request.setValue("Token \(bootstrap.credential)", forHTTPHeaderField: "Authorization")
        case .openai:
            request.setValue("Bearer \(bootstrap.credential)", forHTTPHeaderField: "Authorization")
        case .automatic:
            throw HTTPError(status: 0, code: "bad_provider", message: "The provider was not resolved.")
        }

        provider = bootstrap.provider
        ready = false
        let task = session.webSocketTask(with: request)
        socket = task
        task.resume()
        receiveTask = Task {
            await self.receiveLoop(
                onCaption: onCaption,
                onAudio: onAudio,
                onDisconnect: onDisconnect
            )
        }
        try await send(text: Self.setupFrame(
            provider: bootstrap.provider,
            model: bootstrap.model,
            targetLanguage: targetLanguage
        ))
        try await waitUntilReady()
    }

    func sendAudio(_ data: Data, sampleRate: Int) async {
        guard !data.isEmpty, ready, let provider else { return }
        let frame: [String: Any]
        switch provider {
        case .gemini:
            frame = [
                "realtimeInput": [
                    "audio": [
                        "data": data.base64EncodedString(),
                        "mimeType": "audio/pcm;rate=\(sampleRate)",
                    ],
                ],
            ]
        case .openai:
            let pcm24k = Self.resample16kTo24k(data)
            frame = [
                "type": "session.input_audio_buffer.append",
                "audio": pcm24k.base64EncodedString(),
            ]
        case .automatic:
            return
        }
        guard let jsonData = try? JSONSerialization.data(withJSONObject: frame),
              let json = String(data: jsonData, encoding: .utf8) else { return }
        try? await send(text: json)
    }

    func close() async {
        ready = false
        receiveTask?.cancel()
        receiveTask = nil
        if provider == .openai {
            try? await send(text: #"{"type":"session.close"}"#)
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        provider = nil
    }

    private func waitUntilReady() async throws {
        try await withThrowingTaskGroup(of: Bool.self) { group in
            group.addTask {
                while !Task.isCancelled {
                    if await self.isReady() { return true }
                    try await Task.sleep(nanoseconds: 40_000_000)
                }
                return false
            }
            group.addTask {
                try await Task.sleep(nanoseconds: 8_000_000_000)
                return false
            }
            let becameReady = try await group.next() ?? false
            group.cancelAll()
            if !becameReady {
                throw HTTPError(status: 0, code: "setup_timeout", message: "The translation provider did not become ready.")
            }
        }
    }

    private func isReady() -> Bool { ready }

    private func send(text: String) async throws {
        guard let socket else {
            throw HTTPError(status: 0, code: "socket_closed", message: "The live connection is closed.")
        }
        try await socket.send(.string(text))
    }

    private func receiveLoop(
        onCaption: @escaping @Sendable (String) -> Void,
        onAudio: @escaping @Sendable (Data) -> Void,
        onDisconnect: @escaping @Sendable (String) -> Void
    ) async {
        while !Task.isCancelled, let socket {
            do {
                let message = try await socket.receive()
                let text: String?
                switch message {
                case .string(let value): text = value
                case .data(let data): text = String(data: data, encoding: .utf8)
                @unknown default: text = nil
                }
                if let text {
                    parse(text: text, onCaption: onCaption, onAudio: onAudio, onDisconnect: onDisconnect)
                }
            } catch {
                if !Task.isCancelled { onDisconnect("The live translation connection was interrupted.") }
                return
            }
        }
    }

    /// `onDisconnect` is threaded in because the provider can report a fatal
    /// error inside an otherwise ordinary message — an OpenAI `error` frame
    /// ends the session, and the caller has to be told. It was referenced here
    /// without being a parameter, so this file never compiled.
    private func parse(
        text: String,
        onCaption: @escaping @Sendable (String) -> Void,
        onAudio: @escaping @Sendable (Data) -> Void,
        onDisconnect: @escaping @Sendable (String) -> Void
    ) {
        guard let data = text.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        if provider == .openai {
            switch root["type"] as? String {
            case "session.updated": ready = true
            case "session.output_transcript.delta":
                if let delta = root["delta"] as? String, !delta.isEmpty { onCaption(delta) }
            case "session.output_audio.delta":
                if let value = root["delta"] as? String, let audio = Data(base64Encoded: value) { onAudio(audio) }
            case "error":
                let error = root["error"] as? [String: Any]
                let message = error?["message"] as? String ?? "OpenAI translation failed."
                onDisconnect(message)
            default: break
            }
            return
        }

        if root["setupComplete"] != nil { ready = true }
        guard let content = root["serverContent"] as? [String: Any] else { return }
        if let transcription = content["outputTranscription"] as? [String: Any],
           let caption = transcription["text"] as? String, !caption.isEmpty { onCaption(caption) }
        if let modelTurn = content["modelTurn"] as? [String: Any],
           let parts = modelTurn["parts"] as? [[String: Any]] {
            for part in parts {
                guard let inline = part["inlineData"] as? [String: Any],
                      let payload = inline["data"] as? String,
                      let audio = Data(base64Encoded: payload) else { continue }
                onAudio(audio)
            }
        }
    }

    /// Internal rather than private so the wire format can be pinned by a
    /// test. The field placement inside this frame is the difference between
    /// a working session and one the server closes with 1007, and it is not
    /// something to discover from a user report.
    static func setupFrame(provider: TranslationProvider, model: String, targetLanguage: String) -> String {
        let frame: [String: Any]
        switch provider {
        case .gemini:
            let normalized = model.hasPrefix("models/") ? model : "models/\(model)"
            // Field placement is load-bearing and asymmetric: transcription
            // sits on `setup`, translation sits inside `generationConfig`.
            //
            // These were both inside generationConfig, which is the shape the
            // Android client shipped and had to fix. The server does not
            // ignore the mistake, it closes the socket:
            //
            //   1007 Invalid JSON payload received. Unknown name
            //   "outputAudioTranscription" at 'setup.generation_config':
            //   Cannot find field.
            //
            // Measured on Android on-device 2026-07-26. Every session with
            // captions on dies immediately, so on iOS this meant Gemini
            // translation never worked at all. Do not "tidy" these fields
            // back down into generationConfig — the symmetry is wrong and the
            // server is the authority. Pinned by GeminiSetupFrameTests.
            frame = [
                "setup": [
                    "model": normalized,
                    "inputAudioTranscription": [:],
                    "outputAudioTranscription": [:],
                    "generationConfig": [
                        "responseModalities": ["AUDIO"],
                        "translationConfig": [
                            "targetLanguageCode": targetLanguage,
                            "echoTargetLanguage": false,
                        ],
                    ],
                ],
            ]
        case .openai:
            frame = [
                "type": "session.update",
                "session": ["audio": ["output": ["language": targetLanguage]]],
            ]
        case .automatic:
            frame = [:]
        }
        let data = try? JSONSerialization.data(withJSONObject: frame)
        return String(data: data ?? Data(), encoding: .utf8) ?? "{}"
    }

    private static func resample16kTo24k(_ input: Data) -> Data {
        let bytes = [UInt8](input)
        let inputCount = bytes.count / 2
        guard inputCount > 0 else { return Data() }
        let outputCount = inputCount * 3 / 2
        var output = Data(capacity: outputCount * 2)
        func sample(_ index: Int) -> Int {
            let offset = index * 2
            return Int(Int16(bitPattern: UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)))
        }
        for index in 0..<outputCount {
            let position = Double(index) * 2.0 / 3.0
            let lower = min(Int(position), inputCount - 1)
            let upper = min(lower + 1, inputCount - 1)
            let value = Int(Double(sample(lower)) + Double(sample(upper) - sample(lower)) * (position - Double(lower)))
            var pcm = Int16(clamping: value).littleEndian
            withUnsafeBytes(of: &pcm) { output.append(contentsOf: $0) }
        }
        return output
    }
}
