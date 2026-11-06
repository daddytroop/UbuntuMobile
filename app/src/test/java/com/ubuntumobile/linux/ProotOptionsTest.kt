package com.ubuntumobile.linux

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks the app's proot command line against the proot binary that ships
 * in the APK.
 *
 * This is the one part of the container that cannot be verified on the
 * host: the Termux build of proot is an Android binary, and it rejects any
 * option it does not know about with
 *
 *     proot error: unknown option '--'
 *
 * Upstream proot, which is what a developer builds and tests against on a
 * desktop, happily accepts a bare "--" as an end-of-options marker. That
 * difference is invisible until the app runs on a phone, where it stops the
 * container from starting at all.
 *
 * So the option names are read straight out of the shipped ELF binaries and
 * every option the app passes is checked against them.
 */
class ProotOptionsTest {

    /** The proot payloads that ship in the APK, per ABI. */
    private val payloads = listOf(
        "aarch64" to "src/main/assets/proot/aarch64/proot",
        "arm" to "src/main/assets/proot/arm/proot",
        "x86_64" to "src/main/assets/proot/x86_64/proot",
    )

    private fun payloadFiles(): List<Pair<String, File>> =
        payloads.map { (abi, path) -> abi to File(path) }.filter { it.second.isFile }

    /**
     * The printable strings of an ELF file.
     *
     * The option names are stored as plain NUL-terminated strings in the
     * binary, so they show up as ordinary strings.
     */
    private fun stringsOf(file: File): Set<String> {
        val bytes = file.readBytes()
        val out = HashSet<String>()
        val current = StringBuilder()
        for (b in bytes) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in ' '..'~') {
                current.append(c)
            } else {
                if (current.length >= 2) out.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.length >= 2) out.add(current.toString())
        return out
    }

    @Test
    fun everyOptionTheAppUsesIsSupportedByTheShippedProot() {
        val files = payloadFiles()
        assumeTrue("no proot payload found in the source tree", files.isNotEmpty())

        val args = LinuxRuntime.baseArgsFor("/data/data/com.ubuntumobile/files/linux", "/shared", "/data/data/com.ubuntumobile/files/tmp", "/data/data/com.ubuntumobile/files/shm")

        for ((abi, file) in files) {
            val strings = stringsOf(file)
            // Only the options - values such as the rootfs path are not
            // option names and must not be looked up.
            val options = args.filter { it.startsWith("-") && it.length > 1 }
            assertTrue(
                "$abi: no options found in the command line",
                options.isNotEmpty(),
            )
            for (option in options) {
                assertTrue(
                    "$abi: proot does not support the option '$option' " +
                        "(the container would fail with \"unknown option '$option'\")",
                    strings.contains(option),
                )
            }
        }
    }

    @Test
    fun theCommandLineNeverUsesABareDoubleDash() {
        // proot has no end-of-options marker. The Termux build treats a bare
        // "--" as an unknown option and refuses to start.
        val args = LinuxRuntime.baseArgsFor("/data/data/com.ubuntumobile/files/linux", "/shared", "/data/data/com.ubuntumobile/files/tmp", "/data/data/com.ubuntumobile/files/shm")
        assertTrue(
            "a bare '--' must not be passed to proot",
            args.none { it == "--" },
        )
    }

    @Test
    fun theShippedProotReallyRejectsUnknownOptions() {
        val files = payloadFiles()
        assumeTrue("no proot payload found in the source tree", files.isNotEmpty())
        // If the binary did not contain this message the check above would
        // be meaningless, so make sure it is really the strict parser.
        for ((abi, file) in files) {
            val strings = stringsOf(file)
            assertTrue(
                "$abi: the payload does not look like the strict Termux build of proot",
                strings.any { it.contains("unknown option") },
            )
        }
    }

    @Test
    fun theShippedProotSupportsTheLongFormsOfTheOptionsWeUse() {
        val files = payloadFiles()
        assumeTrue("no proot payload found in the source tree", files.isNotEmpty())

        // The long forms of the options the app uses. They are the ones
        // whose presence in the binary is easy to check, because the option
        // table stores them verbatim.
        val expected = listOf("--rootfs", "--bind", "--cwd", "--pwd")
        for ((abi, file) in files) {
            val strings = stringsOf(file)
            for (option in expected) {
                assertTrue(
                    "$abi: the payload does not contain '$option'",
                    strings.contains(option),
                )
            }
        }
    }
}
