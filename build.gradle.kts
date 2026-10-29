plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// This project lives inside OneDrive, which conflicts with Gradle's
// file-locking during incremental builds. Redirect all build output to a
// local-disk directory (override with -Pubuntumobile.buildDir=...).
val buildRoot: String = (findProperty("ubuntumobile.buildDir") as String?)
    ?: "${System.getProperty("user.home")}/AppData/Local/UbuntuMobile/build"

allprojects {
    layout.buildDirectory = file("$buildRoot/${project.name}")
}
