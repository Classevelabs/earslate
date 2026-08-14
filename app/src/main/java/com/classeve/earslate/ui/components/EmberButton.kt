package com.classeve.earslate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionFastMs
import com.classeve.earslate.ui.theme.PreciseEasing
import com.classeve.earslate.ui.theme.rememberReducedMotion

/**
 * The action row used across earslate, matched to the same component on
 * classeve.com so the app and the site read as one product.
 *
 * The shape of it is deliberate. A full-width pill filled with solid ember and
 * a shouted uppercase label is the default an interface arrives at when nobody
 * decides anything: the colour does all the work, every action looks equally
 * loud, and the result reads as generic no matter how good the palette is.
 * Here the ember is a 3dp rule down the leading edge instead of a fill, the
 * surface stays close to the canvas, and the label is set in sentence case at
 * body size. Restraint is what reads as expensive — the accent earns attention
 * precisely because it is the only saturated thing in the row.
 *
 * `primary = true` gets the brighter rule, a slightly lifted surface and a
 * hairline border, so it is unmistakably the main action without becoming a
 * block of colour. Everything else recedes.
 *
 * The trailing arrow is not decoration: it marks the row as something that
 * takes you somewhere, and it slides 4dp on press, which is the whole of the
 * feedback budget. Press also lifts the surface one step rather than scaling
 * the control — scaling a full-width row looks like a bug on a large screen.
 *
 * Accessibility is unchanged from the Material button this replaces: an
 * explicit `Role.Button`, a 56dp minimum target that still grows with font
 * scale, a visible focus ring, and motion that collapses to nothing when the
 * system asks for reduced motion.
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
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = pressed || hovered

    val colors = EarslateTheme.colors
    val shape = EarslateTheme.shapes.md

    val targetSurface = when {
        !enabled -> colors.elev1
        pressed -> colors.elev3
        hovered -> colors.elev2
        primary -> colors.elev2
        else -> colors.elev1
    }
    val surface by animateColorAsState(
        targetValue = targetSurface,
        animationSpec = tween(MotionFastMs, easing = PreciseEasing),
        label = "ember-row-surface",
    )

    val targetRule = when {
        !enabled -> colors.borderSubtle
        primary && active -> colors.emberHover
        primary -> colors.ember
        active -> colors.ember
        else -> colors.emberLine
    }
    val rule by animateColorAsState(
        targetValue = targetRule,
        animationSpec = tween(MotionFastMs, easing = PreciseEasing),
        label = "ember-row-rule",
    )

    val labelColor = when {
        !enabled -> colors.textDisabled
        primary -> colors.cream
        else -> colors.textPrimary
    }
    val targetArrow = when {
        !enabled -> colors.textDisabled
        active -> colors.ember
        else -> colors.textSecondary
    }
    val arrowColor by animateColorAsState(
        targetValue = targetArrow,
        animationSpec = tween(MotionFastMs, easing = PreciseEasing),
        label = "ember-row-arrow",
    )
    val arrowShift by animateDpAsState(
        targetValue = if (pressed && !reducedMotion) 4.dp else 0.dp,
        animationSpec = tween(MotionFastMs, easing = PreciseEasing),
        label = "ember-row-arrow-shift",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(shape)
            .background(surface)
            .then(
                if (primary) Modifier.border(1.dp, colors.borderSubtle, shape) else Modifier,
            )
            .then(
                if (focused) Modifier.border(2.dp, colors.focusRing, shape) else Modifier,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        // The leading rule. Ember appears exactly once in the row, which is why
        // it still means something.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .defaultMinSize(minHeight = 56.dp)
                .clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp))
                .background(rule),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = label,
                style = EarslateTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
                color = labelColor,
            )
            Text(
                text = "→",
                style = EarslateTheme.textStyles.body,
                color = arrowColor,
                modifier = Modifier.padding(start = 12.dp).height(20.dp).then(
                    Modifier.padding(start = arrowShift),
                ),
            )
        }
    }
}
