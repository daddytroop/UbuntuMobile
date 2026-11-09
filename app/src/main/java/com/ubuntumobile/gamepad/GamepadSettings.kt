package com.ubuntumobile.gamepad

import android.content.SharedPreferences
import org.json.JSONObject

/** How the pad's buttons are coloured. */
enum class PadStyle(val label: String) {
    CLASSIC("Classic"),
    MONO("Mono"),
    ACCENT("Accent"),
}

/** What the pad looks like to Linux. */
enum class PadMode(val label: String) {
    /** A game controller (a virtual uinput joystick; needs /dev/uinput). */
    CONTROLLER("Controller"),
    /** Keys on the keyboard, which every game understands. */
    KEYBOARD("Keyboard keys"),
}

/** A key a button can send in [PadMode.KEYBOARD], by its X keysym. */
data class KeyChoice(val label: String, val keysym: Int)

/** The controls that can be moved, and the buttons that can send keys. */
object PadParts {
    val movable = listOf("lshoulder", "rshoulder", "lstick", "rstick", "dpad", "face", "middle")
    val buttons = listOf("A", "B", "X", "Y", "L1", "R1", "LT", "RT", "START", "SELECT")

    val keys: List<KeyChoice> = buildList {
        add(KeyChoice("Space", 0x20))
        add(KeyChoice("Enter", 0xff0d))
        add(KeyChoice("Esc", 0xff1b))
        add(KeyChoice("Tab", 0xff09))
        add(KeyChoice("Shift", 0xffe1))
        add(KeyChoice("Ctrl", 0xffe3))
        add(KeyChoice("Alt", 0xffe9))
        add(KeyChoice("Backspace", 0xff08))
        ('a'..'z').forEach { add(KeyChoice(it.uppercase(), it.code)) }
        ('0'..'9').forEach { add(KeyChoice(it.toString(), it.code)) }
        add(KeyChoice("None", 0))
    }

    fun label(keysym: Int) = keys.firstOrNull { it.keysym == keysym }?.label ?: "?"

    /** Common choices for PC games: jump, back, two actions, run and crouch. */
    val defaultKeys = mapOf(
        "A" to 0x20, "B" to 0xff1b, "X" to 'z'.code, "Y" to 'x'.code,
        "L1" to 'q'.code, "R1" to 'e'.code, "LT" to 0xffe1, "RT" to 0xffe3,
        "START" to 0xff0d, "SELECT" to 0xff09,
    )

    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
}

/** Everything the gamepad editor changes; kept in the app's preferences. */
data class GamepadSettings(
    val opacity: Float = 0.55f,
    /** 0.7 to 1.4 times the normal size. */
    val size: Float = 1f,
    val style: PadStyle = PadStyle.CLASSIC,
    /** Null until chosen: a controller when Linux can have one, keys otherwise. */
    val mode: PadMode? = null,
    /** The sticks and D-pad as WASD rather than the arrow keys (keyboard mode). */
    val wasd: Boolean = false,
    val keys: Map<String, Int> = PadParts.defaultKeys,
    /** How far each part was moved from its place, in dp. */
    val offsets: Map<String, Pair<Float, Float>> = emptyMap(),
) {
    fun offset(part: String) = offsets[part] ?: (0f to 0f)

    fun save(prefs: SharedPreferences) {
        val json = JSONObject()
            .put("opacity", opacity.toDouble())
            .put("size", size.toDouble())
            .put("style", style.name)
            .put("wasd", wasd)
            .put("keys", JSONObject(keys.mapValues { it.value }))
            .put("offsets", JSONObject(offsets.mapValues { (_, v) -> "${v.first},${v.second}" }))
        mode?.let { json.put("mode", it.name) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    companion object {
        private const val KEY = "gamepad"

        fun load(prefs: SharedPreferences): GamepadSettings {
            val json = try {
                JSONObject(prefs.getString(KEY, null) ?: return GamepadSettings(
                    opacity = prefs.getFloat("gamepad_opacity", 0.55f),
                ))
            } catch (_: Exception) {
                return GamepadSettings()
            }
            val keys = json.optJSONObject("keys")?.let { o ->
                PadParts.defaultKeys + o.keys().asSequence().associateWith { o.optInt(it) }
            } ?: PadParts.defaultKeys
            val offsets = json.optJSONObject("offsets")?.let { o ->
                o.keys().asSequence().mapNotNull { k ->
                    val parts = o.optString(k).split(",")
                    val x = parts.getOrNull(0)?.toFloatOrNull()
                    val y = parts.getOrNull(1)?.toFloatOrNull()
                    if (x == null || y == null) null else k to (x to y)
                }.toMap()
            } ?: emptyMap()
            return GamepadSettings(
                opacity = json.optDouble("opacity", 0.55).toFloat().coerceIn(0.15f, 1f),
                size = json.optDouble("size", 1.0).toFloat().coerceIn(0.7f, 1.4f),
                style = PadStyle.entries.firstOrNull { it.name == json.optString("style") } ?: PadStyle.CLASSIC,
                mode = PadMode.entries.firstOrNull { it.name == json.optString("mode") },
                wasd = json.optBoolean("wasd", false),
                keys = keys,
                offsets = offsets,
            )
        }
    }
}

/**
 * The keys the pad holds down in keyboard mode, for its current state:
 * the buttons' own keys, and the left stick and D-pad as four direction
 * keys.
 */
fun heldKeys(state: GamepadState, settings: GamepadSettings): Set<Int> = buildSet {
    val pressed = state.pressedButtons() +
        (if (state.leftTrigger > 0.5f) setOf("LT") else emptySet()) +
        (if (state.rightTrigger > 0.5f) setOf("RT") else emptySet())
    for (b in pressed) {
        val k = settings.keys[b] ?: continue
        if (k != 0) add(k)
    }
    val left = state.dpadLeft || state.leftX < -0.5f
    val right = state.dpadRight || state.leftX > 0.5f
    val up = state.dpadUp || state.leftY < -0.5f
    val down = state.dpadDown || state.leftY > 0.5f
    if (settings.wasd) {
        if (left) add('a'.code)
        if (right) add('d'.code)
        if (up) add('w'.code)
        if (down) add('s'.code)
    } else {
        if (left) add(PadParts.LEFT)
        if (right) add(PadParts.RIGHT)
        if (up) add(PadParts.UP)
        if (down) add(PadParts.DOWN)
    }
}
