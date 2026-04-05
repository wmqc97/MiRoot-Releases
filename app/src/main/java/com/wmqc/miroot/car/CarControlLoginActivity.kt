package com.wmqc.miroot.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 吉利车控账号登录（界面风格 [Miuix](https://github.com/compose-miuix-ui/miuix)）。
 */
class CarControlLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                // Material3 输入框读取 MaterialTheme；仅 MiuixTheme 时默认浅色 scheme，深色模式下文字会与深色卡片同色。
                CarControlLoginScreen(
                    onSuccess = {
                        startActivity(android.content.Intent(this, CarControlSettingsActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun CarControlLoginScreen(onSuccess: () -> Unit) {
    val ctx = LocalContext.current
    val darkUi = isSystemInDarkTheme()
    val materialScheme = remember(darkUi) {
        val p = Color(ContextCompat.getColor(ctx, R.color.miuix_primary))
        if (darkUi) {
            m3DarkColorScheme(primary = p, secondary = p, tertiary = p)
        } else {
            m3LightColorScheme(primary = p, secondary = p, tertiary = p)
        }
    }

    val brandPrimary = Color(ContextCompat.getColor(ctx, R.color.miuix_primary))
    MaterialTheme(colorScheme = materialScheme) {
        CarControlLoginScreenContent(
            onSuccess = onSuccess,
            brandPrimary = brandPrimary,
        )
    }
}

@Composable
private fun CarControlLoginScreenContent(
    onSuccess: () -> Unit,
    brandPrimary: Color,
) {
    val ctx = LocalContext.current
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val cardColors = CardColors(
        color = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface)),
        contentColor = onPagePrimary,
    )
    val scheme = MaterialTheme.colorScheme
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        focusedLabelColor = scheme.onSurfaceVariant,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        cursorColor = scheme.primary,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outline,
        disabledTextColor = scheme.onSurface.copy(alpha = 0.38f),
        disabledLabelColor = scheme.onSurfaceVariant.copy(alpha = 0.38f),
        disabledBorderColor = scheme.outline.copy(alpha = 0.38f),
        focusedPlaceholderColor = scheme.onSurfaceVariant,
        unfocusedPlaceholderColor = scheme.onSurfaceVariant,
    )

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val p = ctx.getSharedPreferences("LoginPrefs", android.content.Context.MODE_PRIVATE)
        username = p.getString("username", "").orEmpty()
        password = p.getString("password", "").orEmpty()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg),
    ) {
        CompositionLocalProvider(LocalContentColor provides onPagePrimary) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = scrollPad, vertical = scrollPad),
                verticalArrangement = Arrangement.spacedBy(scrollPad),
            ) {
                Text(
                    text = stringResource(R.string.car_control_login_title),
                    fontSize = pageTitleTextUnit(),
                    fontWeight = FontWeight.SemiBold,
                    color = onPagePrimary,
                )
                Text(
                    text = stringResource(R.string.car_control_login_subtitle),
                    style = MiuixTheme.textStyles.body2,
                    color = onPageSecondary,
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(scrollPad),
                    colors = cardColors,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { androidx.compose.material3.Text(stringResource(R.string.car_control_username)) },
                            singleLine = true,
                            enabled = !loading,
                            colors = fieldColors,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { androidx.compose.material3.Text(stringResource(R.string.car_control_password)) },
                            singleLine = true,
                            enabled = !loading,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = fieldColors,
                        )
                        if (status.isNotEmpty()) {
                            Text(
                                text = status,
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (statusError) Color(0xFFFF5722) else Color(0xFF4CAF50),
                            )
                        }
                        Button(
                            onClick = {
                                val u = username.trim()
                                val pw = password.trim()
                                if (u.isEmpty()) {
                                    status = ctx.getString(R.string.car_control_need_username)
                                    statusError = true
                                    return@Button
                                }
                                if (pw.isEmpty()) {
                                    status = ctx.getString(R.string.car_control_need_password)
                                    statusError = true
                                    return@Button
                                }
                                loading = true
                                status = ctx.getString(R.string.car_control_logging_in)
                                statusError = false
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        LoginService.login(ctx, u, pw)
                                    }
                                    loading = false
                                    if (result.success) {
                                        val prefs = ctx.getSharedPreferences("LoginPrefs", android.content.Context.MODE_PRIVATE)
                                        prefs.edit().apply {
                                            putString("username", u)
                                            putString("password", pw)
                                            putString("accessToken", result.accessToken)
                                            putString("userId", result.userId)
                                            putString("refreshToken", result.refreshToken)
                                            val now = System.currentTimeMillis() / 1000
                                            putLong("expireTime", now + result.expiresIn)
                                            putInt("expiresIn", result.expiresIn)
                                            apply()
                                        }
                                        onSuccess()
                                    } else {
                                        status = result.message ?: ctx.getString(R.string.car_control_login_failed)
                                        statusError = true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(stringResource(R.string.car_control_login_button))
                        }
                    }
                }
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = brandPrimary)
            }
        }
    }
}

/** 与 `TextAppearance.MiRoot.PageTitle` / 音乐页主标题一致。 */
@Composable
private fun pageTitleTextUnit(): TextUnit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val d = LocalDensity.current
    val px = ctx.resources.getDimension(R.dimen.mi_page_title_text_size)
    return (px / (d.density * d.fontScale)).sp
}
