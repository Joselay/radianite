package app.radianite.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class StoreSyncTest {
    private fun utc(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun `next run is a few minutes after the coming rotation`() {
        assertEquals(utc("2026-10-20T00:05:00Z"), StoreSync.nextRunAt(utc("2026-10-19T09:25:00Z")))
    }

    @Test
    fun `a run that ends late still pins the next one to the rotation, not a day after it ended`() {
        assertEquals(utc("2026-10-20T00:05:00Z"), StoreSync.nextRunAt(utc("2026-10-19T00:05:42Z")))
        assertEquals(utc("2026-10-20T00:05:00Z"), StoreSync.nextRunAt(utc("2026-10-19T11:00:00Z")))
    }
}
