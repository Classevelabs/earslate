import AVFoundation
import Foundation

final class AudioCapture {
    private static let targetSampleRate = 16_000

    private let engine = AVAudioEngine()
    private var onPCM: ((Data, Int) -> Void)?
    private let bufferLock = NSLock()
    private var pendingPCM = Data()
    private static let batchBytes = 3_200 // 100 ms of mono PCM16 at 16 kHz

    var isRunning: Bool {
        engine.isRunning
    }

    func start(onPCM: @escaping (Data, Int) -> Void) throws {
        self.onPCM = onPCM
        bufferLock.lock()
        pendingPCM.removeAll(keepingCapacity: true)
        bufferLock.unlock()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            guard let self else { return }
            let pcm = Self.resampleToPCM16Mono16k(buffer)
            self.emitCompleteBatches(pcm)
        }
        engine.prepare()
        try engine.start()
    }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        onPCM = nil
        bufferLock.lock()
        pendingPCM.removeAll(keepingCapacity: true)
        bufferLock.unlock()
    }

    private func emitCompleteBatches(_ pcm: Data) {
        guard !pcm.isEmpty else { return }
        var batches: [Data] = []
        bufferLock.lock()
        pendingPCM.append(pcm)
        while pendingPCM.count >= Self.batchBytes {
            batches.append(pendingPCM.prefix(Self.batchBytes))
            pendingPCM.removeFirst(Self.batchBytes)
        }
        bufferLock.unlock()
        for batch in batches { onPCM?(batch, Self.targetSampleRate) }
    }

    private static func resampleToPCM16Mono16k(_ buffer: AVAudioPCMBuffer) -> Data {
        guard let channels = buffer.floatChannelData else { return Data() }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return Data() }

        let sourceRate = buffer.format.sampleRate
        let ratio = max(sourceRate / Double(targetSampleRate), 1)
        let outputCount = max(1, Int(Double(count) / ratio))
        let channelCount = max(1, Int(buffer.format.channelCount))
        var data = Data(capacity: outputCount * 2)

        for outputIndex in 0..<outputCount {
            let sourceIndex = min(count - 1, Int(Double(outputIndex) * ratio))
            var mixed: Float = 0
            for channelIndex in 0..<channelCount {
                mixed += channels[channelIndex][sourceIndex]
            }
            let clipped = max(-1, min(1, mixed / Float(channelCount)))
            let sample = Int16(clipped * Float(Int16.max))
            var littleEndian = sample.littleEndian
            withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
        }
        return data
    }
}
