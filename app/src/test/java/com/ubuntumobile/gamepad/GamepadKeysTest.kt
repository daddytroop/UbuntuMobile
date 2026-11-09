package com.ubuntumobile.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which keys the pad holds down in keyboard mode. */
class GamepadKeysTest {
    @Test
    fun buttonsSendTheirKeysAndTheStickTheArrows() {
        val state = GamepadState().apply {
            a = true
            leftX = -0.9f
            leftY = 0.1f
        }
        assertEquals(setOf(0x20, PadParts.LEFT), heldKeys(state, GamepadSettings()))
    }

    @Test
    fun wasdAndChangedKeys() {
        val state = GamepadState().apply {
            dpadUp = true
            x = true
            rightTrigger = 1f
        }
        val settings = GamepadSettings(wasd = true, keys = PadParts.defaultKeys + ("X" to 'f'.code))
        assertEquals(setOf('w'.code, 'f'.code, 0xffe3), heldKeys(state, settings))
    }

    @Test
    fun aButtonSetToNoneSendsNothing() {
        val state = GamepadState().apply { b = true }
        val settings = GamepadSettings(keys = PadParts.defaultKeys + ("B" to 0))
        assertEquals(emptySet<Int>(), heldKeys(state, settings))
    }
}
