package com.ubuntumobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the parts of the app that only fail at runtime on a device.
 *
 * A missing `android:name` on `<application>` is invisible to the compiler
 * and to the unit tests, but it makes the app die before it draws anything -
 * it installs fine and then crashes on launch. These checks catch that.
 *
 * The manifest is read from the source tree and the classes it names are
 * resolved against the compiled classpath, so nothing here needs a device.
 */
class ManifestSanityTest {

    private fun manifestFile(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }

    private fun manifestText(): String = manifestFile().readText()

    /**
     * The manifest names classes relative to the package ("." or ".Foo").
     * Turn those into fully qualified names.
     */
    private fun qualify(raw: String): String = when {
        raw.startsWith(".") -> "$PACKAGE$raw"
        raw.contains(".") -> raw
        else -> "$PACKAGE.$raw"
    }

    /** Pull the android:name of the <application> element. */
    private fun applicationClassName(): String {
        val text = manifestText()
        val appTag = text.substringAfter("<application", "").substringBefore(">", "")
        val match = Regex("""android:name\s*=\s*"([^"]+)"""").find(appTag)
        return match?.groupValues?.get(1)?.let { qualify(it) }
            ?: error("the <application> element has no android:name")
    }

    /** Pull the android:name of the <activity> that has the MAIN/LAUNCHER filter. */
    private fun launcherClassName(): String {
        val text = manifestText()
        val activity = text.substringAfter("<activity", "").substringBefore(">", "")
        val match = Regex("""android:name\s*=\s*"([^"]+)"""").find(activity)
        return match?.groupValues?.get(1)?.let { qualify(it) }
            ?: error("the <activity> element has no android:name")
    }

    @Test
    fun theApplicationClassNamedInTheManifestExists() {
        val name = applicationClassName()
        assertEquals("$PACKAGE.UbuntuApplication", name)
        // The platform instantiates this class before anything is drawn, so
        // a missing class is a launch crash.
        val loaded = try {
            Class.forName(name)
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "the manifest names $name but that class does not exist - " +
                    "the app would crash on launch",
                e,
            )
        }
        assertTrue(
            "$name must extend android.app.Application",
            android.app.Application::class.java.isAssignableFrom(loaded),
        )
    }

    @Test
    fun theLauncherActivityExists() {
        val name = launcherClassName()
        assertEquals("$PACKAGE.MainActivity", name)
        val loaded = try {
            Class.forName(name)
        } catch (e: ClassNotFoundException) {
            throw AssertionError("the launcher activity $name does not exist", e)
        }
        assertTrue(
            "$name must extend android.app.Activity",
            android.app.Activity::class.java.isAssignableFrom(loaded),
        )
    }

    @Test
    fun everyClassNamedInTheManifestExists() {
        val text = manifestText()
        val names = Regex("""android:name\s*=\s*"([^"]+)"""")
            .findAll(text)
            .map { qualify(it.groupValues[1]) }
            .filter { it.startsWith(PACKAGE) }
            .toList()
        assertTrue("no app classes found in the manifest", names.isNotEmpty())
        for (name in names) {
            try {
                Class.forName(name)
            } catch (e: ClassNotFoundException) {
                throw AssertionError("the manifest names $name but that class does not exist", e)
            }
        }
    }

    private companion object {
        const val PACKAGE = "com.ubuntumobile"
    }
}
