import AVFoundation
import SwiftUI

@MainActor
final class TranslationViewModel: ObservableObject {
    @Published var state: LiveState = .idle
    @Published var caption = ""
    @Published var errorMessage: String?
    @Published var targetLanguage: String {
        didSet { UserDefaults.standard.set(targetLanguage, forKey: Self.targetLanguageKey) }
    }
    @Published var provider: TranslationProvider {
        didSet { UserDefaults.standard.set(provider.rawValue, forKey: Self.providerKey) }
    }

    static let supportedTargetLanguages = [
        "English", "Spanish", "French", "German", "Hindi", "Punjabi",
        "Mandarin Chinese", "Japanese", "Arabic",
    ]
    private static let targetLanguageKey = "earslate.targetLanguage"
    private static let providerKey = "earslate.provider"
    private static let languageCodes = [
        "English": "en", "Spanish": "es", "French": "fr", "German": "de",
        "Hindi": "hi", "Punjabi": "pa", "Mandarin Chinese": "zh-Hans",
        "Japanese": "ja", "Arabic": "ar",
    ]

    /// True while no provider key is stored, so the app can show setup instead
    /// of a Start button that could only ever fail. Nothing here reads or
    /// publishes the key itself.
    @Published private(set) var needsKey: Bool

    private let keys = ProviderKeyStore()
    private let bootstrapClient = BootstrapClient()
    private let liveClient = LiveTranslationClient()
    private let capture = AudioCapture()
    private let playback = AudioPlayback()
    private var startTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var speakingResetTask: Task<Void, Never>?
    private var manualStop = false

    init() {
        let savedLanguage = UserDefaults.standard.string(forKey: Self.targetLanguageKey)
        targetLanguage = savedLanguage?.isEmpty == false ? savedLanguage! : "English"
        provider = TranslationProvider(
            rawValue: UserDefaults.standard.string(forKey: Self.providerKey) ?? "auto"
        ) ?? .automatic
        needsKey = !ProviderKeyStore().hasAnyKey
    }

    /// Re-read whether a key exists. Called after setup completes, and on
    /// foreground — the Keychain can be emptied from outside this process
    /// (a restore onto a new device carries no `ThisDeviceOnly` item), and a
    /// Start button that cannot work is the "UI lying while failing" pattern.
    func refreshKeyState() {
        needsKey = !keys.hasAnyKey
    }

    func start() {
        switch state {
        case .bootstrapping, .connecting, .listening, .playing, .reconnecting:
            return
        case .idle, .failed:
            break
        }
        // Belt and braces with the view's needsKey gate: the tile-less iOS app
        // has one entry point today, but a future one (Shortcuts, a widget)
        // must not be able to open the mic for a session that cannot mint.
        refreshKeyState()
        if needsKey {
            errorMessage = BootstrapClient.missingKey(provider).message
            return
        }
        manualStop = false
        errorMessage = nil
        caption = ""
        reconnectTask?.cancel()
        startTask?.cancel()
        startTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await requestMic()
                try configureAudioSession()
                try await connectTransport()
            } catch {
                await cleanupTransport()
                errorMessage = error.localizedDescription
                state = .failed
            }
        }
    }

    func stop() async {
        manualStop = true
        startTask?.cancel()
        startTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        await cleanupTransport()
        state = .idle
    }

    private func connectTransport() async throws {
        state = .bootstrapping
        let code = Self.languageCodes[targetLanguage] ?? "en"
        let bootstrap = try await bootstrapClient.bootstrap(provider: provider, targetLanguage: code)

        try playback.start()
        state = .connecting
        try await liveClient.connect(
            bootstrap: bootstrap,
            targetLanguage: code,
            onCaption: { [weak self] text in
                Task { @MainActor in self?.appendCaption(text) }
            },
            onAudio: { [weak self] audio in
                Task { @MainActor in self?.handleAudio(audio) }
            },
            onDisconnect: { [weak self] message in
                Task { @MainActor in self?.recoverFromDisconnect(message) }
            }
        )
        let liveClient = liveClient
        try capture.start { data, rate in
            Task { await liveClient.sendAudio(data, sampleRate: rate) }
        }
        state = .listening
    }

    private func recoverFromDisconnect(_ message: String) {
        guard !manualStop else { return }
        guard reconnectTask == nil else { return }
        switch state {
        case .connecting, .listening, .playing, .reconnecting:
            break
        default:
            return
        }
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            guard let self else { return }
            capture.stop()
            playback.stop()
            await liveClient.close()
            for attempt in 1...4 {
                guard !Task.isCancelled, !manualStop else { return }
                state = .reconnecting
                try? await Task.sleep(nanoseconds: UInt64(min(attempt * attempt, 8)) * 1_000_000_000)
                do {
                    try await connectTransport()
                    reconnectTask = nil
                    return
                } catch {
                    if attempt == 4 {
                        errorMessage = message
                        state = .failed
                        reconnectTask = nil
                    }
                }
            }
        }
    }

    private func cleanupTransport() async {
        speakingResetTask?.cancel()
        speakingResetTask = nil
        capture.stop()
        playback.stop()
        await liveClient.close()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setPreferredSampleRate(16_000)
        try session.setPreferredIOBufferDuration(0.064)
        try session.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP]
        )
        try session.setActive(true)
    }

    private func appendCaption(_ text: String) {
        caption += text
        if caption.count > 1600 { caption = String(caption.suffix(1600)) }
    }

    private func handleAudio(_ audio: Data) {
        state = .playing
        playback.playPCM16(audio)
        speakingResetTask?.cancel()
        speakingResetTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            if self?.state == .playing { self?.state = .listening }
        }
    }

    private func requestMic() async throws {
        let session = AVAudioSession.sharedInstance()
        if session.recordPermission == .granted { return }
        let granted = await withCheckedContinuation { continuation in
            session.requestRecordPermission { continuation.resume(returning: $0) }
        }
        if !granted {
            throw HTTPError(
                status: 0,
                code: "microphone_denied",
                message: "Microphone permission is required for live translation."
            )
        }
    }
}
