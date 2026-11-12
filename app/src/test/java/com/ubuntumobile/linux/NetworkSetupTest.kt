package com.ubuntumobile.linux

import org.junit.Assert.assertEquals
import org.junit.Test

/** Linux asks only the first three resolvers, so a public one must be among them. */
class NetworkSetupTest {
    @Test
    fun aPublicResolverIsAlwaysAmongTheFirstThree() {
        assertEquals(
            listOf("192.168.1.1", "192.168.1.2", "1.1.1.1"),
            NetworkSetup.resolvers(listOf("192.168.1.1", "192.168.1.2", "10.0.0.1", "10.0.0.2")),
        )
        assertEquals(listOf("192.168.1.1", "1.1.1.1", "8.8.8.8"), NetworkSetup.resolvers(listOf("192.168.1.1")))
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), NetworkSetup.resolvers(emptyList()))
    }
}
