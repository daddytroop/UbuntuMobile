package com.ubuntumobile

import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubuntumobile.display.DesktopSurface
import com.ubuntumobile.display.DisplayActions
import com.ubuntumobile.display.DisplayScreen
import com.ubuntumobile.display.DisplaySession
import com.ubuntumobile.display.PresentationOn
import com.ubuntumobile.display.TrackpadScreen
import com.ubuntumobile.display.androidKeyToX11
import com.ubuntumobile.display.rememberExternalDisplay
import com.ubuntumobile.gamepad.GamepadBridge
import com.ubuntumobile.gamepad.GamepadState
import com.ubuntumobile.gamepad.VirtualGamepad
import com.ubuntumobile.linux.LinuxPaths
import com.ubuntumobile.linux.LinuxRuntime
import com.ubuntumobile.linux.Pty
import com.ubuntumobile.linux.RootfsInstaller
import com.ubuntumobile.ui.AnsiTerminal
import com.ubuntumobile.ui.AppCatalog
import com.ubuntumobile.ui.AppEntry
import com.ubuntumobile.ui.FlatpakApp
import com.ubuntumobile.ui.StoreScreen
import com.ubuntumobile.ui.TerminalScreen
import com.ubuntumobile.ui.UbuntuShell
import com.ubuntumobile.ui.UbuntuViewModel
import com.ubuntumobile.ui.theme.UbuntuPalette
import com.ubuntumobile.ui.theme.UbuntuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /**
     * The display session that should receive key events, if one is open.
     * Set while the Linux display is in the foreground.
     */
    private var keyTarget: DisplaySession? = null

    /**
     * What the back button should do.
     *
     * The Linux desktop is the main screen, so the back button leaves it
     * for the app launcher rather than closing the app.
     */
    private var backAction: (() -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            UbuntuTheme {
                UbuntuApp(
                    keyTarget = { keyTarget = it },
                    registerBack = { action -> backAction = action },
                )
            }
        }
    }

    /**
     * Route key events to the Linux display when it is open.
     *
     * Doing this at the Activity level catches hardware keyboards, Bluetooth
     * keyboards and the soft keyboard alike.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val session = keyTarget
        if (session != null && session.connected) {
            val down = event.action == android.view.KeyEvent.ACTION_DOWN
            // The release must name the key the press did, even if Shift
            // changed in between, or X keeps the key held down.
            val keysym = if (down) {
                keysymFor(event)?.also { pressedKeys[event.keyCode] = it }
            } else {
                pressedKeys.remove(event.keyCode) ?: keysymFor(event)
            }
            if (keysym != null) {
                session.key(keysym, down)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** The keysym each key currently held down was sent as. */
    private val pressedKeys = HashMap<Int, Int>()

    /**
     * The keysym for a key event.
     *
     * A printable key is sent as the character it types, with Shift and the
     * keyboard layout applied: Android may report Shift only in the event's
     * meta state (no Shift key event of its own), and X would then type the
     * unshifted character. Ctrl and Alt are left out, so Ctrl+C is still C.
     * Everything else (Enter, the arrows, F1...) has a fixed keysym.
     */
    private fun keysymFor(event: android.view.KeyEvent): Int? {
        val meta = event.metaState and
            (android.view.KeyEvent.META_CTRL_MASK or android.view.KeyEvent.META_ALT_MASK or
                android.view.KeyEvent.META_META_MASK).inv()
        val char = event.getUnicodeChar(meta)
        if (char > 0x20 && char and android.view.KeyCharacterMap.COMBINING_ACCENT == 0) {
            return if (char <= 0xFF) char else 0x01000000 or char
        }
        return androidKeyToX11(event.keyCode)
    }

    @Deprecated("the onBackPressedDispatcher is the modern way to do this")
    override fun onBackPressed() {
        if (backAction?.invoke() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

/** Which surface is currently on screen. */
private enum class Screen { DESKTOP, TERMINAL, STORE, GAMEPAD, DISPLAY }

/** Port the in-container X display exports its RFB stream on. */
private const val DISPLAY_PORT = 5901

/** X display number used inside the container. */
private const val DISPLAY_NUM = 99

@Composable
fun UbuntuApp(
    keyTarget: (DisplaySession?) -> Unit = {},
    registerBack: (() -> Boolean) -> Unit = {},
    vm: UbuntuViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Default to the real Linux desktop; the Compose app launcher is still
    // available as an overlay/secondary screen.
    var screen by remember { mutableStateOf(Screen.DISPLAY) }

    // What the back button does. The Linux desktop is the main screen, so
    // the back button leaves it for the app launcher rather than closing
    // the app.
    LaunchedEffect(screen) {
        registerBack {
            when (screen) {
                Screen.DISPLAY -> {
                    screen = Screen.DESKTOP
                    true
                }
                Screen.DESKTOP -> false
                else -> {
                    screen = Screen.DISPLAY
                    true
                }
            }
        }
    }

    val context = LocalContext.current
    val config = LocalConfiguration.current
    val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val wide = config.screenWidthDp >= 600

    // The X screen takes the shape of the area the desktop is drawn in -
    // on the phone (below the status bar and the toolbar) or on a monitor -
    // which the display surfaces measure. Anything else letterboxes the
    // desktop with black bars. When the area changes (the phone rotates,
    // the app is docked or resized) the X screen is resized to match.
    var canvasSize by remember { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    var monitorSize by remember { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }

    // A monitor connected over USB-C or HDMI. The desktop moves there and
    // the phone becomes its trackpad, unless asked to stay on the phone.
    val externalDisplay = rememberExternalDisplay()
    var preferMonitor by remember { mutableStateOf(true) }
    val docked = externalDisplay != null && preferMonitor
    LaunchedEffect(externalDisplay) { if (externalDisplay != null) preferMonitor = true }

    val target: DisplayTarget? = if (docked) {
        monitorSize?.let { displayTargetFor(it, density = 1f, monitor = true) }
    } else {
        canvasSize?.let { displayTargetFor(it, context.resources.displayMetrics.density, monitor = false) }
    }
    // The start-up code waits on this, so it has to be state: a plain
    // value is captured once and never seen to change.
    val currentTarget by androidx.compose.runtime.rememberUpdatedState(target)
    // The UI scale the desktop is running with right now.
    var appliedScale by remember { mutableStateOf(2) }

    // Hidden text field used to raise the Android soft keyboard so it can
    // send key events to the VNC session.
    val keyboardFocus = remember { FocusRequester() }
    // The field can only take the focus once the keyboard button asked for
    // it. With a physical keyboard Android leaves touch mode, and Compose
    // then focuses the first thing that can be focused - this field - which
    // brings up the soft keyboard nobody asked for.
    var keyboardWanted by remember { mutableStateOf(false) }

    // Terminal state
    val terminal = remember { AnsiTerminal() }
    var terminalLines by remember { mutableStateOf(listOf<String>()) }
    var pty by remember { mutableStateOf<Pty?>(null) }
    // The X server lives inside the container, and proot kills everything it
    // started when it exits - so it needs a proot process that stays alive.
    var displayProcess by remember { mutableStateOf<Process?>(null) }
    val scope = rememberCoroutineScope()

    // The connection to the desktop. It belongs to the app rather than to
    // a screen, because the phone and a monitor can both show it.
    val session = remember {
        DisplaySession("127.0.0.1", DISPLAY_PORT).apply {
            // Debug builds can record the RFB stream for offline
            // replay: create files/vnc-capture to switch it on.
            if (BuildConfig.DEBUG && java.io.File(context.filesDir, "vnc-capture").exists()) {
                captureFile = java.io.File(context.cacheDir, "vnc-stream.bin")
            }
        }
    }
    LaunchedEffect(state.isInstalled) {
        if (state.isInstalled) session.keepConnected()
    }

    DisposableEffect(Unit) {
        onDispose {
            session.stop()
            // Stop the X server first: killing the proot process that hosts
            // it does not stop the server itself, and a leftover server
            // would hold the RFB port.
            val proc = displayProcess
            displayProcess = null
            if (proc != null) {
                Thread({
                    try {
                        vm.runtime.shell("/usr/local/lib/linx/display-stop.sh", timeoutSeconds = 20)
                    } catch (_: Exception) {
                    }
                    proc.destroyForcibly()
                }, "display-stop").apply { isDaemon = true }.start()
            }
        }
    }

    LaunchedEffect(Unit) {
        terminal.setSink { terminalLines = it }
    }

    /**
     * Start the X display inside the Ubuntu container.
     *
     * Xvnc (TigerVNC) runs an X server and exports it over RFB in one
     * process. Because proot shares the host network namespace, the app
     * can reach the RFB port on 127.0.0.1 directly.
     *
     * proot kills every process it started when it exits, so the server
     * needs a proot process of its own that stays alive for as long as the
     * display is wanted.
     */
    suspend fun startLinuxDisplay() {
        // The display screen is the first thing shown, so its size is known
        // almost at once; fall back to the whole screen if it never is.
        val start = kotlinx.coroutines.withTimeoutOrNull(3000) {
            androidx.compose.runtime.snapshotFlow { currentTarget }.first { it != null }
        } ?: context.resources.displayMetrics.let {
            displayTargetFor(androidx.compose.ui.unit.IntSize(it.widthPixels, it.heightPixels), it.density, monitor = false)
        }
        val geometry = "${start.width}x${start.height}"
        appliedScale = start.scale
        withContext(Dispatchers.IO) {
            displayProcess?.destroyForcibly()
            com.ubuntumobile.linux.ContainerSetup.syncScripts(context, vm.paths)
            vm.paths.appendLog("starting the Linux display ($geometry)")
            displayProcess = vm.runtime.startPersistent(
                listOf(
                    "/bin/bash", "-c",
                    "export LINX_DISPLAY=$DISPLAY_NUM LINX_RFB_PORT=$DISPLAY_PORT; " +
                        "export LINX_GEOMETRY=$geometry LINX_DEPTH=24 LINX_UI_SCALE=${start.scale}; " +
                        "/usr/local/lib/linx/display-start.sh; " +
                        // Keep the container alive: the X server is a child
                        // of this shell, and proot kills its children when
                        // it exits.
                        "exec sleep infinity",
                ),
            )
            if (displayProcess == null) {
                vm.paths.appendLog("the display process could not be started")
                return@withContext
            }
            // Give the server a moment to open the RFB port and start XFCE.
            delay(5000)
            // If the port is not open yet, the server is still starting (or
            // it failed); either way the diagnostics are worth having.
            val port = vm.runtime.shell(
                "(exec 3<>/dev/tcp/127.0.0.1/$DISPLAY_PORT) 2>/dev/null && echo OPEN || echo CLOSED",
                30,
            )
            vm.paths.appendLog("the RFB port is ${port.output.trim()}")
            if (!port.output.contains("OPEN")) {
                vm.runtime.dumpDisplayDiagnostics()
            }
        }
    }

    // Once Ubuntu is installed, boot straight into the real desktop.
    LaunchedEffect(state.isInstalled) {
        if (state.isInstalled && displayProcess == null) {
            startLinuxDisplay()
        }
    }

    // Follow the drawing area: resize the X screen and, between the phone
    // and a monitor, change the UI scale. A rotation passes through
    // in-between sizes, so wait for it to settle first.
    LaunchedEffect(target, displayProcess) {
        val t = target ?: return@LaunchedEffect
        delay(400)
        session.resize(t.width, t.height)
        if (displayProcess != null && t.scale != appliedScale) {
            appliedScale = t.scale
            vm.paths.appendLog("the desktop is now ${t.width}x${t.height} at scale ${t.scale}")
            withContext(Dispatchers.IO) {
                vm.runtime.shell("/usr/local/lib/linx/display-scale.sh ${t.scale}", 30)
            }
        }
    }

    // On a monitor, the desktop.
    if (state.isInstalled && docked && externalDisplay != null) {
        PresentationOn(externalDisplay) {
            DesktopSurface(
                session = session,
                onCanvasSize = { monitorSize = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // In landscape the desktop gets the whole screen: the status and
    // navigation bars hide until swiped in.
    val activityWindow = (context as? android.app.Activity)?.window
    val rootView = LocalView.current
    LaunchedEffect(landscape, screen, docked, state.isInstalled) {
        val window = activityWindow ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, rootView)
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (state.isInstalled && landscape && screen == Screen.DISPLAY && !docked) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
        } else {
            controller.show(bars)
        }
    }

    // A shell on a PTY, without the desktop. Useful when the display does
    // not come up.
    fun openConsole() {
        screen = Screen.TERMINAL
        if (pty?.isRunning == true) return

        val session = Pty(vm.runtime, vm.paths)
        try {
            session.startShell(rows = 40, cols = 120) { data, len ->
                terminal.feed(data, len)
            }
            pty = session
        } catch (e: Exception) {
            val message = "!! ${e.message}\n".toByteArray()
            terminal.feed(message, message.size)
        }
    }

    /** Launch a Linux GUI app as a window in the running XFCE desktop. */
    fun launchOnDisplay(command: List<String>) {
        // Set DISPLAY to the in-container X server. Because proot shares
        // the network namespace and /tmp, a separate proot process can
        // still connect to the X11 unix socket started by the display
        // process.
        vm.runtime.startPersistent(
            // A login shell resets PATH from /etc/profile, so the
            // stand-in bwrap is put in front of it inside the command.
            listOf(
                "/bin/bash", "-lc",
                "export PATH=/usr/local/lib/linx/nosandbox:\$PATH; exec " +
                    command.joinToString(" ") { quote(it) },
            ),
            mapOf(
                "DISPLAY" to ":$DISPLAY_NUM",
                // GTK applications take the scale from the desktop's
                // XSETTINGS; Qt ones only read it when they start.
                "QT_SCALE_FACTOR" to appliedScale.toString(),
                "FLATPAK_BWRAP" to "/usr/bin/bwrap",
            ),
        )
    }

    /** Show the Android soft keyboard so key events reach the VNC session. */
    fun showSoftKeyboard(view: android.view.View, ctx: android.content.Context) {
        keyboardWanted = true
        keyboardFocus.requestFocus()
        val imm = ContextCompat.getSystemService(ctx, InputMethodManager::class.java)
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    // Install / setup screen
    if (!state.isInstalled) {
        SetupScreen(
            state = state,
            onInstall = { vm.installUbuntu() },
        )
        return
    }

    when (screen) {
        Screen.DESKTOP -> {
            val apps = remember(state.installedApps) {
                (AppCatalog.builtin + state.installedApps).distinctBy { it.id }
            }
            UbuntuShell(
                state = state,
                apps = apps,
                wide = wide,
                onLaunchApp = { entry ->
                    when (entry.id) {
                        "store" -> screen = Screen.STORE
                        "gamepad" -> screen = Screen.GAMEPAD
                        "console" -> openConsole()
                        else -> {
                            // Linux apps open as windows on the desktop, so
                            // go there to see them.
                            launchOnDisplay(entry.command)
                            screen = Screen.DISPLAY
                        }
                    }
                },
                onOpenStore = { screen = Screen.STORE },
                onRefresh = { vm.refreshSystemInfo() },
                onBack = { screen = Screen.DISPLAY },
                // The launcher's gradient continues behind the system bars.
                modifier = Modifier
                    .background(UbuntuPalette.Aubergine)
                    .systemBarsPadding(),
            )
        }

        Screen.TERMINAL -> {
            TerminalSurface(
                lines = terminalLines,
                landscape = landscape,
                onClose = {
                    pty?.close()
                    pty = null
                    screen = Screen.DISPLAY
                },
                onSend = { text -> pty?.write(text + "\n") },
            )
        }

        Screen.STORE -> {
            StoreScreen(
                searchRemote = { query ->
                    val out = vm.guestCommand(
                        "/usr/local/lib/linx/flatpak-search.sh ${quote(query)}",
                        timeout = 180,
                    )
                    out.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                        val p = line.split("|")
                        if (p.isEmpty()) return@mapNotNull null
                        FlatpakApp(
                            id = p[0].trim(),
                            name = p.getOrNull(2)?.takeIf { it.isNotBlank() && it != "-" } ?: p[0].trim(),
                            version = p.getOrNull(1)?.trim() ?: "",
                        )
                    }
                },
                installApp = { appId ->
                    vm.guestCommand(
                        "/usr/local/lib/linx/flatpak-install.sh ${quote(appId)}",
                        timeout = 900,
                    )
                },
                onClose = { screen = Screen.DISPLAY },
                modifier = Modifier.systemBarsPadding(),
            )
        }

        Screen.GAMEPAD -> {
            val pad = remember { GamepadState() }
            val bridge = remember { GamepadBridge(vm.runtime, vm.paths) }
            var bridgeActive by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                bridgeActive = bridge.start()
            }
            DisposableEffect(Unit) {
                onDispose {
                    bridge.stop()
                    pad.reset()
                }
            }
            // Push the controller state into the container whenever it changes.
            LaunchedEffect(pad.leftX, pad.leftY, pad.rightX, pad.rightY,
                pad.leftTrigger, pad.rightTrigger, pad.pressedButtons()) {
                bridge.pushState(pad)
            }

            Box(Modifier.fillMaxSize().background(Color(0xFF1C1B1A))) {
                VirtualGamepad(
                    state = pad,
                    landscape = landscape,
                    modifier = Modifier.systemBarsPadding(),
                )
                if (!bridgeActive) {
                    Text(
                        "On-screen controls only \u2014 this device does not expose /dev/uinput, " +
                            "so Linux games cannot see a virtual pad.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                IconButton(
                    onClick = { screen = Screen.DISPLAY },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.4f)),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        Screen.DISPLAY -> {
            DisposableEffect(Unit) {
                keyTarget(session)
                onDispose { keyTarget(null) }
            }
            // An invisible text field that captures key events for the VNC
            // session. It is shown behind the display so it never blocks
            // touch, but it can receive focus when the keyboard button is
            // pressed.
            val view = LocalView.current
            val actions = DisplayActions(
                onShowLauncher = { screen = Screen.DESKTOP },
                onOpenTerminal = { launchOnDisplay(listOf("/usr/bin/xfce4-terminal")) },
                onOpenFiles = { launchOnDisplay(listOf("/usr/bin/thunar")) },
                onOpenStore = { screen = Screen.STORE },
                onOpenGamepad = { screen = Screen.GAMEPAD },
                onShowKeyboard = { showSoftKeyboard(view, context) },
                onReconnect = { session.retry() },
                onUseMonitor = if (externalDisplay != null && !docked) ({ preferMonitor = true }) else null,
            )
            // The toolbar colour continues behind the status bar.
            Box(Modifier.fillMaxSize().background(UbuntuPalette.DarkSurface)) {
                // Whatever the soft keyboard commits is typed into X and
                // the field is left empty again.
                androidx.compose.foundation.text.BasicTextField(
                    value = "",
                    onValueChange = { typed -> if (typed.isNotEmpty()) session.type(typed) },
                    modifier = Modifier
                        .focusProperties { canFocus = keyboardWanted }
                        .focusRequester(keyboardFocus)
                        .size(1.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                )
                val insets = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout),
                )
                if (docked) {
                    TrackpadScreen(
                        session = session,
                        actions = actions,
                        onShowOnPhone = { preferMonitor = false },
                        modifier = insets,
                    )
                } else {
                    DisplayScreen(
                        session = session,
                        landscape = landscape,
                        actions = actions,
                        onCanvasSize = { canvasSize = it },
                        modifier = insets,
                    )
                }
            }
        }
    }
}

/** The size and UI scale the X screen should have. */
private data class DisplayTarget(val width: Int, val height: Int, val scale: Int)

/**
 * The X screen for a drawing area of [size] pixels.
 *
 * The desktop is laid out in pixels - the panel is 30 pixels tall, the
 * fonts are 10 points - so at the native resolution of a modern handset
 * (1440x3120) it would be far too small to tap. On a phone the X screen is
 * therefore a normal desktop screen with the phone's shape (short side 720
 * pixels) with everything doubled (scale 2), and the phone scales it up.
 *
 * A monitor gets its own resolution at scale 1, a plain desktop; a tablet
 * (or a large window) one pixel per dp, also at scale 1. Either is capped
 * at about 1080p worth of pixels: every pixel is streamed and decoded, and
 * more of them only makes the desktop slower.
 */
private fun displayTargetFor(
    size: androidx.compose.ui.unit.IntSize,
    density: Float,
    monitor: Boolean,
): DisplayTarget {
    // Round to even numbers: X servers are happier with them.
    fun even(v: Float) = maxOf(2, (v.toInt() / 2) * 2)
    val w = size.width.toFloat().coerceAtLeast(1f)
    val h = size.height.toFloat().coerceAtLeast(1f)
    if (monitor || minOf(w, h) / density >= 600f) {
        val dw = if (monitor) w else w / density
        val dh = if (monitor) h else h / density
        val fit = minOf(1f, kotlin.math.sqrt(1920f * 1200f / (dw * dh)))
        return DisplayTarget(even(dw * fit), even(dh * fit), 1)
    }
    val short = minOf(w, h)
    val fit = if (short > 720) 720f / short else 1f
    return DisplayTarget(even(w * fit), even(h * fit), 2)
}

private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"


/** The first-run screen that provisions Ubuntu. */
@Composable
private fun SetupScreen(
    state: com.ubuntumobile.ui.UbuntuState,
    onInstall: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UbuntuPalette.Aubergine)
            .systemBarsPadding()
            .padding(28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            com.ubuntumobile.ui.UbuntuLogo(72.dp)
            Spacer(Modifier.height(24.dp))
            Text("Ubuntu 26.04", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "A complete Linux system on your phone",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))

            when (state.installPhase) {
                RootfsInstaller.Progress.Phase.IDLE -> {
                    Text(
                        "Ubuntu ships inside the app, with Flatpak and Flathub\n" +
                            "ready to go. Setting it up unpacks about 1.2 GB\n" +
                            "and takes a minute or two. No download needed.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(UbuntuPalette.UbuntuOrange)
                            .androidClickable(onInstall)
                            .padding(horizontal = 32.dp, vertical = 12.dp),
                    ) {
                        Text("Install Ubuntu", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                RootfsInstaller.Progress.Phase.DONE -> {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text("Ubuntu 26.04 is ready", color = Color.White)
                }

                RootfsInstaller.Progress.Phase.FAILED -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "The Ubuntu install did not finish",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            state.installDetail,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(UbuntuPalette.UbuntuOrange)
                                .androidClickable(onInstall)
                                .padding(horizontal = 32.dp, vertical = 12.dp),
                        ) {
                            Text("Retry", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Retrying reuses the parts that already worked.\n" +
                                "The details are in files/install.log on the phone.",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    Text(
                        state.installDetail.ifBlank { "Preparing…" },
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { state.installFraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = UbuntuPalette.UbuntuOrange,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${(state.installFraction * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** Terminal surface with a header bar. */
@Composable
private fun TerminalSurface(
    lines: List<String>,
    landscape: Boolean,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(UbuntuPalette.TerminalBackground)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(
                "Terminal — bash",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
        TerminalScreen(
            lines = lines,
            onInput = onSend,
            modifier = Modifier.weight(1f),
        )
        TerminalInputBar(onSend = onSend)
    }
}

/** Simple command entry row under the terminal. */
@Composable
private fun TerminalInputBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$", color = UbuntuPalette.TerminalGreen, fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = UbuntuPalette.TerminalForeground,
                fontSize = 15.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            "type a command…",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 15.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(UbuntuPalette.UbuntuOrange)
                .androidClickable {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text("Run", color = Color.White, fontSize = 13.sp)
        }
    }
}

/** Small helper so clickable modifiers stay readable. */
private fun Modifier.androidClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
