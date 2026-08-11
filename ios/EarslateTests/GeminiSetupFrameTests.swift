import XCTest
@testable import Earslate

/// The Gemini live-session setup frame, pinned.
///
/// Field placement here is asymmetric and load-bearing: transcription belongs
/// on `setup`, translation belongs inside `setup.generationConfig`. It looks
/// like an inconsistency worth tidying. It is not — the server rejects the
/// tidy version and closes the socket:
///
///     1007 Invalid JSON payload received. Unknown name
///     "outputAudioTranscription" at 'setup.generation_config':
///     Cannot find field.
///
/// The Android client shipped that shape, measured that failure on-device on
/// 2026-07-26, fixed it, and pinned it with GeminiProtocolContractTest. This
/// iOS client still carried the broken shape — every Gemini session with
/// captions would have been closed the moment it opened, so translation never
/// worked at all. It was found by comparing the two clients rather than by
/// anything either of them tested.
///
/// These are the same assertions the Android suite makes, so the two clients
/// cannot drift apart again without one of them going red.
final class GeminiSetupFrameTests: XCTestCase {

    private func geminiSetup(
        model: String = "gemini-3.5-live-translate-preview",
        targetLanguage: String = "es"
    ) throws -> (setup: [String: Any], generation: [String: Any]) {
        let json = LiveTranslationClient.setupFrame(
            provider: .gemini, model: model, targetLanguage: targetLanguage
        )
        let data = try XCTUnwrap(json.data(using: .utf8), "frame was not UTF-8")
        let root = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: data) as? [String: Any],
            "frame was not a JSON object"
        )
        let setup = try XCTUnwrap(root["setup"] as? [String: Any], "no setup object")
        let generation = try XCTUnwrap(
            setup["generationConfig"] as? [String: Any], "no generationConfig"
        )
        return (setup, generation)
    }

    func testTranscriptionSitsOnSetup() throws {
        let (setup, _) = try geminiSetup()
        XCTAssertNotNil(setup["inputAudioTranscription"],
                        "inputAudioTranscription must sit on setup")
        XCTAssertNotNil(setup["outputAudioTranscription"],
                        "outputAudioTranscription must sit on setup")
    }

    func testGenerationConfigRejectsTranscription() throws {
        let (_, generation) = try geminiSetup()
        XCTAssertNil(
            generation["inputAudioTranscription"],
            "generationConfig must not carry inputAudioTranscription — the server closes the socket with 1007"
        )
        XCTAssertNil(
            generation["outputAudioTranscription"],
            "generationConfig must not carry outputAudioTranscription — the server closes the socket with 1007"
        )
    }

    func testTranslationSitsInsideGenerationConfig() throws {
        let (setup, generation) = try geminiSetup()
        let translation = try XCTUnwrap(
            generation["translationConfig"] as? [String: Any],
            "translationConfig must sit inside generationConfig"
        )
        XCTAssertEqual(translation["targetLanguageCode"] as? String, "es")
        XCTAssertNil(setup["translationConfig"],
                     "the setup itself must not carry translationConfig")
    }

    func testModelIsNamespacedExactlyOnce() throws {
        // "models/" is prepended when absent. Doing it twice yields
        // models/models/... and the session is rejected.
        let (bare, _) = try geminiSetup(model: "gemini-3.5-live-translate-preview")
        XCTAssertEqual(bare["model"] as? String, "models/gemini-3.5-live-translate-preview")

        let (prefixed, _) = try geminiSetup(model: "models/gemini-3.5-live-translate-preview")
        XCTAssertEqual(prefixed["model"] as? String, "models/gemini-3.5-live-translate-preview")
    }

    func testAudioIsRequestedAsAResponseModality() throws {
        let (_, generation) = try geminiSetup()
        let modalities = try XCTUnwrap(generation["responseModalities"] as? [String])
        XCTAssertEqual(modalities, ["AUDIO"])
    }

    /// OpenAI speaks a different protocol entirely; a shared mistake here
    /// would be silent because the frames share no field names.
    func testOpenAIFrameIsASessionUpdateCarryingTheTargetLanguage() throws {
        let json = LiveTranslationClient.setupFrame(
            provider: .openai, model: "gpt-realtime", targetLanguage: "ja"
        )
        let data = try XCTUnwrap(json.data(using: .utf8))
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(root["type"] as? String, "session.update")
        let session = try XCTUnwrap(root["session"] as? [String: Any])
        let audio = try XCTUnwrap(session["audio"] as? [String: Any])
        let output = try XCTUnwrap(audio["output"] as? [String: Any])
        XCTAssertEqual(output["language"] as? String, "ja")
    }
}
