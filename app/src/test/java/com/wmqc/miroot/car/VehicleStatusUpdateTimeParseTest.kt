package com.wmqc.miroot.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStatusUpdateTimeParseTest {

    @Test
    fun parseUpdateTimeMillis_supportsPlainMillisString() {
        assertEquals(1779777178880L, VehicleStatusService.parseUpdateTimeMillis("1779777178880"))
    }

    @Test
    fun parseUpdateTimeMillis_supportsSecondsString() {
        assertEquals(1698212139000L, VehicleStatusService.parseUpdateTimeMillis("1698212139"))
    }

    @Test
    fun parseUpdateTimeMillis_supportsScientificNotation() {
        val parsed = VehicleStatusService.parseUpdateTimeMillis("1.779777178880E12")
        assertEquals(1779777178880L, parsed)
    }

    @Test
    fun parseUpdateTimeMillis_supportsDecimalMillisString() {
        assertEquals(1779777178880L, VehicleStatusService.parseUpdateTimeMillis("1779777178880.0"))
    }

    @Test
    fun resolveVehicleUpdateMillis_usesUpdateDateTimeWhenRawBroken() {
        val status = VehicleStatusService.VehicleStatusInfo().apply {
            updateTime = "bad"
            updateDateTime = "2026-05-26 14:32:58"
        }
        val millis = VehicleStatusService.resolveVehicleUpdateMillis(status)
        assertTrue(millis > 0L)
    }

    @Test
    fun resolveVehicleUpdateMillis_rejectsUnknown() {
        val status = VehicleStatusService.VehicleStatusInfo().apply {
            updateTime = "未知"
            updateDateTime = "未知"
        }
        assertEquals(-1L, VehicleStatusService.resolveVehicleUpdateMillis(status))
    }
}
