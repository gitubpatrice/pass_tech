package com.filestech.pass_tech.ui.splash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The first-launch splash, the "Files Tech signature" shared by the portfolio (reference: SMS Tech
 * 1.3.7, `ui/screens/splash/SplashScreen.kt`), with the timings and texts of Pass Tech 2.7.1
 * (`splash_screen.dart`): logo, name, tagline, then a discreet "tap to continue".
 *
 * Tap anywhere, Back, or the 5.5 s timer dismiss it, once: every exit goes through the same guard.
 */
@Composable
fun SplashScreen(onDismiss: () -> Unit) {
    val fired = remember { AtomicBoolean(false) }
    val dismissOnce = remember(onDismiss) { { if (fired.compareAndSet(false, true)) onDismiss() } }

    val logoScale = remember { Animatable(LOGO_START_SCALE) }
    val logoAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val hintAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { logoAlpha.animateTo(1f, tween(LOGO_ANIM_MS, easing = EaseOutCubic)) }
            launch { logoScale.animateTo(1f, tween(LOGO_ANIM_MS, easing = EaseOutCubic)) }
            launch {
                delay(TEXT_DELAY_MS)
                textAlpha.animateTo(1f, tween(TEXT_ANIM_MS, easing = EaseOutCubic))
            }
            launch {
                delay(HINT_DELAY_MS)
                hintAlpha.animateTo(HINT_FINAL_ALPHA, tween(HINT_ANIM_MS))
            }
            launch {
                delay(AUTO_DISMISS_MS)
                dismissOnce()
            }
        }
    }
    BackHandler { dismissOnce() }

    val logoSize = (LocalConfiguration.current.screenWidthDp * LOGO_SIZE_RATIO).coerceIn(LOGO_MIN_DP, LOGO_MAX_DP).dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.splash_skip_label),
                onClick = dismissOnce,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(
                painter = painterResource(R.drawable.pass_tech_logo),
                contentDescription = stringResource(R.string.splash_logo_content_description),
                modifier = Modifier
                    .size(logoSize)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(textAlpha.value),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .alpha(textAlpha.value),
            )
        }
        Text(
            text = stringResource(R.string.splash_skip_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(hintAlpha.value),
        )
    }
}

private const val LOGO_START_SCALE = 0.5f
private const val LOGO_ANIM_MS = 900
private const val TEXT_DELAY_MS = 700L
private const val TEXT_ANIM_MS = 800
private const val HINT_DELAY_MS = 2500L
private const val HINT_ANIM_MS = 500
private const val HINT_FINAL_ALPHA = 0.6f
private const val AUTO_DISMISS_MS = 5500L
private const val LOGO_SIZE_RATIO = 0.4f
private const val LOGO_MIN_DP = 128f
private const val LOGO_MAX_DP = 200f
