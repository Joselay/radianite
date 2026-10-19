package app.radianite.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `reads as time left, never as a clock time`() {
        assertEquals("14h 09m", countdown(14 * hour + 9 * minute + 24_000))
        assertEquals("3d 14h", countdown(3 * 24 * hour + 14 * hour + 5 * minute))
        assertEquals("45m", countdown(45 * minute + 59_000))
    }

    @Test
    fun `the last minute shows as under a minute, and time up as nothing left`() {
        assertEquals("<1m", countdown(30_000))
        assertEquals("0m", countdown(0))
        assertEquals("0m", countdown(-5_000))
    }
}
