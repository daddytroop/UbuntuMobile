package com.ubuntumobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTest {
    private fun gib(x: Double) = (x * 1024 * 1024 * 1024).toLong()

    @Test
    fun `memory is named as the box names it`() {
        assertEquals(4, Performance.boxSize(gib(3.6)))
        assertEquals(4, Performance.boxSize(gib(3.83)))
        assertEquals(6, Performance.boxSize(gib(5.5)))
        assertEquals(8, Performance.boxSize(gib(7.4)))
        assertEquals(12, Performance.boxSize(gib(11.2)))
        assertEquals(16, Performance.boxSize(gib(15.0)))
    }

    @Test
    fun `phones get the level for their memory`() {
        assertEquals(Performance.BASIC, Performance.forMemory(3))
        assertEquals(Performance.BASIC, Performance.forMemory(4))
        assertEquals(Performance.BALANCED, Performance.forMemory(6))
        assertEquals(Performance.BALANCED, Performance.forMemory(8))
        assertEquals(Performance.FAST, Performance.forMemory(12))
    }
}
