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
import com.ubuntumobile.display.DisplayScreen
import com.ubuntumobile.display.DisplaySession
import com.ubuntumobile.display.androidKeyToX11
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
            val keysym = androidKeyToX11(event.keyCode)
            if (keysym != null) {
                session.key(keysym, event.action == android.view.KeyEvent.ACTION_DOWN)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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

    // The in-container X display is not the phone's native resolution.
    // The desktop is laid out in pixels - the panel is 30 pixels tall, the
    // fonts are 10 points - so at a native resolution of a modern handset
    // (1440x3120) the whole desktop would be far too small to tap. The
    // display is therefore sized to a normal desktop screen with the
    // phone's aspect ratio, and the phone scales it up.
    val geometry = remember {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val short = minOf(width, height)
        val scale = if (short > 720) 720f / short else 1f
        // Round to even numbers: X servers are happier with them.
        val gw = ((width * scale).toInt() / 2) * 2
        val gh = ((height * scale).toInt() / 2) * 2
        "${gw}x$gh"
    }

    // Hidden text field used to raise the Android soft keyboard so it can
    // send key events to the VNC session.
    val keyboardFocus = remember { FocusRequester() }

    // Terminal state
    val terminal = remember { AnsiTerminal() }
    var terminalLines by remember { mutableStateOf(listOf<String>()) }
    var pty by remember { mutableStateOf<Pty?>(null) }
    // The X server lives inside the container, and proot kills everything it
    // started when it exits - so it needs a proot process that stays alive.
    var displayProcess by remember { mutableStateOf<Process?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
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
        withContext(Dispatchers.IO) {
            displayProcess?.destroyForcibly()
            vm.paths.appendLog("starting the Linux display ($geometry)")
            displayProcess = vm.runtime.startPersistent(
                listOf(
                    "/bin/bash", "-c",
                    "export LINX_DISPLAY=$DISPLAY_NUM LINX_RFB_PORT=$DISPLAY_PORT; " +
                        "export LINX_GEOMETRY=$geometry LINX_DEPTH=24; " +
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

    // Launch a guest app in a PTY
    fun launchInTerminal(entry: AppEntry) {
        screen = Screen.TERMINAL
        val runtime = vm.runtime
        val paths = vm.paths
        if (pty?.isRunning == true) return

        val session = Pty(runtime, paths)
        try {
            session.startShell(rows = 40, cols = 120) { data, len ->
                terminal.feed(data, len)
            }
            pty = session
            // Send the app command once the shell is up.
            Thread {
                Thread.sleep(600)
                session.write(entry.command.joinToString(" ") { quote(it) } + "\n")
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            terminal.feed("!! ${e.message}\n".toByteArray(), 0)
        }
    }

    /** Launch a Linux GUI app as a window in the running XFCE desktop. */
    fun launchOnDisplay(command: List<String>) {
        // Set DISPLAY to the in-container X server. Because proot shares
        // the network namespace and /tmp, a separate proot process can
        // still connect to the X11 unix socket started by the display
        // process.
        vm.runtime.startPersistent(
            listOf("/bin/bash", "-lc", command.joinToString(" ") { quote(it) }),
            mapOf("DISPLAY" to ":$DISPLAY_NUM"),
        )
    }

    /** Show the Android soft keyboard so key events reach the VNC session. */
    fun showSoftKeyboard(view: android.view.View, ctx: android.content.Context) {
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
                        "terminal" -> launchInTerminal(entry)
                        else -> launchOnDisplay(entry.command)
                    }
                },
                onOpenStore = { screen = Screen.STORE },
                onRefresh = { vm.refreshSystemInfo() },
                onBack = { screen = Screen.DISPLAY },
                modifier = Modifier.systemBarsPadding(),
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
            val session = remember { DisplaySession("127.0.0.1", DISPLAY_PORT) }
            DisposableEffect(Unit) {
                keyTarget(session)
                onDispose {
                    keyTarget(null)
                    session.stop()
                }
            }
            // An invisible text field that captures key events for the VNC
            // session. It is shown behind the display so it never blocks
            // touch, but it can receive focus when the keyboard button is
            // pressed.
            val view = LocalView.current
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.text.BasicTextField(
                    value = "",
                    onValueChange = {},
                    modifier = Modifier
                        .focusRequester(keyboardFocus)
                        .size(1.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
                )
                DisplayScreen(
                    session = session,
                    landscape = landscape,
                    onClose = { screen = Screen.DESKTOP },
                    onShowLauncher = { screen = Screen.DESKTOP },
                    onOpenTerminal = { launchOnDisplay(listOf("/usr/bin/xfce4-terminal")) },
                    onOpenFiles = { launchOnDisplay(listOf("/usr/bin/thunar")) },
                    onOpenStore = { screen = Screen.STORE },
                    onOpenGamepad = { screen = Screen.GAMEPAD },
                    onShowKeyboard = { showSoftKeyboard(view, context) },
                    modifier = Modifier.systemBarsPadding(),
                )
            }
        }
    }
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
                        "Set up Ubuntu with Flatpak and Flathub preinstalled.\n" +
                            "This downloads ~330 MB and unpacks it on first launch.",
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
