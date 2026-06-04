package com.wmqc.miroot.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import top.yukonga.miuix.kmp.basic.Text
import com.wmqc.miroot.R

/** 二级页系统栏：与 [R.color.mi_page_bg] 一致，避免状态栏与 Toolbar 色差。 */
fun Activity.applyMiRootSecondarySystemBars() {
    val bg = ContextCompat.getColor(this, R.color.mi_page_bg)
    window.statusBarColor = bg
    window.navigationBarColor = bg
    val lightBars =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightBars
        isAppearanceLightNavigationBars = lightBars
    }
}

/** 主 Tab / 二级内容区水平边距（与 [R.dimen.mi_page_scroll_padding] 一致）。 */
@Composable
fun miRootPageHorizontalPadding(): Dp = dimensionResource(R.dimen.mi_page_scroll_padding)

/** 主 Tab 内容区顶部边距（状态栏由 Activity 处理后的额外留白）。 */
@Composable
fun miRootPageTopPadding(): Dp = dimensionResource(R.dimen.mi_page_content_padding_top)

/** 页主标题字号（与 [R.style.TextAppearance_MiRoot_PageTitle] 一致）。 */
@Composable
fun miRootPageTitleTextUnit(): TextUnit {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val px = ctx.resources.getDimension(R.dimen.mi_page_title_text_size)
    return with(density) { px.toSp() }
}

/** Compose 二级页顶栏：与 XML [MaterialToolbar] + [TextAppearance.MiRoot.Title] 对齐。 */
@Composable
fun MiRootSecondaryToolbar(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val barBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val onPrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(barBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.cd_navigate_up),
                tint = onPrimary,
            )
        }
        Text(
            text = title,
            fontSize = miRootPageTitleTextUnit(),
            fontWeight = FontWeight.SemiBold,
            color = onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 16.dp),
        )
    }
}
