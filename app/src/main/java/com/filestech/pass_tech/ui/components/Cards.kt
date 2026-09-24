package com.filestech.pass_tech.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.filestech.pass_tech.ui.theme.LocalDarkTheme

/**
 * A card as 2.7.1 draws it: flat on the surface colour with a thin border in the dark theme
 * (`cardTheme` of `_darkTheme`), Material's elevated card in the light one. Corners 12 in both.
 */
@Composable
fun PtCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    if (LocalDarkTheme.current) {
        val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        val border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
        if (onClick == null) {
            Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
        } else {
            Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = content)
        }
    } else if (onClick == null) {
        ElevatedCard(modifier = modifier, shape = shape, content = content)
    } else {
        ElevatedCard(onClick = onClick, modifier = modifier, shape = shape, content = content)
    }
}
