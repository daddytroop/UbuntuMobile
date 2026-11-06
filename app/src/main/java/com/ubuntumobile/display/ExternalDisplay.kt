package com.ubuntumobile.display

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A monitor the phone can show the desktop on, as Compose state: the first
 * display Android offers for presentations, or null.
 *
 * A USB-C dock or cable with HDMI or DisplayPort shows up as one. Android
 * mirrors the phone onto it until an app puts its own content there.
 */
@Composable
fun rememberExternalDisplay(): Display? {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(DisplayManager::class.java) }
    fun current(): Display? =
        manager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull()

    var display by remember { mutableStateOf(current()) }
    DisposableEffect(manager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { display = current() }
            override fun onDisplayRemoved(displayId: Int) { display = current() }
            override fun onDisplayChanged(displayId: Int) {
                // A new mode (resolution) keeps the same id; let the size be re-read.
                if (displayId == display?.displayId) display = current()
            }
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    return display
}

/**
 * Shows [content] full screen on [display] for as long as this is in the
 * composition.
 *
 * The content lives in a [Presentation], Android's window for a second
 * screen. Its Compose content is part of the app's composition, so it sees
 * the same state, and it gets the monitor's own density and size.
 */
@Composable
fun PresentationOn(display: Display, content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)

    DisposableEffect(display.displayId) {
        val presentation = Presentation(activity, display)
        // Created from the presentation's context, so the content is laid
        // out with the monitor's metrics rather than the phone's.
        val view = ComposeView(presentation.context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setParentCompositionContext(parent)
            setContent { currentContent() }
        }
        presentation.setContentView(view)
        presentation.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // The monitor's window never takes the focus: keys go to the
            // phone's window, which passes them on to the desktop, and a
            // focused Compose window would bring up the soft keyboard.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        try {
            presentation.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            // The monitor went away between being found and being used.
            Log.w("ExternalDisplay", "could not show on display ${display.displayId}", e)
        }
        onDispose { presentation.dismiss() }
    }
}

private fun Context.findActivity(): ComponentActivity? {
    var c: Context? = this
    while (c != null) {
        if (c is ComponentActivity) return c
        if (c is Activity) return null
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}
