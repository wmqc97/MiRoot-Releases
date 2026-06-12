package com.wmqc.miroot.rear.balance

import android.hardware.SensorManager
import kotlin.math.atan2

internal object BalanceTiltMath {
    fun deltaToDegrees(delta: Float): Float {
        return Math.toDegrees(atan2(delta.toDouble(), SensorManager.GRAVITY_EARTH.toDouble())).toFloat()
    }
}
