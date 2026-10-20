package com.tailscale.ipn.victorgps

import com.tailscale.ipn.fleet.CallerAllowlist
import com.tailscale.ipn.fleet.CallerScope
import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsProfileStoreTest {
  private class MapStore : SecretStore {
    val m = HashMap<String, String>()

    override fun get(key: String) = m[key]

    override fun put(key: String, value: String) {
      m[key] = value
    }

    override fun remove(key: String) {
      m.remove(key)
    }
  }

  @Test
  fun defaultsAreOff() {
    val s = GpsProfileStore(MapStore())
    assertFalse(s.profile().enabled)
    assertEquals("", s.profile().latitude)
  }

  @Test
  fun plannerRejectsBadCoordsAndPackage() {
    assertFalse(GpsProfilePlanner.validLatitude("91"))
    assertFalse(GpsProfilePlanner.validLongitude("200"))
    assertFalse(GpsProfilePlanner.validTargetPackage("not a package"))
    assertTrue(GpsProfilePlanner.validLatitude("-33.9"))
    assertTrue(GpsProfilePlanner.validLongitude("18.4"))
    assertTrue(GpsProfilePlanner.validTargetPackage(""))
    assertTrue(GpsProfilePlanner.validTargetPackage("com.example.app"))
    assertFalse(
        GpsProfilePlanner.canEnable(GpsRoomProfile(enabled = true, latitude = "1", longitude = ""))
    )
  }

  @Test
  fun saveRequiresValidCoordsToEnable() {
    val backing = MapStore()
    val s = GpsProfileStore(backing)
    s.save(GpsRoomProfile(enabled = true, latitude = "99", longitude = "0"))
    assertFalse(GpsProfileStore(backing).profile().enabled)

    s.save(
        GpsRoomProfile(
            enabled = true,
            latitude = "-33.92",
            longitude = "18.42",
            targetPackage = "com.example.maps",
        )
    )
    val again = GpsProfileStore(backing).profile()
    assertTrue(again.enabled)
    assertEquals("-33.92", again.latitude)
    assertEquals("18.42", again.longitude)
    assertEquals("com.example.maps", again.targetPackage)
    assertFalse("com.example.maps" in again.toString())
  }

  @Test
  fun gpsIsNotOnFleetAllowlist() {
    assertEquals(CallerScope.NONE, CallerAllowlist.scopeForPackage(VICTOR_GPS_PACKAGE))
    assertFalse(CallerAllowlist.ENTRIES.containsKey(VICTOR_GPS_PACKAGE))
    val candidates =
        mapOf(VICTOR_GPS_PACKAGE to listOf(CallerAllowlist.normalize(VICTOR_GPS_DEBUG_CERT)))
    assertEquals(CallerScope.NONE, CallerAllowlist.scopeFor(candidates))
  }
}
