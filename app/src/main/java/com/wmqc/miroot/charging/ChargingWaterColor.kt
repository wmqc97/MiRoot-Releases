package com.wmqc.miroot.charging



import kotlin.math.min



/** 充电动画水体配色：由基色推导渐变/涟漪色。 */

object ChargingWaterColor {



    /** 默认深绿色水体（功能页色盘可改）。 */
    const val DEFAULT_ARGB: Int = 0xFF1B6B42.toInt()



    /** 100% 为实色水体；低于 100% 为半透明水体。 */

    @JvmStatic

    fun isSolidOpacity(opacityPercent: Int): Boolean =

        opacityPercent.coerceIn(ChargingAnimationPrefs.MIN_WATER_OPACITY_PERCENT, 100) >= 100



    /** @return [topArgb, bottomArgb]；[opacityPercent] 10–100 控制透明度与实色/半透明基线。 */

    @JvmStatic

    @JvmOverloads

    fun gradientColors(baseArgb: Int, opacityPercent: Int = 100): IntArray {

        val p = opacityPercent.coerceIn(ChargingAnimationPrefs.MIN_WATER_OPACITY_PERCENT, 100)

        val scale = p / 100f

        val solid = isSolidOpacity(p)

        val r = (baseArgb shr 16) and 0xFF

        val g = (baseArgb shr 8) and 0xFF

        val b = baseArgb and 0xFF

        val topA = ((if (solid) 0xF0 else 0x66) * scale).toInt().coerceIn(0, 255)

        val botA = ((if (solid) 0xFF else 0x99) * scale).toInt().coerceIn(0, 255)

        val top = (topA shl 24) or (min(255, r + 28) shl 16) or (min(255, g + 28) shl 8) or min(255, b + 16)

        val bot = (botA shl 24) or (r * 2 / 3 shl 16) or (g * 2 / 3 shl 8) or (b * 2 / 3)

        return intArrayOf(top, bot)

    }



    @JvmStatic

    @JvmOverloads

    fun depthOverlayArgb(baseArgb: Int, opacityPercent: Int = 100): Int {

        if (!isSolidOpacity(opacityPercent)) return 0

        val scale = opacityPercent.coerceIn(0, 100) / 100f

        val r = (baseArgb shr 16) and 0xFF

        val g = (baseArgb shr 8) and 0xFF

        val b = baseArgb and 0xFF

        val a = (0x28 * scale).toInt().coerceIn(0, 255)

        return (a shl 24) or (r * 2 / 5 shl 16) or (g * 2 / 5 shl 8) or (b * 2 / 5)

    }



    /** 液面下缘带填充色：比 [depthOverlayArgb] 更深，贴近真实水面暗影。 */

    @JvmStatic

    @JvmOverloads

    fun surfaceBandFillArgb(baseArgb: Int, opacityPercent: Int = 100): Int {

        val p = opacityPercent.coerceIn(ChargingAnimationPrefs.MIN_WATER_OPACITY_PERCENT, 100)

        val scale = p / 100f

        val r = ((baseArgb shr 16) and 0xFF) * 22 / 100

        val g = ((baseArgb shr 8) and 0xFF) * 22 / 100

        val b = (baseArgb and 0xFF) * 18 / 100

        val a = ((if (isSolidOpacity(p)) 0x52 else 0x40) * scale).toInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b

    }



    /** 液面顶缘描边：浅白色波纹线。 */

    @JvmStatic

    @JvmOverloads

    fun surfaceMeniscusStrokeArgb(baseArgb: Int, opacityPercent: Int = 100): Int {

        val p = opacityPercent.coerceIn(ChargingAnimationPrefs.MIN_WATER_OPACITY_PERCENT, 100)

        val scale = p / 100f

        val a = ((if (isSolidOpacity(p)) 0xD8 else 0xA8) * scale).toInt().coerceIn(0, 255)

        return (a shl 24) or (0xF2 shl 16) or (0xF6 shl 8) or 0xFA

    }



    /** 液面顶缘高光：更亮的浅白细线。 */

    @JvmStatic

    @JvmOverloads

    fun surfaceSheenStrokeArgb(baseArgb: Int, opacityPercent: Int = 100): Int {

        val p = opacityPercent.coerceIn(ChargingAnimationPrefs.MIN_WATER_OPACITY_PERCENT, 100)

        val scale = p / 100f

        val a = ((if (isSolidOpacity(p)) 0x88 else 0x60) * scale).toInt().coerceIn(0, 255)

        return (a shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF

    }



    @JvmStatic

    fun rippleRgb(baseArgb: Int): IntArray {

        val r = (baseArgb shr 16) and 0xFF

        val g = (baseArgb shr 8) and 0xFF

        val b = baseArgb and 0xFF

        return intArrayOf(r, g, b)

    }

}

