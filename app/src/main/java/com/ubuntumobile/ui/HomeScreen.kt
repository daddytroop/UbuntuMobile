package com.ubuntumobile.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Mobuntu as the phone's home screen, for people who live on their phone.
 *
 * A disabled activity (HomeActivity in the manifest) is what offers the
 * app as a home app; turning the option on enables it and asks Android to
 * make it the default (the home role on Android 10 and newer, the home
 * settings before). Pressing Home then opens the app grid - phone apps and
 * Linux apps together - with the Linux desktop a tap away.
 */
object HomeScreen {
    /** What HomeActivity asks MainActivity for: the app grid. */
    const val ACTION_SHOW_HOME = "com.ubuntumobile.SHOW_HOME"

    private fun alias(context: Context) = ComponentName(context, "com.ubuntumobile.HomeActivity")

    /** Whether Mobuntu is the phone's default home app now. */
    fun isDefault(context: Context): Boolean {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return home?.activityInfo?.packageName == context.packageName
    }

    /** Whether [intent] is Home being pressed (by way of HomeActivity). */
    fun isHomeIntent(intent: Intent?): Boolean = intent?.action == ACTION_SHOW_HOME

    /** Offer Mobuntu as a home app and ask Android to make it the default. */
    fun ask(activity: Activity) {
        activity.packageManager.setComponentEnabledSetting(
            alias(activity), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                try {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 41)
                    return
                } catch (_: Exception) {
                }
            }
        }
        openHomeSettings(activity)
    }

    /** Stop offering Mobuntu as a home app; Android then asks for another one. */
    fun stop(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            alias(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
        )
        openHomeSettings(context)
    }

    private fun openHomeSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
