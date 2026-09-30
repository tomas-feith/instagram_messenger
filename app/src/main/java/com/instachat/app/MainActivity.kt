package com.instachat.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.instachat.app.notify.AppVisibility
import com.instachat.app.notify.cancelAllNotifications
import com.instachat.app.notify.notificationPermissionIsRuntime
import com.instachat.app.ui.ChatScreen
import com.instachat.app.ui.theme.InstaChatTheme
import com.instachat.app.web.ChatWebViewClient
import com.instachat.app.web.UrlPolicy
import com.instachat.app.web.chromeLikeUserAgent

/**
 * The whole app: one WebView showing instagram.com's messaging, fenced in by [UrlPolicy].
 *
 * The WebView is owned by the activity rather than created inside composition, because
 * it holds the page, the session and the SPA's state - things a recomposition must never
 * throw away.
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var client: ChatWebViewClient

    /** True while a shared post or reel is open, which puts the "back to chat" bar up. */
    private var viewingItem by mutableStateOf(false)

    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private var pendingMedia: PermissionRequest? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingFiles?.onReceiveValue(pickedUris(result.resultCode, result.data))
            pendingFiles = null
        }

    private val mediaPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingMedia?.let(::answerMediaRequest)
            pendingMedia = null
        }

    // Nothing to do with the answer: a refusal just means no notifications, which the
    // system settings page already explains.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        webView = createWebView()
        val restored = savedInstanceState?.let { webView.restoreState(it) } != null
        if (restored) {
            client.restore(
                savedInstanceState.getString(KEY_LAST_ALLOWED),
                savedInstanceState.getString(KEY_LAST_CHAT),
            )
        } else {
            webView.loadUrl(startUrl(intent))
        }

        onBackPressedDispatcher.addCallback(this) {
            if (!handleBack()) {
                // Nothing left inside the app to go back to: behave as if unhandled.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }

        if (notificationPermissionIsRuntime() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            InstaChatTheme {
                ChatScreen(
                    webView = webView,
                    viewingItem = viewingItem,
                    onBackToChat = { client.backToChat(webView) },
                )
            }
        }
    }

    // JavaScript is the site: instagram.com is a React app and renders nothing without it.
    // The risk Lint warns about is a WebView running scripts from pages it did not choose;
    // this one only ever stays on pages UrlPolicy allows.
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // chrome://inspect on debug builds, for when Instagram changes its markup.
            WebView.setWebContentsDebuggingEnabled(true)
        }

        CookieManager.getInstance().setAcceptCookie(true)

        val hideChrome = assets.open("hide_chrome.js").bufferedReader().use { it.readText() }
        client =
            ChatWebViewClient(
                hideChromeScript = hideChrome,
                onExternal = ::openExternally,
                onLocation = { url -> viewingItem = UrlPolicy.kind(url) == UrlPolicy.Kind.ITEM },
            )

        return WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = chromeLikeUserAgent(userAgentString)
                // Nothing on the device's file system is any business of a web page.
                // allowContentAccess is left on: content:// is what the photo picker
                // hands back, and attachments are part of messaging.
                allowFileAccess = false
            }
            webViewClient = client
            webChromeClient = ChatChromeClient()
        }
    }

    private inner class ChatChromeClient : WebChromeClient() {
        /** Attaching a photo or video to a message. */
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            // A chooser still open from before would never be answered; answer it empty so
            // the page is not left waiting on it.
            pendingFiles?.onReceiveValue(null)
            pendingFiles = filePathCallback
            return try {
                filePicker.launch(pickerIntent(fileChooserParams))
                true
            } catch (_: ActivityNotFoundException) {
                pendingFiles = null
                false
            }
        }

        /** The microphone for voice messages, the camera for video. */
        override fun onPermissionRequest(request: PermissionRequest) {
            // Only Instagram's own pages get the microphone or camera - not an embed, and
            // not a Facebook login page that happens to be allowed on screen.
            if (!isInstagramOrigin(request.origin)) {
                request.deny()
                return
            }
            val needed = androidPermissionsFor(request).filterNot(::isGranted)
            if (needed.isEmpty()) {
                answerMediaRequest(request)
            } else {
                pendingMedia?.deny()
                pendingMedia = request
                mediaPermissions.launch(needed.toTypedArray())
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingMedia == request) pendingMedia = null
        }
    }

    /** Grant the page whatever the app itself now holds, and nothing it does not. */
    private fun answerMediaRequest(request: PermissionRequest) {
        val granted =
            request.resources.filter { resource ->
                androidPermissionFor(resource)?.let(::isGranted) == true
            }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun androidPermissionsFor(request: PermissionRequest): List<String> =
        request.resources.mapNotNull(::androidPermissionFor).distinct()

    private fun androidPermissionFor(resource: String): String? =
        when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            else -> null
        }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * A link out of Instagram - one a friend pasted into a chat - opens in the browser.
     *
     * Only web, mail and phone links are passed on. Everything else, `intent:` URLs
     * above all, is dropped: that is how the site tries to hand over to the Instagram app,
     * which is the very app this one exists to avoid.
     */
    private fun openExternally(url: String) {
        val uri = url.toUri()
        if (uri.scheme?.lowercase() !in EXTERNAL_SCHEMES) return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE),
            )
        } catch (_: ActivityNotFoundException) {
            // No app for it. Staying put is the only sensible outcome.
        }
    }

    /**
     * @return false when there is nothing left inside the app to go back to.
     *
     * Back never steps blindly through the WebView's history, because that history can
     * hold pages the policy refused: a refusal that could not back out cleanly reloads the
     * allowed page on top, leaving the refused one behind it. Stepping onto it would be
     * refused again and reload again, so back would never get anywhere. Inside chat, back
     * only goes to another chat page, and otherwise to the inbox.
     */
    private fun handleBack(): Boolean {
        val current = webView.url ?: return false
        val history = webView.copyBackForwardList()
        val previous =
            (history.currentIndex - 1).takeIf { it >= 0 }?.let { history.getItemAtIndex(it)?.url }
        return when {
            viewingItem -> {
                client.backToChat(webView)
                true
            }

            // The inbox is home. Back from there leaves, rather than stepping back
            // through the login pages that led to it.
            isInbox(current) -> {
                false
            }

            previous != null && UrlPolicy.isChat(previous) -> {
                webView.goBack()
                true
            }

            UrlPolicy.isChat(current) -> {
                webView.loadUrl(UrlPolicy.INBOX_URL)
                true
            }

            // Login and checkpoint pages: ordinary back, so a wrong step can be undone.
            webView.canGoBack() -> {
                webView.goBack()
                true
            }

            else -> {
                false
            }
        }
    }

    private fun isInbox(url: String): Boolean = url.toUri().path?.trimEnd('/') == "/direct/inbox"

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A notification tapped while the app is already open: go to that thread.
        chatUrlFrom(intent)?.let(webView::loadUrl)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.inForeground = true
        cancelAllNotifications(this)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        // Persist the session now, not whenever the engine gets round to it: the
        // notification worker reads these cookies, possibly after this process has died.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        AppVisibility.inForeground = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putString(KEY_LAST_ALLOWED, client.lastAllowedUrl)
        outState.putString(KEY_LAST_CHAT, client.lastChatUrl)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")

        const val KEY_LAST_ALLOWED = "instachat.lastAllowedUrl"
        const val KEY_LAST_CHAT = "instachat.lastChatUrl"

        fun isInstagramOrigin(origin: Uri): Boolean {
            val host = origin.host?.lowercase() ?: return false
            return origin.scheme == "https" &&
                (host == "instagram.com" || host.endsWith(".instagram.com"))
        }

        /**
         * The file picker, built by hand rather than with FileChooserParams.createIntent.
         *
         * createIntent puts the page's accept list into the intent's single type field, so
         * the site's combined image-and-video list becomes a type no picker handles and
         * attaching a photo fails outright. EXTRA_MIME_TYPES is how a list is meant to be
         * passed. It also ignores multi-select, which the site uses to send several photos
         * at once.
         */
        fun pickerIntent(params: WebChromeClient.FileChooserParams): Intent {
            val types =
                params.acceptTypes
                    .flatMap { it.split(',') }
                    .map { it.trim() }
                    .filter { it.contains('/') }
                    .distinct()
            return Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = types.singleOrNull() ?: "*/*"
                if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
                )
            }
        }

        /**
         * What the picker returned, including several files. FileChooserParams.parseResult
         * reads only the single-file field, so a multi-select would arrive as nothing.
         */
        fun pickedUris(
            resultCode: Int,
            data: Intent?,
        ): Array<Uri>? {
            if (resultCode != android.app.Activity.RESULT_OK || data == null) return null
            val clip = data.clipData
            val uris =
                if (clip != null && clip.itemCount > 0) {
                    (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                } else {
                    listOfNotNull(data.data)
                }
            return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
        }

        /** Only chat URLs are accepted from an intent; anything else opens the inbox. */
        fun chatUrlFrom(intent: Intent?): String? = intent?.dataString?.takeIf(UrlPolicy::isChat)

        fun startUrl(intent: Intent?): String = chatUrlFrom(intent) ?: UrlPolicy.INBOX_URL
    }
}
