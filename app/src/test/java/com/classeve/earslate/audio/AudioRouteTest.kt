package com.classeve.earslate.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which outputs mean the translation is in someone's ear, and which mean the room hears it. */
class AudioRouteTest {

    private val phone = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    @Test
    fun `a phone on its own plays to the room`() {
        assertEquals(AudioRoute.SPEAKER, AudioRoute.of(phone))
        assertTrue(AudioRoute.SPEAKER.sharedWithMicrophone)
    }

    @Test
    fun `earbuds, wired or not, play in the ear`() {
        assertEquals(AudioRoute.BLUETOOTH, AudioRoute.of(phone + AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioRoute.BLUETOOTH, AudioRoute.of(phone + AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(AudioRoute.BLUETOOTH, AudioRoute.of(phone + AudioDeviceInfo.TYPE_HEARING_AID))
        assertEquals(AudioRoute.WIRED, AudioRoute.of(phone + AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioRoute.WIRED, AudioRoute.of(phone + AudioDeviceInfo.TYPE_USB_HEADSET))
        assertFalse(AudioRoute.BLUETOOTH.sharedWithMicrophone)
        assertFalse(AudioRoute.WIRED.sharedWithMicrophone)
    }

    // The telephone link alone carries no translation: the phone's loudspeaker does,
    // and the session must take turns with it.
    @Test
    fun `a watch or a car kit connected only for calls is not earbuds`() {
        assertEquals(AudioRoute.SPEAKER, AudioRoute.of(phone + AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }

    @Test
    fun `a loudspeaker over Bluetooth Low Energy is still a loudspeaker`() {
        assertEquals(AudioRoute.SPEAKER, AudioRoute.of(listOf(AudioDeviceInfo.TYPE_BLE_SPEAKER)))
    }

    @Test
    fun `an output that cannot be placed is treated as one the microphone can hear`() {
        assertEquals(AudioRoute.UNKNOWN, AudioRoute.of(emptyList()))
        assertTrue(AudioRoute.UNKNOWN.sharedWithMicrophone)
    }
}
