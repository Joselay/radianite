package app.radianite.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorTest {
    @Test
    fun `parses RRGGBBAA hex`() {
        assertEquals(Color(0xD1, 0x54, 0x8D, 0x33), rgbaColor("d1548d33"))
        assertNull(rgbaColor("d1548d"))
        assertNull(rgbaColor(null))
        assertNull(rgbaColor("zzzzzzzz"))
    }
}
