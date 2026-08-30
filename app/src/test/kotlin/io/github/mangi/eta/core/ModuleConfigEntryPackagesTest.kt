package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleConfigEntryPackagesTest {
    @Test
    fun googleTargetsContainTheSupportedPixelApps() {
        assertEquals(emptySet<String>(), ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES)
        assertTrue(ModuleConfig.GOOGLE_DIALER_PACKAGE in ModuleConfig.GOOGLE_TARGET_PACKAGES)
        assertTrue(ModuleConfig.GOOGLE_MESSAGES_PACKAGE in ModuleConfig.GOOGLE_TARGET_PACKAGES)
        assertTrue(ModuleConfig.GOOGLE_CONTACTS_PACKAGE in ModuleConfig.GOOGLE_TARGET_PACKAGES)
        assertTrue(ModuleConfig.GOOGLE_CALENDAR_PACKAGE in ModuleConfig.GOOGLE_TARGET_PACKAGES)
        assertTrue(ModuleConfig.GOOGLE_KEEP_PACKAGE in ModuleConfig.GOOGLE_TARGET_PACKAGES)
    }
}
