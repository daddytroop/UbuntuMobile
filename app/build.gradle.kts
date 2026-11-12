plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ubuntumobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ubuntumobile"
        minSdk = 26
        // Target API 28 on purpose.
        //
        // Android 10 introduced the W^X restriction: apps that target API 29
        // or higher may not execute files from their own home directory.
        // proot lives in files/bin and has to be executed, so the app has to
        // stay below API 29. This is exactly what Termux does, and it is why
        // the app cannot go to the Play Store as it stands.
        targetSdk = 28
        versionCode = 172
        versionName = "0.17.2"
        resourceConfigurations += listOf("en")
    }

    // The Ubuntu rootfs is architecture specific and large (~330 MB), so it
    // ships inside a per-ABI flavor instead of the shared assets folder.
    // Each generated APK therefore carries exactly one rootfs.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            buildConfigField("String", "ROOTFS_ASSET", "\"rootfs/ubuntu-26.04-lite-arm64.rootfs\"")
            ndk { abiFilters.clear(); abiFilters.add("arm64-v8a") }
        }
        create("armhf") {
            dimension = "abi"
            buildConfigField("String", "ROOTFS_ASSET", "\"rootfs/ubuntu-26.04-lite-armhf.rootfs\"")
            ndk { abiFilters.clear(); abiFilters.add("armeabi-v7a") }
        }
        create("amd64") {
            dimension = "abi"
            buildConfigField("String", "ROOTFS_ASSET", "\"rootfs/ubuntu-26.04-lite-amd64.rootfs\"")
            ndk { abiFilters.clear(); abiFilters.add("x86_64") }
        }
    }

    signingConfigs {
        create("release") {
            // Debug keystore fallback so `assembleRelease` works out of the box.
            // Replace with a real keystore for publishing.
            val ks = rootProject.file("keystore/release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = (rootProject.findProperty("LINX_STORE_PASSWORD") as String?) ?: "ubuntu"
                keyAlias = (rootProject.findProperty("LINX_KEY_ALIAS") as String?) ?: "ubuntu"
                keyPassword = (rootProject.findProperty("LINX_KEY_PASSWORD") as String?) ?: "ubuntu"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Rootfs is huge; keep it uncompressed-store friendly and let Play split by ABI.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.version",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    androidResources {
        // The rootfs is a gzip archive already. Keeping it in noCompress
        // means aapt stores it verbatim (no recompression, faster install).
        // NOTE: do not use a ".gz" suffix - aapt2 would decompress the asset
        // and strip the extension from the packaged name.
        noCompress += listOf("so", "rootfs")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.testLogging {
                    showStandardStreams = true
                    events("passed", "failed", "skipped")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }

    lint {
        // This app is sideloaded rather than published to the Play Store, and
        // it deliberately targets API 28: Android's W^X restriction stops
        // apps that target API 29+ from executing files in their own home
        // directory, which is exactly where proot has to live.
        disable += "ExpiredTargetSdkVersion"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                cppFlags += "-std=c++17"
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// The display and desktop scripts also ship inside the APK, and the app
// copies them into the container every time it starts the display. A fix to
// one of them then reaches the phone with the next APK, without rebuilding
// the 400 MB rootfs or reinstalling Ubuntu.
abstract class BundleLinxScripts : DefaultTask() {
    @get:InputFiles
    abstract val scripts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("linx")
        dir.deleteRecursively()
        dir.mkdirs()
        // A Windows checkout may have given them CRLF line endings, which
        // bash inside the container cannot run.
        scripts.files.forEach { dir.resolve(it.name).writeText(it.readText().replace("\r\n", "\n")) }
    }
}

val linxScripts = tasks.register<BundleLinxScripts>("bundleLinxScripts") {
    scripts.from(
        listOf(
            "display-start.sh", "desktop-start.sh", "display-scale.sh", "tint2rc", "gamepad-helper.py",
            "nosandbox-bwrap", "linx-windows.py", "xfce4-panel.xml", "apt-install.sh",
            "install-firefox.sh", "firefox", "linx-audio.sh", "linx-look.sh",
            "make-xfwm4-theme.py", "linx-folders.sh", "linx-bwrap", "linx-flatpak.sh", "linx-setup.sh",
            "linx-apps.py", "linx-panel.sh", "linx-notify.py", "linx-snapshot.sh", "linx-locale.sh", "linx-phone", "linx-updates.sh", "linx-mic.sh", "linx-clip.py", "linx-status", "linx-print.sh", "linx-pkg", "linx-shell.sh", "99linx-tidy",
        )
            .map { rootProject.file("tools/$it") }
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(linxScripts, BundleLinxScripts::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Draws the SVG icons of Linux apps in the launcher.
    implementation(libs.androidsvg)

    testImplementation(libs.junit)
}
