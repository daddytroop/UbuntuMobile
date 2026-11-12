package com.ubuntumobile.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Gives the container a working DNS resolver.
 *
 * proot shares the phone's network namespace, so the guest can reach the
 * internet directly - but the rootfs ships with an empty
 * /etc/resolv.conf, and nothing inside the container can resolve a name
 * until one exists. Flatpak needs that to reach Flathub, so the resolver
 * has to be set up before the first boot finishes.
 *
 * The phone's own DNS servers are used when they can be discovered, because
 * a carrier or a Wi-Fi network often only resolves through its own
 * resolvers. Public resolvers are the fallback.
 */
object NetworkSetup {

    private const val TAG = "NetworkSetup"

    /** Public resolvers used when the phone does not report any. */
    private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")

    /**
     * Write the resolver configuration into the rootfs.
     *
     * Returns the nameservers that were written, or an empty list if
     * nothing could be written (which is not fatal - the container still
     * works, it just cannot resolve names).
     */
    fun configureDns(context: Context, paths: LinuxPaths): List<String> {
        val servers = resolvers(deviceDnsServers(context))
        val target = File(paths.root, "etc/resolv.conf")
        val content = buildString {
            append("# Written by UbuntuMobile so the container can resolve names.\n")
            servers.forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:2\n")
        }
        return try {
            if (target.isFile && target.readText() == content) return servers
            target.parentFile?.mkdirs()
            // The image may ship /etc/resolv.conf as a symlink; writing
            // through it would touch the host's file, so replace it.
            if (target.isDirectory) {
                target.deleteRecursively()
            }
            if (!target.exists() || target.parentFile?.canonicalFile != target.canonicalFile) {
                target.delete()
            }
            target.writeText(content)
            Log.i(TAG, "resolver configured with ${servers.joinToString()}")
            servers
        } catch (e: Exception) {
            Log.w(TAG, "could not write the resolver configuration", e)
            emptyList()
        }
    }

    /**
     * What goes in resolv.conf: Linux only ever asks the first three, so at
     * most two of the phone's (those of the network in use) and always a
     * public one after them, in case the phone's do not answer the container.
     */
    internal fun resolvers(phone: List<String>): List<String> =
        (phone.distinct().take(2) + FALLBACK_DNS).distinct().take(3)

    /**
     * The IPv4 DNS servers of the network the phone is using now.
     *
     * Only that network's: a phone also keeps mobile data, and on many the
     * carrier's calling network (IMS), up while on Wi-Fi, and their
     * resolvers cannot be reached from it - listed first, they left Linux
     * with no name lookups at all. IPv6 resolvers are skipped: proot's
     * network handling is IPv4 only in practice.
     */
    private fun deviceDnsServers(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        return try {
            val network = cm.activeNetwork ?: return emptyList()
            val props: LinkProperties = cm.getLinkProperties(network) ?: return emptyList()
            props.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the device's DNS servers", e)
            emptyList()
        }
    }
}

/**
 * The bits of the container's state that have to exist before anything
 * useful can happen in it.
 */
object ContainerSetup {

    private const val TAG = "ContainerSetup"

    /**
     * Prepare the container: a resolver, a runtime directory and the
     * Flathub remote.
     *
     * Flatpak refuses to work without `XDG_RUNTIME_DIR`, and the guest
     * environment points it at `/tmp/runtime-root`, which the rootfs does
     * not ship.
     */
    fun prepare(context: Context, paths: LinuxPaths) {
        NetworkSetup.configureDns(context, paths)
        ensureRuntimeDir(paths)
        syncScripts(context, paths)
    }

    /** Where each bundled script lives inside the container. */
    private val SCRIPT_TARGETS = mapOf(
        "display-start.sh" to "usr/local/lib/linx/display-start.sh",
        "desktop-start.sh" to "usr/local/lib/linx/desktop-start.sh",
        "display-scale.sh" to "usr/local/lib/linx/display-scale.sh",
        "linx-windows.py" to "usr/local/lib/linx/linx-windows.py",
        "xfce4-panel.xml" to "usr/local/share/linx/xfce4-panel.xml",
        "apt-install.sh" to "usr/local/lib/linx/apt-install.sh",
        "install-firefox.sh" to "usr/local/lib/linx/install-firefox.sh",
        "firefox" to "usr/local/bin/firefox",
        "linx-audio.sh" to "usr/local/lib/linx/linx-audio.sh",
        "linx-look.sh" to "usr/local/lib/linx/linx-look.sh",
        "linx-folders.sh" to "usr/local/lib/linx/linx-folders.sh",
        "linx-bwrap" to "usr/local/lib/linx/linx-bwrap",
        "linx-flatpak.sh" to "usr/local/lib/linx/linx-flatpak.sh",
        "linx-setup.sh" to "usr/local/lib/linx/linx-setup.sh",
        "linx-apps.py" to "usr/local/lib/linx/linx-apps.py",
        "linx-panel.sh" to "usr/local/lib/linx/linx-panel.sh",
        "linx-notify.py" to "usr/local/lib/linx/linx-notify.py",
        "linx-snapshot.sh" to "usr/local/lib/linx/linx-snapshot.sh",
        "linx-locale.sh" to "usr/local/lib/linx/linx-locale.sh",
        "linx-phone" to "usr/local/bin/linx-phone",
        "linx-updates.sh" to "usr/local/lib/linx/linx-updates.sh",
        "linx-mic.sh" to "usr/local/lib/linx/linx-mic.sh",
        "linx-clip.py" to "usr/local/lib/linx/linx-clip.py",
        "linx-status" to "usr/local/bin/linx-status",
        "linx-print.sh" to "usr/local/lib/linx/linx-print.sh",
        "make-xfwm4-theme.py" to "usr/local/lib/linx/make-xfwm4-theme.py",
        "gamepad-helper.py" to "usr/local/lib/linx/gamepad-helper.py",
        "tint2rc" to "usr/local/share/linx/tint2rc",
        "nosandbox-bwrap" to "usr/local/lib/linx/nosandbox/bwrap",
    )

    /**
     * Copy the display and desktop scripts from the APK into the rootfs.
     *
     * The rootfs carries copies from when it was built, but the APK's are
     * newer whenever the app has been updated since Ubuntu was installed.
     * Running this before every display start keeps them in step.
     */
    @Synchronized
    fun syncScripts(context: Context, paths: LinuxPaths) {
        if (!paths.root.isDirectory) return
        for ((asset, target) in SCRIPT_TARGETS) {
            try {
                val dest = File(paths.root, target)
                dest.parentFile?.mkdirs()
                val bytes = context.assets.open("linx/$asset").use { it.readBytes() }
                if (dest.isFile && dest.readBytes().contentEquals(bytes)) continue
                val tmp = File(dest.parentFile, ".${dest.name}.new")
                tmp.writeBytes(bytes)
                android.system.Os.chmod(tmp.absolutePath, if (asset == "tint2rc" || asset.endsWith(".xml")) 0x1A4 else 0x1ED)
                if (!tmp.renameTo(dest)) {
                    dest.delete()
                    tmp.renameTo(dest)
                }
                Log.i(TAG, "updated $target from the APK")
            } catch (e: Exception) {
                Log.w(TAG, "could not update $target", e)
            }
        }
    }

    /**
     * Make sure the Flathub remote is registered.
     *
     * The image ships the remote definition - including the GPG key - so
     * this works without a network connection. It is done from the app
     * rather than only from the first-boot script, because that script can
     * fail for unrelated reasons (a slow locale archive, for instance) and
     * then the store would have nothing to talk to.
     *
     * @return true when the remote is there afterwards.
     */
    fun ensureFlathub(runtime: LinuxRuntime): Boolean {
        val remotes = runtime.shell("flatpak remotes 2>/dev/null", 90)
        if (remotes.output.lines().any { it.startsWith("flathub") }) return true

        Log.i(TAG, "the Flathub remote is missing; adding it")
        val added = runtime.shell(
            "flatpak remote-add --if-not-exists --from flathub " +
                "/usr/local/share/linx/flathub.flatpakrepo 2>&1 | tail -3",
            180,
        )
        Log.i(TAG, "remote-add: ${added.output.trim()}")
        val after = runtime.shell("flatpak remotes 2>/dev/null", 90)
        val ok = after.output.lines().any { it.startsWith("flathub") }
        if (ok) {
            runtime.shell("flatpak remote-modify --enable flathub 2>/dev/null", 60)
        }
        return ok
    }

    /** Create the guest's `XDG_RUNTIME_DIR` with the mode Flatpak wants.
     *
     * Because proot binds the shared app tmp to `/tmp`, this directory lives
     * in `paths.sharedTmpDir` on the host so it is visible to every proot
     * process (the display server, flatpak, and the apps launched from the
     * shell).
     */
    private fun ensureRuntimeDir(paths: LinuxPaths) {
        val dir = File(paths.sharedTmpDir, "runtime-root")
        try {
            if (!dir.isDirectory) dir.mkdirs()
            java.nio.file.Files.setPosixFilePermissions(
                dir.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            )
        } catch (e: Exception) {
            // The java.io helpers are a fallback: their "group/other"
            // handling can leave the owner without the execute bit.
            Log.w(TAG, "could not create the runtime directory", e)
            dir.mkdirs()
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }
    }
}
