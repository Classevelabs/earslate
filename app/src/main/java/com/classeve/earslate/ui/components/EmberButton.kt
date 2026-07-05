package com.classeve.earslate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionFastMs
import com.classeve.earslate.ui.theme.PreciseEasing
import com.classeve.earslate.ui.theme.rememberReducedMotion

/**
 * ClassEve primary/secondary pill button with:
 *  - press-scale feedback (gated on the system "remove animations" setting),
 *  - a visible focus ring for keyboard / switch-access users,
 *  - a 52dp minimum touch target that still grows with large font scale,
 *  - proper Material button semantics for TalkBack out of the box.
 *
 * `primary = true`  → matte ember fill, onEmber text.
 * `primary = false` → bg-elev-2 fill, cream text (brand secondary).
 */
@Composable
fun EmberButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    val reducedMotion = rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(MotionFastMs, easing = PreciseEasing),
        label = "ember-button-scale",
    )

    val targetContainer = when {
        !enabled -> EarslateTheme.colors.elev2
        pressed && primary -> EarslateTheme.colors.emberPressed
        primary -> EarslateTheme.colors.ember
        else -> EarslateTheme.colors.elev2
    }
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(MotionFastMs, easing = PreciseEasing),
        label = "ember-button-bg",
    )
    val content = when {
        !enabled -> EarslateTheme.colors.textTertiary
        primary -> EarslateTheme.colors.onEmber
        else -> EarslateTheme.colors.cream
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = EarslateTheme.colors.elev2,
            disabledContentColor = EarslateTheme.colors.textTertiary,
        ),
        shape = EarslateTheme.shapes.pill,
        border = if (focused) BorderStroke(2.dp, EarslateTheme.colors.emberGlow) else null,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Text(
            text = label.uppercase(),
            style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
