package com.wmqc.miroot.car

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R

/**
 * 车控刷新按钮统一图标（矢量 [R.drawable.ic_car_control_refresh]），颜色随系统深浅色切换。
 * 不包含下拉刷新指示器。
 */
object CarControlRefreshIcon {

    @ColorInt
    fun tintColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.car_control_refresh_icon)

    fun loadBitmap(context: Context, sizePx: Int): Bitmap? =
        CarControlWidgetSupport.loadTintedVectorIcon(
            context,
            R.drawable.ic_car_control_refresh,
            tintColor(context),
            sizePx,
        )

    @Composable
    fun Icon(
        modifier: Modifier = Modifier,
        size: Dp = 14.dp,
        rotating: Boolean = false,
    ) {
        val ctx = LocalContext.current
        val tint = remember(ctx, isSystemInDarkTheme()) {
            Color(tintColor(ctx))
        }
        val rotation = if (rotating) {
            val transition = rememberInfiniteTransition(label = "carRefreshSpin")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                ),
                label = "carRefreshRotation",
            ).value
        } else {
            0f
        }
        Icon(
            painter = painterResource(R.drawable.ic_car_control_refresh),
            contentDescription = stringResource(R.string.car_control_vehicle_refresh),
            tint = tint,
            modifier = modifier
                .size(size)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}
