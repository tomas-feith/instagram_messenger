package com.instachat.app.ui

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.instachat.app.notify.Blocker

/**
 * The only screen: the WebView, and above it - only while a shared post or reel is open -
 * a bar that goes back to the chat. Above that, when something stops message
 * notifications, a banner saying what.
 *
 * The bar exists because a shared post is the one place the site's own navigation could
 * lead somewhere blocked, and a blocked tap does nothing. Without a way out that is
 * visibly the app's, a user who hid the nav bar would have only the system back gesture.
 */
@Composable
fun ChatScreen(
    webView: WebView,
    viewingItem: Boolean,
    onBackToChat: () -> Unit,
    banner: Blocker?,
    onFixBanner: (Blocker) -> Unit,
    onDismissBanner: (Blocker) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // safeDrawing includes the keyboard, which is what keeps the message box above
            // it: with edge-to-edge there is no adjustResize to do that for us.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (banner != null) {
            NotificationBanner(banner, onFixBanner, onDismissBanner)
            HorizontalDivider()
        }
        if (viewingItem) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackToChat) {
                    Text("←  Back to chat")
                }
            }
            HorizontalDivider()
        }
        AndroidView(
            factory = { webView },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        )
    }
}

/**
 * Says what is stopping message notifications and offers the one fix. Dismissable, since
 * either can be a deliberate choice; it returns only if the problem clears and comes back.
 */
@Composable
private fun NotificationBanner(
    blocker: Blocker,
    onFix: (Blocker) -> Unit,
    onDismiss: (Blocker) -> Unit,
) {
    val (text, action) =
        when (blocker) {
            Blocker.NOTIFICATIONS_OFF -> {
                "Notifications are off for Insta Chat." to "Turn on"
            }

            Blocker.NOT_EXEMPT -> {
                "No message notifications while Power saving mode is on." to
                    "Allow"
            }
        }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { onDismiss(blocker) }) { Text("Dismiss") }
        TextButton(onClick = { onFix(blocker) }) { Text(action) }
    }
}
