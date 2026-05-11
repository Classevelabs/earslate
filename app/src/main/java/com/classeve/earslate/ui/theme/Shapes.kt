package com.classeve.earslate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

// CLASSEVE v6 radii: sm=6 md=10 lg=16 xl=22. Controls use md, not capsules.
val RadiusSm = 6.dp
val RadiusMd = 10.dp
val RadiusLg = 16.dp
val RadiusXl = 22.dp

val ShapeSm = RoundedCornerShape(RadiusSm)
val ShapeMd = RoundedCornerShape(RadiusMd)
val ShapeLg = RoundedCornerShape(RadiusLg)
val ShapeXl = RoundedCornerShape(RadiusXl)
val ShapePill = ShapeMd

@Immutable
data class EarslateShapes(
    val sm: RoundedCornerShape = ShapeSm,
    val md: RoundedCornerShape = ShapeMd,
    val lg: RoundedCornerShape = ShapeLg,
    val xl: RoundedCornerShape = ShapeXl,
    val pill: RoundedCornerShape = ShapePill,
)

val DefaultEarslateShapes = EarslateShapes()

val LocalEarslateShapes = staticCompositionLocalOf { DefaultEarslateShapes }

val EarslateMaterialShapes = Shapes(
    extraSmall = ShapeSm,
    small = ShapeSm,
    medium = ShapeMd,
    large = ShapeLg,
    extraLarge = ShapeXl,
)
