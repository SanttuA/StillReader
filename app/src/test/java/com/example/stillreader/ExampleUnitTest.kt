package io.github.santtua.stillreader

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherIdentityTest {
    @Test
    fun launcherUsesThePublicApplicationNamespace() {
        assertEquals("io.github.santtua.stillreader.MainActivity", MainActivity::class.java.name)
    }
}
