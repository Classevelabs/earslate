package com.classeve.earslate.update

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    private fun play(
        availability: Int = UpdateAvailability.UPDATE_AVAILABLE,
        status: Int = InstallStatus.UNKNOWN,
        priority: Int = 0,
        immediate: Boolean = true,
        flexible: Boolean = true,
    ) = playStepFor(availability, status, priority, immediate, flexible)

    @Test
    fun aPlayReleaseUploadedAtPriorityFourOrFiveMustBeInstalled() {
        assertEquals(PlayStep.Require, play(priority = 5))
        assertEquals(PlayStep.Require, play(priority = 4))
        assertEquals(PlayStep.Offer, play(priority = 3))
        assertEquals(PlayStep.Offer, play(priority = 1))
        assertEquals(PlayStep.Nothing, play(priority = 0))
    }

    @Test
    fun playAsksNothingWithoutAnUpdateAndTakesUpOnlyAStalledRequiredOne() {
        assertEquals(PlayStep.Nothing, play(availability = UpdateAvailability.UPDATE_NOT_AVAILABLE, priority = 5))
        val inProgress = UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        assertEquals(PlayStep.Require, play(availability = inProgress, priority = 5))
        assertEquals(PlayStep.Nothing, play(availability = inProgress, priority = 2))
        assertEquals(PlayStep.Restart, play(status = InstallStatus.DOWNLOADED, priority = 2))
        assertEquals(PlayStep.Offer, play(priority = 5, immediate = false))
    }

    private val file = """{"version":"0.7.2","minimumVersion":"0.7.1","url":"https://classeve.com/downloads/Earslate-0.7.2.apk","extra":1}"""

    @Test
    fun theSiteFileIsReadAndAnUnknownFieldDoesNotBreakIt() {
        val policy = parsePolicy(file)!!
        assertEquals("0.7.2", policy.version)
        assertEquals("0.7.1", policy.minimumVersion)
    }

    @Test
    fun aFileThatPointsAnywhereButOurDownloadsIsRefused() {
        assertNull(parsePolicy(file.replace("https://classeve.com/downloads/", "https://example.com/")))
        assertNull(parsePolicy(file.replace("0.7.2\",\"minimumVersion", "x.y\",\"minimumVersion")))
        assertNull(parsePolicy("not json"))
    }

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(isOlder("0.7.9", "0.7.10"))
        assertTrue(isOlder("0.7.1", "0.8"))
        assertFalse(isOlder("0.7.1", "0.7.1"))
        assertFalse(isOlder("0.7.1-debug", "0.7.1"))
        assertFalse(isOlder("1.0.0", "0.9.9"))
    }

    @Test
    fun belowTheMinimumIsRequiredAndBelowTheNewestIsOffered() {
        val policy = parsePolicy(file)!!
        assertTrue(siteGateFor("0.7.0", policy, dismissed = null) is SiteGate.Required)
        assertTrue(siteGateFor("0.7.1", policy, dismissed = null) is SiteGate.Available)
        assertEquals(SiteGate.None, siteGateFor("0.7.2", policy, dismissed = null))
        assertEquals(SiteGate.None, siteGateFor("0.7.1", null, dismissed = null))
    }

    @Test
    fun laterHidesOnlyTheOfferedVersionAndNeverARequiredOne() {
        val policy = parsePolicy(file)!!
        assertEquals(SiteGate.None, siteGateFor("0.7.1", policy, dismissed = "0.7.2"))
        assertTrue(siteGateFor("0.7.1", policy.copy(version = "0.7.3"), dismissed = "0.7.2") is SiteGate.Available)
        assertTrue(siteGateFor("0.7.0", policy, dismissed = "0.7.2") is SiteGate.Required)
    }
}
