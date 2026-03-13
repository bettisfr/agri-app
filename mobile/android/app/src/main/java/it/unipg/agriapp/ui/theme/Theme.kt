package it.unipg.agriapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val AgriLightColorScheme = lightColorScheme(
    primary = AgriPrimary,
    onPrimary = AgriOnPrimary,
    primaryContainer = AgriPrimaryContainer,
    onPrimaryContainer = AgriOnPrimaryContainer,
    secondary = AgriSecondary,
    onSecondary = AgriOnSecondary,
    secondaryContainer = AgriSecondaryContainer,
    onSecondaryContainer = AgriOnSecondaryContainer,
    tertiary = AgriTertiary,
    onTertiary = AgriOnTertiary,
    surface = AgriSurface,
    background = AgriBackground,
    onSurface = AgriOnSurface,
    onBackground = AgriOnBackground,
)

private val AgriShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun AgriAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgriLightColorScheme,
        shapes = AgriShapes,
        content = content,
    )
}
