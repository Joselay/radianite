package app.radianite.ui.store

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test
    fun `formats as hh mm ss and clamps negatives`() {
        assertEquals("05:03:09", formatDuration((5 * 3600 + 3 * 60 + 9) * 1000L + 999))
        assertEquals("00:00:00", formatDuration(-5_000))
    }
}
