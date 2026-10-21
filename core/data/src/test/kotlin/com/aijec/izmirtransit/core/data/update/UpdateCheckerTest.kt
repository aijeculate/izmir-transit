package com.aijec.izmirtransit.core.data.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun `compares versions numerically`() {
        assertTrue(UpdateChecker.isNewer("1.0.2", "1.0.1"))
        assertTrue(UpdateChecker.isNewer("1.0.10", "1.0.9"))
        assertTrue(UpdateChecker.isNewer("1.1", "1.0.5"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.0.1", "1.0.1"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateChecker.isNewer("1.0.1-beta", "1.0.1"))
    }
}
