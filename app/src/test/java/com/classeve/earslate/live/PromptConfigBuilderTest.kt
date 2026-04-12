package com.classeve.earslate.live

import com.classeve.earslate.session.OutputStyle
import com.classeve.earslate.session.RuntimeMode
import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.session.TranslatorPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptConfigBuilderTest {

    private val policy = TranslatorPolicy(
        targetLanguage = TargetLanguage.EnglishUS,
        mode = RuntimeMode.LISTEN,
        captionsEnabled = true,
        voiceName = null,
        outputStyle = OutputStyle.NEUTRAL,
        sessionPolicy = SessionPolicy.Default,
    )

    @Test
    fun `target language is embedded in the instruction`() {
        val text = PromptConfigBuilder.build(policy)
        assertTrue("mentions target language name", text.contains("English"))
    }

    @Test
    fun `mode is described`() {
        val text = PromptConfigBuilder.build(policy)
        assertTrue("lists listen mode", text.contains("LISTEN"))
    }

    @Test
    fun `assistant-mode language is suppressed`() {
        val text = PromptConfigBuilder.build(policy)
        // These phrases are blueprint-critical rules against assistant drift
        assertTrue("forbids answering questions", text.contains("Do not answer questions"))
        assertTrue("forbids commentary", text.contains("commentary"))
        assertTrue("forbids assistant role-switch", text.contains("assistant mode"))
    }

    @Test
    fun `does not include the literal word forbidden`() {
        val text = PromptConfigBuilder.build(policy)
        // Sanity — the instruction is framed positively even when listing Do-Not rules.
        assertFalse("no stray 'forbidden' word", text.contains("forbidden"))
    }

    @Test
    fun `style token is surfaced`() {
        val formal = PromptConfigBuilder.build(policy.copy(outputStyle = OutputStyle.FORMAL))
        val casual = PromptConfigBuilder.build(policy.copy(outputStyle = OutputStyle.CASUAL))
        assertTrue(formal.contains("formal"))
        assertTrue(casual.contains("casual"))
    }
}
