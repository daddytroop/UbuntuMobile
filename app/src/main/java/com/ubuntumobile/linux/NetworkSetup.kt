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
        val servers = deviceDnsServers(context).ifEmpty { FALLBACK_DNS }
        val target = File(paths.root, "etc/resolv.conf")
        val content = buildString {
            append("# Written by UbuntuMobile so the container can resolve names.\n")
            servers.forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:2\n")
        }
        return try {
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
     * The DNS servers of the phone's active network.
     *
     * IPv6 resolvers are skipped: proot's network handling is IPv4 only in
     * practice, and a resolvers line with an IPv6 address often fails
     * outright in a container.
     */
    private fun deviceDnsServers(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        return try {
            val networks = cm.allNetworks ?: emptyArray()
            val out = LinkedHashSet<String>()
            for (network in networks) {
                val props: LinkProperties? = cm.getLinkProperties(network)
                val servers = props?.dnsServers ?: continue
                for (address in servers) {
                    if (address is Inet4Address) out.add(address.hostAddress ?: continue)
                }
            }
            out.toList()
        } catch (e: Exception) {
            Log.w(TAG, "could not read the device's DNS servers", e)
            emptyList()
        }
    }
}
