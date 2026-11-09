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
import androidx.compose.material.icons.filled.Opacity
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
import com.ubuntumobile.ui.DesktopSize
import com.ubuntumobile.ui.SettingsScreen
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
private enum class Screen { DESKTOP, TERMINAL, STORE, GAMEPAD, DISPLAY, SETTINGS, ONBOARDING }

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

    // The app's own settings (see SettingsScreen).
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var desktopSize by remember {
        mutableStateOf(
            DesktopSize.entries.firstOrNull { it.name == prefs.getString("desktop_size", null) }
                ?: DesktopSize.STANDARD,
        )
    }
    var fullScreen by remember { mutableStateOf(prefs.getBoolean("full_screen", true)) }

    var monitorSize by remember { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }

    // A monitor connected over USB-C or HDMI. The desktop moves there and
    // the phone becomes its trackpad, unless asked to stay on the phone.
    val externalDisplay = rememberExternalDisplay()
    var preferMonitor by remember { mutableStateOf(true) }
    val docked = externalDisplay != null && preferMonitor
    LaunchedEffect(externalDisplay) { if (externalDisplay != null) preferMonitor = true }

    val target: DisplayTarget? = if (docked) {
        monitorSize?.let { displayTargetFor(it, density = 1f, monitor = true, desktopSize.shortSide) }
    } else {
        canvasSize?.let {
            displayTargetFor(it, context.resources.displayMetrics.density, monitor = false, desktopSize.shortSide)
        }
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
            displayTargetFor(
                androidx.compose.ui.unit.IntSize(it.widthPixels, it.heightPixels),
                it.density, monitor = false, desktopSize.shortSide,
            )
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

    // What makes the desktop a complete one - sound, Ubuntu's look, Firefox
    // and whatever else onboarding chose - is not in the image: Firefox
    // cannot be (Ubuntu's package is a snap), and the rest keeps the APK
    // small. It is installed the first time the desktop is up, in the
    // background (linx-setup.sh).
    val firefoxBinary = remember { java.io.File(vm.paths.root, "usr/lib/firefox/firefox") }
    var browserInstalled by remember { mutableStateOf<Boolean?>(null) }
    var browserBusy by remember { mutableStateOf(false) }
    var browserNote by remember { mutableStateOf<String?>(null) }
    var setupStatus by remember { mutableStateOf<com.ubuntumobile.display.SetupStatus?>(null) }
    var gamepadShown by remember { mutableStateOf(false) }

    // Linux's sound, played on the phone. The player waits for the stream
    // by itself; the server is started from the container (linx-audio.sh)
    // in a process of its own that keeps it alive.
    val audio = remember { com.ubuntumobile.display.AudioBridge() }
    DisposableEffect(Unit) { onDispose { audio.stop() } }
    fun startAudio() {
        if (!java.io.File(vm.paths.root, "usr/bin/pulseaudio").exists()) return
        audio.start()
        vm.runtime.startPersistent(
            listOf("/bin/bash", "-c", "/usr/local/lib/linx/linx-audio.sh && exec sleep infinity"),
        )
    }

    // What onboarding chose (OnboardingScreen) lives in the container, in
    // /etc/linx/setup.conf; linx-setup.sh copies it to setup.done once
    // everything in it is installed.
    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
    val setupConf = remember { java.io.File(vm.paths.root, "etc/linx/setup.conf") }
    val setupDoneFile = remember { java.io.File(vm.paths.root, "etc/linx/setup.done") }
    fun setupDone(): Boolean = try {
        setupDoneFile.isFile && setupDoneFile.readText() == setupConf.readText()
    } catch (_: Exception) {
        false
    }

    suspend fun runSetup() {
        if (browserBusy) return
        browserBusy = true
        browserNote = "Setting up your apps…"
        vm.paths.appendLog("setting up: ${setupConf.readText().lines().filter { it.startsWith("LINX_") }}")
        val log = java.io.File(vm.paths.sharedTmpDir, "linx-setup.log")
        val progressFile = java.io.File(vm.paths.sharedTmpDir, "linx-setup-progress")
        log.delete()
        progressFile.delete()
        setupStatus = com.ubuntumobile.display.SetupStatus(0, 1, "Starting", "", 0f)
        // The script's "STEP n/total what" lines, and apt's and Flatpak's
        // progress, tell how far it got.
        val progress = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(1000)
                val status = try {
                    com.ubuntumobile.display.SetupStatus.parse(
                        log.readLines(),
                        if (progressFile.isFile) progressFile.readLines().takeLast(20) else emptyList(),
                    )
                } catch (_: Exception) {
                    null
                } ?: continue
                setupStatus = status
                browserNote = "Setting up (${status.step} of ${status.total}): ${status.title}…"
            }
        }
        val out = withContext(Dispatchers.IO) {
            vm.runtime.shell("/usr/local/lib/linx/linx-setup.sh > /tmp/linx-setup.log 2>&1; tail -n 1 /tmp/linx-setup.log", 3600).output
        }
        progress.cancel()
        browserInstalled = firefoxBinary.exists()
        val done = setupDone()
        // Why it failed, as linx-setup.sh put it ("Flathub: error: ...").
        val why = try {
            java.io.File(vm.paths.sharedTmpDir, "linx-setup-error").readText().trim()
        } catch (_: Exception) {
            ""
        }
        browserNote = if (done) null else "Setup did not finish" + if (why.isNotEmpty()) ": $why" else ""
        setupStatus = if (done) null else (setupStatus ?: com.ubuntumobile.display.SetupStatus(0, 1, "", "", 0f))
            .copy(failed = true, detail = why)
        vm.refreshInstalledApps()
        vm.paths.appendLog("setup: ${out.trim().lines().lastOrNull()}")
        withContext(Dispatchers.IO) {
            vm.runtime.shell("/usr/local/lib/linx/linx-look.sh", 120)
            startAudio()
        }
        browserBusy = false
    }
    LaunchedEffect(session.connected, onboarded) {
        if (!session.connected || !onboarded) return@LaunchedEffect
        browserInstalled = firefoxBinary.exists()
        withContext(Dispatchers.IO) {
            startAudio()
            // The wallpapers are drawn by the app (once per design change).
            if (com.ubuntumobile.ui.Wallpaper.ensure(vm.paths.root) && setupDone()) {
                vm.runtime.shell("/usr/local/lib/linx/linx-look.sh", 120)
            }
        }
        if (!setupDone()) runSetup()
    }

    /** Keep what onboarding chose, and set Linux up to match. */
    fun applyChoices(choices: com.ubuntumobile.ui.SetupChoices) {
        desktopSize = choices.desktopSize
        prefs.edit()
            .putBoolean("onboarded", true)
            .putString("desktop_size", choices.desktopSize.name)
            .apply()
        scope.launch(Dispatchers.IO) {
            setupConf.parentFile?.mkdirs()
            setupConf.writeText(choices.toConf())
            com.ubuntumobile.ui.Wallpaper.ensure(vm.paths.root)
            withContext(Dispatchers.Main) {
                onboarded = true
                // Already up: the look changes at once, the rest installs.
                if (session.connected && !setupDone()) runSetup()
            }
            vm.runtime.shell("/usr/local/lib/linx/linx-look.sh", 120)
        }
    }

    // The clipboard, shared both ways. Android only lets the app in the
    // foreground read the clipboard, so the phone's is checked whenever the
    // app comes back and whenever it changes while the app is open - and
    // only read when its timestamp says it changed, because reading shows a
    // "pasted from your clipboard" notice on newer Android.
    val clipboard = remember { ContextCompat.getSystemService(context, android.content.ClipboardManager::class.java) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(clipboard, lifecycleOwner) {
        var lastText: String? = null
        var lastStamp = -1L
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        session.onClipboard = { text ->
            main.post {
                lastText = text
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Ubuntu", text))
                lastStamp = clipboard?.primaryClipDescription?.timestamp ?: -1L
            }
        }
        fun pushToLinux() {
            val cm = clipboard ?: return
            val stamp = cm.primaryClipDescription?.timestamp ?: return
            if (stamp == lastStamp) return
            lastStamp = stamp
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            if (text != lastText && session.connected) {
                lastText = text
                session.sendClipboard(text)
            }
        }
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener { pushToLinux() }
        clipboard?.addPrimaryClipChangedListener(listener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) main.postDelayed({ pushToLinux() }, 500)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            session.onClipboard = null
            clipboard?.removePrimaryClipChangedListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // The phone's own folders (Downloads, Documents, Pictures...) appear in
    // the Linux home folder once the app may read them (linx-folders.sh).
    fun storageGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    var storageAllowed by remember { mutableStateOf(storageGranted()) }
    val storageRequest = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        storageAllowed = storageGranted()
        if (storageAllowed) {
            scope.launch(Dispatchers.IO) { vm.runtime.shell("/usr/local/lib/linx/linx-folders.sh", 60) }
        }
    }
    fun askForStorage() {
        storageRequest.launch(
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ),
        )
    }
    LaunchedEffect(session.connected, onboarded) {
        // Asked once, the first time the desktop is up; Settings can ask again.
        if (session.connected && onboarded && !storageAllowed && !prefs.getBoolean("storage_asked", false)) {
            prefs.edit().putBoolean("storage_asked", true).apply()
            askForStorage()
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

    // The desktop gets the whole screen: the status and navigation bars
    // hide until swiped in (unless switched off in Settings).
    val activityWindow = (context as? android.app.Activity)?.window
    val rootView = LocalView.current
    LaunchedEffect(fullScreen, screen, docked, state.isInstalled) {
        val window = activityWindow ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, rootView)
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (state.isInstalled && fullScreen && screen == Screen.DISPLAY && !docked) {
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
                // XSETTINGS; Qt ones only read it when they start. Firefox
                // starts through /usr/local/bin/firefox, which sets up what
                // it needs under proot.
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

    // Onboarding: the first time, or again from Settings. Ubuntu unpacks
    // behind it.
    val failed = state.installPhase == RootfsInstaller.Progress.Phase.FAILED
    if ((!onboarded && !failed) || screen == Screen.ONBOARDING) {
        val again = onboarded
        com.ubuntumobile.ui.OnboardingScreen(
            initial = remember {
                val saved = try {
                    setupConf.takeIf { it.isFile }?.readText()
                } catch (_: Exception) {
                    null
                }
                saved?.let { com.ubuntumobile.ui.SetupChoices.fromConf(it, desktopSize) }
                    ?: com.ubuntumobile.ui.SetupChoices(desktopSize = desktopSize)
            },
            unpackFraction = if (state.isInstalled) null else state.installFraction,
            unpackDetail = state.installDetail,
            onStarted = { if (!state.isInstalled) vm.installUbuntu() },
            onFinish = {
                applyChoices(it)
                screen = Screen.DISPLAY
            },
            again = again,
            onCancel = { screen = Screen.SETTINGS },
        )
        return
    }

    // Unpacking Ubuntu failed (or onboarding is done and it is still going).
    if (!state.isInstalled) {
        SetupScreen(
            state = state,
            onInstall = { vm.installUbuntu() },
        )
        return
    }

    when (screen) {
        // Shown above, before the rest.
        Screen.ONBOARDING -> {}

        Screen.DESKTOP -> {
            // Everything the desktop's menu has, read afresh each time, so
            // what was just installed is there; until the first read, the
            // apps that come with the image.
            LaunchedEffect(Unit) { vm.refreshInstalledApps() }
            val apps = remember(state.installedApps) {
                if (state.installedApps.isEmpty()) AppCatalog.builtin
                else state.installedApps + AppCatalog.device
            }
            UbuntuShell(
                state = state,
                apps = apps,
                iconRoot = vm.paths.root,
                wide = wide,
                onLaunchApp = { entry ->
                    when (entry.id) {
                        "store" -> screen = Screen.STORE
                        "gamepad" -> screen = Screen.GAMEPAD
                        "console" -> openConsole()
                        "browser" -> if (firefoxBinary.exists()) {
                            launchOnDisplay(listOf("firefox"))
                            screen = Screen.DISPLAY
                        } else {
                            screen = Screen.STORE
                        }
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
                runGuest = { cmd, timeout -> vm.guestCommand(cmd, timeout) },
                openApp = { command ->
                    launchOnDisplay(command)
                    screen = Screen.DISPLAY
                },
                onClose = { screen = Screen.DISPLAY },
                modifier = Modifier.systemBarsPadding(),
            )
        }

        Screen.SETTINGS -> {
            SettingsScreen(
                desktopSize = desktopSize,
                onDesktopSize = {
                    desktopSize = it
                    prefs.edit().putString("desktop_size", it.name).apply()
                },
                fullScreen = fullScreen,
                onFullScreen = {
                    fullScreen = it
                    prefs.edit().putBoolean("full_screen", it).apply()
                },
                browserInstalled = browserInstalled ?: firefoxBinary.exists(),
                browserBusy = browserBusy,
                browserNote = browserNote,
                onInstallBrowser = { scope.launch { runSetup() } },
                onSetUpAgain = { screen = Screen.ONBOARDING },
                storageAllowed = storageAllowed,
                onAllowStorage = { askForStorage() },
                versionName = BuildConfig.VERSION_NAME,
                ubuntuVersion = state.guestInfo.prettyName,
                onClose = { screen = Screen.DISPLAY },
                modifier = Modifier.systemBarsPadding(),
            )
        }

        Screen.GAMEPAD -> {
            // The gamepad is an overlay on the desktop now.
            LaunchedEffect(Unit) {
                gamepadShown = true
                screen = Screen.DISPLAY
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
                onOpenGamepad = { gamepadShown = true },
                onShowKeyboard = { showSoftKeyboard(view, context) },
                onReconnect = { session.retry() },
                onUseMonitor = if (externalDisplay != null && !docked) ({ preferMonitor = true }) else null,
                onOpenBrowser = {
                    if (firefoxBinary.exists()) {
                        launchOnDisplay(listOf("firefox"))
                    } else if (!browserBusy) {
                        scope.launch { runSetup() }
                    }
                },
                onOpenSettings = { screen = Screen.SETTINGS },
                browserNote = browserNote,
                setup = setupStatus,
                onRetrySetup = { if (!browserBusy) scope.launch { runSetup() } },
                onShareSetupLog = { shareSetupLog(context, vm.paths.sharedTmpDir) },
            )
            Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                    com.ubuntumobile.display.SetupBanner(
                        setupStatus,
                        actions.onRetrySetup,
                        insets.align(Alignment.TopCenter),
                        actions.onShareSetupLog,
                    )
                    // Docked, the game is on the monitor and the phone is
                    // the controller: the pad takes the whole screen.
                    if (gamepadShown) {
                        GamepadOverlay(vm, landscape, overGame = false, onClose = { gamepadShown = false }, modifier = insets)
                    }
                } else {
                    DisplayScreen(
                        session = session,
                        actions = actions,
                        onCanvasSize = { canvasSize = it },
                        modifier = insets,
                        overlay = {
                            if (gamepadShown) {
                                GamepadOverlay(vm, landscape, overGame = true, onClose = { gamepadShown = false })
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The on-screen gamepad and its link into the container. Over the game
 * ([overGame]) it is see-through and only its controls take touches; on a
 * docked phone it is a controller filling the screen. A slider sets how
 * see-through it is, and is remembered.
 */
@Composable
private fun GamepadOverlay(
    vm: UbuntuViewModel,
    landscape: Boolean,
    overGame: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var opacity by remember { mutableStateOf(prefs.getFloat("gamepad_opacity", 0.55f)) }
    val pad = remember { GamepadState() }
    val bridge = remember { GamepadBridge(vm.runtime, vm.paths) }
    var bridgeActive by remember { mutableStateOf(true) }

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

    Box(
        modifier
            .fillMaxSize()
            .then(if (overGame) Modifier else Modifier.background(Color(0xFF1C1B1A))),
    ) {
        VirtualGamepad(
            state = pad,
            landscape = landscape,
            opacity = if (overGame) opacity else 1f,
            modifier = Modifier.systemBarsPadding(),
        )
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (overGame) {
                Icon(Icons.Default.Opacity, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 8.dp).size(18.dp))
                androidx.compose.material3.Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { prefs.edit().putFloat("gamepad_opacity", opacity).apply() },
                    valueRange = 0.15f..1f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.8f),
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier.width(120.dp).height(36.dp),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Hide the gamepad", tint = Color.White)
            }
        }
        if (!bridgeActive) {
            Text(
                "On-screen controls only — this device does not expose /dev/uinput, " +
                    "so Linux games cannot see a virtual pad.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Send setup's logs to another app - mail, a chat - as text, so a failure
 * on someone's phone can be looked at: the last lines of each step's log.
 */
private fun shareSetupLog(context: android.content.Context, tmp: java.io.File) {
    val text = buildString {
        append("Mobuntu ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE}, ")
        append("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}\n")
        val logs = listOf("linx-setup.log", "linx-setup-error") +
            (tmp.list()?.filter { it.startsWith("linx-setup-") && it.endsWith(".log") }?.sorted() ?: emptyList())
        for (name in logs.distinct()) {
            val f = java.io.File(tmp, name)
            if (!f.isFile) continue
            append("\n== $name ==\n")
            append(f.readLines().takeLast(40).joinToString("\n"))
            append("\n")
        }
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Mobuntu setup log")
        .putExtra(android.content.Intent.EXTRA_TEXT, text.takeLast(90_000))
    context.startActivity(android.content.Intent.createChooser(send, "Share the setup log"))
}

/** The size and UI scale the X screen should have. */
private data class DisplayTarget(val width: Int, val height: Int, val scale: Int)

/**
 * The X screen for a drawing area of [size] pixels.
 *
 * The desktop is laid out in pixels - the panel is 30 pixels tall, the
 * fonts are 10 points - so at the native resolution of a modern handset
 * (1440x3120) it would be far too small to tap. On a phone the X screen is
 * therefore a normal desktop screen with the phone's shape (short side 900
 * pixels by default, see DesktopSize) with everything doubled (scale 2),
 * and the phone scales it to fit.
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
    /** The phone's short side in X pixels (the Settings' desktop size). */
    phoneShortSide: Int,
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
    val fit = if (short > phoneShortSide) phoneShortSide / short else 1f
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
