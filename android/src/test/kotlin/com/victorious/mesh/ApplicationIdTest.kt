package com.victorious.mesh

import com.tailscale.ipn.fleet.FleetProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdTest {
  @Test
  fun applicationIdIsVictoriousMeshForEveryBuildType() {
    assertEquals("com.victorious.mesh", BuildConfig.APPLICATION_ID)
  }

  @Test
  fun fleetBridgeActionIsFrozen() {
    assertEquals("com.victorious.mesh.action.BRIDGE", FleetProtocol.ACTION_BRIDGE)
  }
}
