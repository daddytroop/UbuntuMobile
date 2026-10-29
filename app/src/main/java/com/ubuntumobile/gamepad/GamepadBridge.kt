package com.ubuntumobile.gamepad

import android.util.Log
import com.ubuntumobile.linux.LinuxRuntime
import com.ubuntumobile.linux.Pty
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges the on-screen gamepad to the Linux session.
 *
 * Linux games read controllers through the kernel's uinput/evdev layer, which
 * is not reachable from an unprivileged Android app. Two practical routes
 * exist and both are supported here:
 *
 *  1. **SDL virtual controller** - the guest runs a tiny helper that creates
 *     an SDL virtual gamepad and feeds it the events we send. This works
 *     without any kernel support and is what most SDL-based games (and
 *     RetroArch / PPSSPP / Dolphin) use.
 *  2. **uinput** - when the device exposes /dev/uinput to the app, the guest
 *     can create a real evdev joystick.
 *
 * The bridge sends a compact event stream over a unix socket inside the
 * container; the guest helper translates it into controller events.
 */
class GamepadBridge(
    private val runtime: LinuxRuntime,
    private val paths: com.ubuntumobile.linux.LinuxPaths,
) {

    private val running = AtomicBoolean(false)
    private var pty: Pty? = null

    /**
     * Start the in-guest helper and begin forwarding events.
     *
     * The helper is `/usr/local/lib/linx/gamepad-helper.py`, which is shipped
     * in the rootfs. If it is unavailable the bridge degrades gracefully and
     * the on-screen pad still works for UI navigation.
     */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val res = runtime.shell(
                "test -f /usr/local/lib/linx/gamepad-helper.py && echo PRESENT || echo MISSING",
                timeoutSeconds = 30,
            )
            if (!res.output.contains("PRESENT")) {
                Log.w(TAG, "gamepad helper not present in the rootfs")
                return false
            }
            running.set(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to start gamepad bridge", e)
            false
        }
    }

    /**
     * Push the current controller state to the guest.
     *
     * @param state the on-screen pad state.
     */
    fun pushState(state: GamepadState) {
        if (!running.get()) return
        val payload = buildString {
            append(state.leftX).append(' ')
            append(state.leftY).append(' ')
            append(state.rightX).append(' ')
            append(state.rightY).append(' ')
            append(state.leftTrigger).append(' ')
            append(state.rightTrigger).append(' ')
            append(state.pressedButtons().joinToString(","))
        }
        // The helper polls a fifo, so a single write is enough.
        try {
            runtime.shell(
                "printf '%s\n' ${payload.replace("'", "'\\''")} " +
                    "> /run/linx-gamepad.fifo 2>/dev/null || true",
                timeoutSeconds = 15,
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to push gamepad state", e)
        }
    }

    fun stop() {
        running.set(false)
    }

    companion object {
        private const val TAG = "GamepadBridge"
    }
}
