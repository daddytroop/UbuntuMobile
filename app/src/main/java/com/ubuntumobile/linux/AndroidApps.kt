package com.ubuntumobile.linux

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import java.io.File

/**
 * The phone's apps in the Linux desktop's menu, under "Phone apps".
 *
 * Each launcher app gets a menu entry and its icon in the container; the
 * entry runs `linx-phone app <package>`, which asks the app (over the
 * notification socket) to start it - next to the desktop on the monitor
 * when docked. The entries are written again only when the phone's apps
 * change.
 */
object AndroidApps {
    private const val TAG = "AndroidApps"
    private const val PREFIX = "android-"
    private const val ICONS = "usr/local/share/linx/android"
    private const val ENTRIES = "usr/local/share/applications"

    /** What was written last: one "package version" line per app. */
    private const val STAMP = "usr/local/share/linx/android/.apps"

    /**
     * [linuxNames]: the Linux apps' names, so a phone app with the same
     * name ("Files") is told apart as "Files (phone)".
     */
    fun sync(context: Context, root: File, linuxNames: Set<String> = emptySet()) {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
        val stamp = launchers.map { info ->
            val pkg = info.activityInfo.packageName
            val updated = try {
                pm.getPackageInfo(pkg, 0).lastUpdateTime
            } catch (_: Exception) {
                0L
            }
            "$pkg $updated"
        }.sorted().joinToString("\n") + "\n" + linuxNames.sorted().joinToString(",")
        val stampFile = File(root, STAMP)
        if (stampFile.isFile && stampFile.readText() == stamp) return

        val icons = File(root, ICONS).apply { mkdirs() }
        val entries = File(root, ENTRIES).apply { mkdirs() }
        val keep = mutableSetOf<String>()
        for (info in launchers) {
            val pkg = info.activityInfo.packageName
            val name = entryName(pkg)
            keep += name
            try {
                val label = phoneLabel(info.loadLabel(pm).toString().replace('\n', ' ').trim().ifBlank { pkg }, linuxNames)
                val icon = File(icons, "$pkg.png")
                icon.outputStream().use { out ->
                    bitmap(info.loadIcon(pm)).compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                File(entries, name).writeText(desktopEntry(label, pkg, "/$ICONS/$pkg.png"))
            } catch (e: Exception) {
                Log.w(TAG, "skipped $pkg", e)
            }
        }
        // Apps that were uninstalled since.
        entries.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".desktop") && f.name !in keep }
            ?.forEach { it.delete() }
        writeMenu(root)
        stampFile.writeText(stamp)
        Log.i(TAG, "${launchers.size} phone apps in the Linux menu")
    }

    fun entryName(pkg: String) = "$PREFIX$pkg.desktop"

    internal fun phoneLabel(label: String, linuxNames: Set<String>): String =
        if (linuxNames.any { it.equals(label, ignoreCase = true) }) "$label (phone)" else label

    internal fun desktopEntry(label: String, pkg: String, icon: String) = buildString {
        append("[Desktop Entry]\n")
        append("Type=Application\n")
        append("Name=").append(label).append('\n')
        append("Comment=Android app\n")
        append("Exec=/usr/local/bin/linx-phone app ").append(pkg).append('\n')
        append("Icon=").append(icon).append('\n')
        append("Categories=X-Android;\n")
        append("X-Linx-Android=true\n")
    }

    /** A "Phone apps" folder in the menu for the X-Android category. */
    private fun writeMenu(root: File) {
        File(root, "etc/xdg/menus/applications-merged").mkdirs()
        File(root, "etc/xdg/menus/applications-merged/linx-android.menu").writeText(
            """
            <!DOCTYPE Menu PUBLIC "-//freedesktop//DTD Menu 1.0//EN"
              "http://www.freedesktop.org/standards/menu-spec/1.0/menu.dtd">
            <Menu>
              <Name>Xfce</Name>
              <Menu>
                <Name>Phone apps</Name>
                <Directory>linx-android.directory</Directory>
                <Include><Category>X-Android</Category></Include>
              </Menu>
            </Menu>
            """.trimIndent() + "\n",
        )
        File(root, "usr/local/share/desktop-directories").mkdirs()
        File(root, "usr/local/share/desktop-directories/linx-android.directory").writeText(
            "[Desktop Entry]\nType=Directory\nName=Phone apps\nIcon=phone\n",
        )
    }

    private fun bitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        return bmp
    }
}
