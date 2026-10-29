package com.ubuntumobile

import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /**
     * The display session that should receive key events, if one is open.
     * Set while the Linux display is in the foreground.
     */
    private var keyTarget: DisplaySession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            UbuntuTheme {
                UbuntuApp(keyTarget = { keyTarget = it })
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
    vm: UbuntuViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.DESKTOP) }

    val config = LocalConfiguration.current
    val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val wide = config.screenWidthDp >= 600

    // Terminal state
    val terminal = remember { AnsiTerminal() }
    var terminalLines by remember { mutableStateOf(listOf<String>()) }
    var pty by remember { mutableStateOf<Pty?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        terminal.setSink { terminalLines = it }
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
                        "display" -> {
                            // Bring up the X display inside the container and
                            // then attach to its RFB stream.
                            scope.launch {
                                startLinuxDisplay(vm)
                                screen = Screen.DISPLAY
                            }
                        }
                        else -> launchInTerminal(entry)
                    }
                },
                onOpenStore = { screen = Screen.STORE },
                onRefresh = { vm.refreshSystemInfo() },
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
                    screen = Screen.DESKTOP
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
                onClose = { screen = Screen.DESKTOP },
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
                    onClick = { screen = Screen.DESKTOP },
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
            DisplayScreen(
                session = session,
                landscape = landscape,
                onClose = { screen = Screen.DESKTOP },
                modifier = Modifier.systemBarsPadding(),
            )
        }
    }
}

private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Start the X display inside the Ubuntu container.
 *
 * Xvnc (TigerVNC) runs an X server and exports it over RFB in one process.
 * Because proot shares the host network namespace, the app can reach the
 * RFB port on 127.0.0.1 directly.
 */
private suspend fun startLinuxDisplay(vm: UbuntuViewModel) {
    withContext(Dispatchers.IO) {
        vm.runtime.shell(
            "export LINX_DISPLAY=$DISPLAY_NUM LINX_RFB_PORT=$DISPLAY_PORT; " +
                "export LINX_GEOMETRY=1024x600 LINX_DEPTH=24; " +
                "nohup /usr/local/lib/linx/display-start.sh >/tmp/linx-display.log 2>&1 & " +
                "sleep 8; echo DISPLAY_STARTED",
            timeoutSeconds = 90,
        )
    }
}

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
                    Text(
                        state.installDetail,
                        color = UbuntuPalette.LightOnSurface,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(UbuntuPalette.UbuntuOrange)
                            .androidClickable(onInstall)
                            .padding(horizontal = 32.dp, vertical = 12.dp),
                    ) {
                        Text("Retry", color = Color.White, fontWeight = FontWeight.SemiBold)
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
