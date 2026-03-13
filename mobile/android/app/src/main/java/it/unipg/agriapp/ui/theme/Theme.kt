package it.unipg.agriapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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

@Composable
fun AgriAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgriLightColorScheme,
        content = content,
    )
}
