package com.ubuntumobile

import android.app.Activity
import android.content.Intent
import com.ubuntumobile.ui.HomeScreen

/**
 * The phone's home screen, when Mobuntu is the home app.
 *
 * Android keeps the home app in a task of its own; were MainActivity the
 * home activity, Home would start a second copy of it there, with a second
 * Linux display. So this stands in: it draws nothing, and each time Home
 * brings it to the front it brings the one MainActivity forward instead,
 * on its app grid.
 */
class HomeActivity : Activity() {
    override fun onResume() {
        super.onResume()
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(HomeScreen.ACTION_SHOW_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
