package com.ubuntumobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubuntumobile.linux.LinuxPaths
import com.ubuntumobile.linux.LinuxRuntime
import com.ubuntumobile.linux.Pty
import com.ubuntumobile.linux.RootfsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the state of the Ubuntu shell: install progress, guest info and the
 * list of discovered applications.
 */
class UbuntuViewModel(app: Application) : AndroidViewModel(app) {

    val paths = LinuxPaths(app)
    val runtime = LinuxRuntime(paths)
    private val installer = RootfsInstaller(app, paths, runtime)

    private val _state = MutableStateFlow(UbuntuState())
    val state: StateFlow<UbuntuState> = _state.asStateFlow()

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    init {
        _state.value = _state.value.copy(
            isInstalled = paths.isInstalled,
            isProotReady = paths.isProotReady,
        )
        if (paths.isInstalled) {
            // The phone may have moved to a different network since the
            // container was set up, so refresh the resolver before anything
            // tries to reach the internet.
            viewModelScope.launch(Dispatchers.IO) {
                com.ubuntumobile.linux.ContainerSetup.prepare(app, paths)
            }
            refreshSystemInfo()
        }
    }

    /** Install Ubuntu 26.04 if it is not present yet. */
    fun installUbuntu() {
        // A failed attempt must be retryable, otherwise the Retry button
        // silently does nothing.
        val phase = _state.value.installPhase
        if (phase != RootfsInstaller.Progress.Phase.IDLE &&
            phase != RootfsInstaller.Progress.Phase.FAILED
        ) {
            return
        }
        viewModelScope.launch {
            installer.install { progress ->
                _state.value = _state.value.copy(
                    installPhase = progress.phase,
                    installFraction = progress.fraction,
                    installDetail = progress.detail,
                )
            }.onSuccess {
                _state.value = _state.value.copy(
                    isInstalled = true,
                    installPhase = RootfsInstaller.Progress.Phase.DONE,
                    installDetail = "",
                )
                refreshSystemInfo()
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    installPhase = RootfsInstaller.Progress.Phase.FAILED,
                    installDetail = e.message ?: "Install failed",
                )
            }
        }
    }

    /** Remove the Ubuntu install so it can be re-provisioned. */
    fun resetUbuntu() {
        viewModelScope.launch {
            installer.uninstall()
            _state.value = UbuntuState()
        }
    }

    /** Read OS details from the guest. */
    fun refreshSystemInfo() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                // If the Flathub remote is not there yet, add it: the
                // first-boot script can fail for reasons that have nothing
                // to do with Flatpak, and the store needs the remote.
                val remotesBefore = runtime.shell("flatpak remotes 2>/dev/null | head -5", 60)
                if (remotesBefore.output.lines().none { it.startsWith("flathub") }) {
                    com.ubuntumobile.linux.ContainerSetup.ensureFlathub(runtime)
                }
                val release = runtime.shell("cat /etc/os-release 2>/dev/null | grep PRETTY_NAME | cut -d= -f2- | tr -d '\"'", 60)
                val kernel = runtime.shell("uname -sr 2>/dev/null", 60)
                val arch = runtime.shell("uname -m 2>/dev/null", 60)
                val flatpak = runtime.shell("flatpak --version 2>/dev/null", 60)
                val remotes = runtime.shell("flatpak remotes 2>/dev/null | head -5", 60)
                val disk = runtime.shell("df -h / 2>/dev/null | tail -1", 60)
                GuestInfo(
                    prettyName = release.output.trim().ifEmpty { "Ubuntu 26.04 LTS" },
                    kernel = kernel.output.trim().ifEmpty { "-" },
                    architecture = arch.output.trim().ifEmpty { "-" },
                    flatpakVersion = flatpak.output.trim().ifEmpty { "not installed" },
                    flathubRemotes = remotes.output.lines()
                        .filter { it.isNotBlank() }
                        .map { it.substringBefore('\t').trim() },
                    diskUsage = disk.output.trim().ifEmpty { "-" },
                )
            }
            _state.value = _state.value.copy(guestInfo = info)
            refreshInstalledApps()
        }
    }

    /** Query installed Flatpak apps so they appear in the grid. */
    fun refreshInstalledApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val res = runtime.shell("/usr/local/lib/linx/flatpak-list.sh 2>/dev/null", 90)
                res.output.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size < 3) return@mapNotNull null
                    AppEntry(
                        id = parts[0],
                        name = parts[2].ifBlank { parts[0] },
                        icon = "\uD83D\uDCE6",
                        command = listOf("/usr/bin/flatpak", "run", parts[0]),
                        category = AppEntry.Category.FLATPAK,
                        accent = 0xFF77216F,
                    )
                }
            }
            _state.value = _state.value.copy(installedApps = apps)
        }
    }

    /** Run a one-off guest command and return its output (used by the store). */
    suspend fun guestCommand(cmd: String, timeout: Long = 120): String =
        withContext(Dispatchers.IO) { runtime.shell(cmd, timeout).output }

    fun appendTerminal(text: String) {
        _terminalOutput.value += text
    }
}

/** Snapshot of the shell state. */
data class UbuntuState(
    val isInstalled: Boolean = false,
    val isProotReady: Boolean = false,
    val installPhase: RootfsInstaller.Progress.Phase = RootfsInstaller.Progress.Phase.IDLE,
    val installFraction: Float = 0f,
    val installDetail: String = "",
    val guestInfo: GuestInfo = GuestInfo(),
    val installedApps: List<AppEntry> = emptyList(),
)

/** Details read out of the running Ubuntu system. */
data class GuestInfo(
    val prettyName: String = "Ubuntu 26.04 LTS",
    val kernel: String = "-",
    val architecture: String = "-",
    val flatpakVersion: String = "-",
    val flathubRemotes: List<String> = emptyList(),
    val diskUsage: String = "-",
)
