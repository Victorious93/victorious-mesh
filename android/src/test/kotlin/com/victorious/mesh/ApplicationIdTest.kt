package com.victorious.mesh

import com.tailscale.ipn.fleet.FleetProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationIdTest {
  @Test
  fun debugApplicationIdUsesSuffix() {
    if (BuildConfig.DEBUG) {
      assertEquals("com.victorious.mesh.debug", BuildConfig.APPLICATION_ID)
    } else {
      assertEquals("com.victorious.mesh", BuildConfig.APPLICATION_ID)
    }
  }

  @Test
  fun applicationIdKeepsVictoriousMeshBase() {
    assertEquals("com.victorious.mesh", BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
  }

  @Test
  fun fleetBridgeActionIsFrozenAndIgnoresDebugSuffix() {
    assertEquals("com.victorious.mesh.action.BRIDGE", FleetProtocol.ACTION_BRIDGE)
    assertFalse(FleetProtocol.ACTION_BRIDGE.contains(".debug"))
    assertTrue(FleetProtocol.ACTION_BRIDGE.startsWith("com.victorious.mesh."))
  }
}
