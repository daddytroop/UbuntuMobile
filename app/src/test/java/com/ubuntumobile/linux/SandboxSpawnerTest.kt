package com.ubuntumobile.linux

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * linx-bwrap names paths as the container sees them; the proot the app
 * starts for a Flatpak app needs the phone's own.
 */
class SandboxSpawnerTest {
    private fun host(path: String) = SandboxSpawner.hostPath(
        path,
        root = "/data/user/0/app/files/linux",
        sharedTmp = "/data/user/0/app/files/tmp",
        shm = "/data/user/0/app/files/shm",
        sdcard = "/storage/emulated/0/Android/data/app/files/shared",
    )

    @Test
    fun containerFilesLiveInTheRootFileSystem() {
        assertEquals("/data/user/0/app/files/linux/var/lib/flatpak/app", host("/var/lib/flatpak/app"))
        assertEquals("/data/user/0/app/files/linux/root/.var", host("/root/.var"))
    }

    @Test
    fun theContainersOwnBindMountsAreFollowed() {
        assertEquals("/data/user/0/app/files/tmp/linx-bwrap-1/root", host("/tmp/linx-bwrap-1/root"))
        assertEquals("/data/user/0/app/files/tmp", host("/tmp"))
        assertEquals("/data/user/0/app/files/shm/x", host("/dev/shm/x"))
        assertEquals("/dev/dri", host("/dev/dri"))
        assertEquals("/proc", host("/proc"))
        assertEquals("/sdcard/Download", host("/sdcard/Download"))
    }

    @Test
    fun namesThatOnlyStartLikeABindMountAreNotOne() {
        assertEquals("/data/user/0/app/files/linux/tmpfiles", host("/tmpfiles"))
        assertEquals("/data/user/0/app/files/linux/devices", host("/devices"))
    }
}
