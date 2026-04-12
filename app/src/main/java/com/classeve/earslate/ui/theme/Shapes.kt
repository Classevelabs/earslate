package com.classeve.earslate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

// ClassEve radii: sm=12 md=14 lg=28 xl=38 pill=999
val RadiusSm = 12.dp
val RadiusMd = 14.dp
val RadiusLg = 28.dp
val RadiusXl = 38.dp

val ShapeSm = RoundedCornerShape(RadiusSm)
val ShapeMd = RoundedCornerShape(RadiusMd)
val ShapeLg = RoundedCornerShape(RadiusLg)
val ShapeXl = RoundedCornerShape(RadiusXl)
val ShapePill = RoundedCornerShape(percent = 50)

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
