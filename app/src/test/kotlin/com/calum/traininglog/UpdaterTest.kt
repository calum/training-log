package com.calum.traininglog

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
