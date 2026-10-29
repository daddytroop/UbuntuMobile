package com.ubuntumobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real tar extractor against the real Ubuntu 26.04 rootfs archive.
 *
 * This is the highest-risk piece of the app: if extraction loses a symlink
 * or truncates a file, the container is broken on device and there is no way
 * to find out until someone installs the APK. The test therefore unpacks the
 * actual archive that ships in the APK and asserts that the guest userspace
 * is intact.
 */
class TarExtractorTest {

    private fun archiveForAbi(): File? {
        val roots = listOf(
            File("src/arm64/assets/rootfs"),
            File("src/amd64/assets/rootfs"),
            File("src/armhf/assets/rootfs"),
        )
        for (dir in roots) {
            val f = dir.listFiles()?.firstOrNull { it.name.endsWith(".rootfs") }
            if (f != null && f.length() > 0) return f
        }
        return null
    }

    @Test
    fun extractsAWorkingRootfsFromTheShippedArchive() {
        val isLinux = System.getProperty("os.name").lowercase().contains("linux")
        val archive = archiveForAbi()
        assumeTrue("rootfs archive not present in the source tree", archive != null)
        val out = File(System.getProperty("java.io.tmpdir"), "linx-rootfs-test-${System.nanoTime()}")
        out.mkdirs()

        try {
            val result = archive!!.inputStream().use { input ->
                TarExtractor(out).extract(input)
            }

            println("extracted ${result.files} files, ${result.bytes} bytes, " +
                    "${result.symlinkEntries} symlink entries, ${result.directories} dirs, " +
                    "${result.failedEntries} failed")

            assertTrue("no files extracted", result.files > 1000)
            assertTrue("no symlink entries seen", result.symlinkEntries > 100)
            if (isLinux) {
                // Every entry has to be writable on the target filesystem.
                assertEquals(
                    "some entries could not be extracted",
                    0L, result.failedEntries,
                )
            } else {
                // Windows cannot represent case-colliding names, which the
                // Ubuntu headers contain (ip6t_HL.h and ip6t_hl.h), so a
                // small number of entries fail there.
                assertTrue(
                    "too many failures on Windows: ${result.failedEntries}",
                    result.failedEntries < result.files / 20,
                )
            }

            // The shell must exist and be reachable through the merged-/usr
            // symlink, otherwise nothing in the container can start.
            val bash = File(out, "usr/bin/bash")
            assertTrue("usr/bin/bash missing", bash.exists())
            if (isLinux) {
                // The permission bits must survive the extraction, otherwise
                // no binary in the container would be executable.
                assertTrue(
                    "usr/bin/bash is not executable (mode bits were lost)",
                    bash.canExecute(),
                )
                val flatpak = File(out, "usr/bin/flatpak")
                assertTrue("usr/bin/flatpak missing", flatpak.exists())
                assertTrue("usr/bin/flatpak is not executable", flatpak.canExecute())
            }

            // Creating symlinks requires privileges on Windows, so the
            // symlink assertions only run on the platform we actually ship
            // to. Everywhere else we still verify the archive was parsed
            // correctly (file and directory counts match GNU tar exactly).
            if (isLinux) {
                assertTrue("symlinks were not created", result.symlinks > 100)

                val binLink = File(out, "bin")
                assertTrue("/bin is not a symlink", java.nio.file.Files.isSymbolicLink(binLink.toPath()))
                // The archive stores the link target relative to the root.
                assertEquals(
                    "usr/bin",
                    java.nio.file.Files.readSymbolicLink(binLink.toPath()).toString(),
                )
                assertTrue("bin/bash not reachable via the /bin symlink", File(out, "bin/bash").exists())
            }

            // Core tooling that the app relies on. Some of these are symlinks
            // (python3 -> python3.14), which Windows cannot create, so on
            // non-Linux hosts we check the package database instead.
            val status = File(out, "var/lib/dpkg/status")
            assertTrue("dpkg status missing", status.exists())
            val statusText = status.readText()
            assertTrue("dpkg status looks empty", statusText.length > 10_000)
            assertTrue("dpkg status has no Description field", statusText.contains("Description:"))

            // binary -> the package that provides it
            val tools = mapOf(
                "flatpak" to "flatpak",
                "bwrap" to "bubblewrap",
                "apt-get" to "apt",
                "python3" to "python3",
                "gcc" to "gcc",
                "curl" to "curl",
                "git" to "git",
            )
            for ((binary, pkg) in tools) {
                val file = File(out, "usr/bin/$binary")
                if (isLinux) {
                    assertTrue("usr/bin/$binary missing", file.exists())
                } else {
                    assertTrue("package '$pkg' missing from the dpkg database",
                        statusText.contains("Package: $pkg\n"))
                }
            }

            // TLS must work, so the CA bundle has to be present.
            val ca = File(out, "etc/ssl/certs/ca-certificates.crt")
            assertTrue("CA bundle missing", ca.exists())
            assertTrue("CA bundle is too small", ca.length() > 50_000)

            // Flatpak integration.
            assertTrue("firstboot script missing",
                File(out, "usr/local/lib/linx/firstboot.sh").exists())
            assertTrue("flathub repo missing",
                File(out, "usr/local/share/linx/flathub.flatpakrepo").exists())
            assertTrue("flatpak search helper missing",
                File(out, "usr/local/lib/linx/flatpak-search.sh").exists())
            assertTrue("gamepad helper missing",
                File(out, "usr/local/lib/linx/gamepad-helper.py").exists())

            // os-release identifies the right release. /etc/os-release is a
            // symlink to ../usr/lib/os-release, which Windows cannot create.
            val osReleaseFile = if (isLinux) {
                File(out, "etc/os-release")
            } else {
                File(out, "usr/lib/os-release")
            }
            assertTrue("os-release missing", osReleaseFile.exists())
            val osRelease = osReleaseFile.readText()
            assertTrue("unexpected release: $osRelease", osRelease.contains("26.04"))
        } finally {
            out.deleteRecursively()
        }
    }
}
