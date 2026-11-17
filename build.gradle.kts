plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// A synced folder (OneDrive, Dropbox) conflicts with Gradle's
// file-locking during incremental builds. Redirect all build output to a
// local-disk directory (override with -Pubuntumobile.buildDir=...).
val buildRoot: String = (findProperty("ubuntumobile.buildDir") as String?)
    ?: if (System.getProperty("os.name").lowercase().contains("win")) {
        "${System.getProperty("user.home")}/AppData/Local/UbuntuMobile/build"
    } else {
        "${System.getProperty("user.home")}/.cache/UbuntuMobile/build"
    }

allprojects {
    layout.buildDirectory = file("$buildRoot/${project.name}")
}
