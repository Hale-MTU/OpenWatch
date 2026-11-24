package tech.mmarca.openwatch.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * One item of a bulleted list. The bullet and the text are two pieces of one row, so the row
 * mirrors in a right-to-left language and the bullet stays at the start of the line; a
 * "• name" string puts it where the text's own direction says.
 */
@Composable
fun BulletRow(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall) {
    Row(modifier = modifier) {
        Text(text = "•", style = style)
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(text = text, style = style)
    }
}
