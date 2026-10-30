package com.calum.traininglog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    @Test fun newerVersionsAreDetected() {
        assertTrue(UpdateManager.isNewer("v0.1.2", "0.1.1"))
        assertTrue(UpdateManager.isNewer("v0.2.0", "0.1.9"))
        assertTrue(UpdateManager.isNewer("v1.0", "0.9.9"))
        assertTrue(UpdateManager.isNewer("v0.10.0", "0.9.0"))
    }
    @Test fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateManager.isNewer("v0.1.1", "0.1.1"))
        assertFalse(UpdateManager.isNewer("v0.1.0", "0.1.1"))
        assertFalse(UpdateManager.isNewer("v0.1", "0.1.0"))
    }

    @Test fun releaseBodyIsParsedAndMissingBodyIsAllowed() {
        val withBody = UpdateManager.parseRelease(
            """{"tag_name":"v0.6.0","body":"- **New** thing\n* Another `one`\nplain line\n- ","assets":[{"name":"a.apk","size":5,"browser_download_url":"u"}]}""",
        )
        assertEquals("v0.6.0", withBody.tag)
        assertEquals(listOf("New thing", "Another one"), UpdateManager.whatsNewBullets(withBody.body.orEmpty()))
        val without = UpdateManager.parseRelease("""{"tag_name":"v0.6.0","assets":[]}""")
        assertNull(without.body)
        assertEquals(emptyList(), UpdateManager.whatsNewBullets(without.body.orEmpty()))
    }
}
