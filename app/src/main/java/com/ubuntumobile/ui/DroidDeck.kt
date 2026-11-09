package com.ubuntumobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Steam on this phone, through DroidDeck.
 *
 * Steam needs an ARM build of the client, FEX to run PC code, and games
 * drawn on the phone's GPU. Mobuntu draws its desktop without the GPU, so
 * it hands Steam to DroidDeck, a separate open-source app that has all
 * three. Mobuntu only opens it, or the page to get it from.
 */
object DroidDeck {
    const val PACKAGE = "com.droiddeck.launcher"

    /** DroidDeck has no website; its own releases page is the one place to get it. */
    const val RELEASES = "https://github.com/Droid-Deck/DroidDeck/releases/latest"

    /** Flathub's Steam apps, which DroidDeck stands in for on ARM. */
    val steamIds = setOf("com.valvesoftware.Steam", "com.valvesoftware.SteamLink")

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Open DroidDeck, or its download page when it is not installed. */
    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}
