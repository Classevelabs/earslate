import AVFoundation
import Foundation

/// Microphone capture, delivered as 100 ms batches of 16 kHz mono PCM16 — the
/// format both provider sockets take.
///
/// The callback runs on the audio render thread, never the main thread. It is
/// `@Sendable` to say so at the type level: a caller that touches UI state from
/// inside it has a bug, and the compiler should be the one to say so.
final class AudioCapture {
    private static let targetSampleRate: Double = 16_000
    /// 100 ms of mono PCM16 at 16 kHz.
    private static let batchBytes = 3_200

    private let engine = AVAudioEngine()

    /// Guards every field below. `onPCM` is in here too — it used to be written
    /// bare from `stop()` while the tap thread was reading it, which is a data
    /// race on a closure reference, i.e. a crash on exactly the stop/reconnect
    /// path where it is hardest to reproduce.
    private let lock = NSLock()
    private var onPCM: (@Sendable (Data, Int) -> Void)?
    private var pendingPCM = Data()
    private var converter: AVAudioConverter?

    /// Interleaved is irrelevant at one channel, but stating it keeps
    /// `int16ChannelData` well defined rather than format-dependent.
    private let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatInt16,
        sampleRate: AudioCapture.targetSampleRate,
        channels: 1,
        interleaved: true
    )!

    var isRunning: Bool { engine.isRunning }

    func start(onPCM: @escaping @Sendable (Data, Int) -> Void) throws {
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            throw HTTPError(
                status: 0, code: "audio_input_unavailable",
                message: "The microphone isn't available right now. Try again."
            )
        }

        // AVAudioConverter, not a hand-rolled decimator.
        //
        // This used to pick every Nth sample: `sourceIndex = Int(outputIndex *
        // ratio)`. `setPreferredSampleRate(16_000)` is a request the input node
        // routinely declines — iPhone hardware runs at 48 kHz — so in practice
        // that discarded two samples in three with NO low-pass first, folding
        // everything above 8 kHz back into the speech band. It is audible as
        // grit on sibilants, and it costs recognition accuracy in exactly the
        // noisy rooms this app exists for.
        //
        // The converter is stateful across calls: it carries the filter history
        // that makes consecutive buffers join cleanly instead of clicking at
        // every seam. That is why it is built once here and only ever touched
        // from the tap thread.
        guard let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            throw HTTPError(
                status: 0, code: "audio_format_unsupported",
                message: "This device's microphone format isn't supported."
            )
        }
        converter.sampleRateConverterQuality = AVAudioQuality.high.rawValue

        lock.lock()
        self.onPCM = onPCM
        self.converter = converter
        pendingPCM.removeAll(keepingCapacity: true)
        lock.unlock()

        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 2048, format: inputFormat) { [weak self] buffer, _ in
            guard let self, let pcm = self.resample(buffer) else { return }
            self.emitCompleteBatches(pcm)
        }
        engine.prepare()
        try engine.start()
    }

    func stop() {
        // Remove the tap first so no new callback starts. One already inside the
        // tap may still finish; it reads `onPCM` under the lock and either sees
        // the old sink (harmless — the consumer is already cancelled) or nil.
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        lock.lock()
        onPCM = nil
        converter = nil
        pendingPCM.removeAll(keepingCapacity: true)
        lock.unlock()
    }

    /// Input buffer → 16 kHz mono PCM16, band-limited by the converter.
    private func resample(_ buffer: AVAudioPCMBuffer) -> Data? {
        lock.lock()
        let converter = self.converter
        lock.unlock()
        guard let converter, buffer.frameLength > 0 else { return nil }

        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        // Round up, plus slack: the converter may emit a frame or two more than
        // the ratio suggests as it drains its filter history, and a short
        // capacity silently truncates audio rather than failing.
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 64
        guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
            return nil
        }

        var supplied = false
        var conversionError: NSError?
        let status = converter.convert(to: output, error: &conversionError) { _, outStatus in
            // One input buffer per call. Saying `.noDataNow` on the second ask
            // returns what has been produced so far instead of blocking for
            // input that will arrive in the next tap callback.
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, output.frameLength > 0,
              let channel = output.int16ChannelData else { return nil }
        return Data(bytes: channel[0], count: Int(output.frameLength) * MemoryLayout<Int16>.size)
    }

    private func emitCompleteBatches(_ pcm: Data) {
        guard !pcm.isEmpty else { return }
        var batches: [Data] = []

        lock.lock()
        pendingPCM.append(pcm)
        while pendingPCM.count >= Self.batchBytes {
            // Re-wrapped in `Data` so the batch is zero-based. A `Data` slice
            // keeps its parent's indices, which reads correctly here and bites
            // the first time someone indexes one directly.
            batches.append(Data(pendingPCM.prefix(Self.batchBytes)))
            pendingPCM.removeFirst(Self.batchBytes)
        }
        let sink = onPCM
        lock.unlock()

        // Called outside the lock: the sink hands off to the send pump, and
        // calling out while holding a lock is how an unrelated future change
        // turns into a deadlock.
        guard let sink else { return }
        for batch in batches { sink(batch, Int(Self.targetSampleRate)) }
    }
}
