package com.instachat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Follows the system, because the page does: the WebView reports the app theme to the
 * site as prefers-color-scheme, so the little native chrome here matches whichever of
 * Instagram's two looks is showing. The backgrounds are Instagram's own.
 */
private val Light =
    lightColorScheme(
        primary = Color(0xFF0095F6),
        background = Color.White,
        surface = Color.White,
    )

private val Dark =
    darkColorScheme(
        primary = Color(0xFF0095F6),
        background = Color.Black,
        surface = Color.Black,
    )

@Composable
fun InstaChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
