package com.instachat.app.web

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Routes every main-frame navigation through [UrlPolicy].
 *
 * Instagram's site is a single-page app, so there are two ways to leave a page and both
 * are covered:
 *
 * - A real navigation (a link with a full page load, a server redirect) reaches
 *   [shouldOverrideUrlLoading] *before* it happens, and a refusal simply means it never
 *   does.
 * - A history.pushState change - most taps inside the site - never asks. It is reported
 *   through [doUpdateVisitedHistory] *after* the URL has changed and the new view has
 *   started drawing, so a refusal there has to back out of it again.
 *
 * @param hideChromeScript injected on every page load; see `assets/hide_chrome.js`.
 * @param onExternal a URL that belongs in another app.
 * @param onLocation every URL that was allowed to stay on screen.
 */
class ChatWebViewClient(
    private val hideChromeScript: String,
    private val onExternal: (String) -> Unit,
    private val onLocation: (String) -> Unit,
) : WebViewClient() {
    /** The last URL the policy let stay on screen. What a refusal returns to. */
    var lastAllowedUrl: String? = null
        private set

    /** The last chat URL, so leaving a shared post returns to the thread it came from. */
    var lastChatUrl: String? = null
        private set

    /**
     * Redirects to the inbox since a chat page last stuck. The policy sends the feed to
     * the inbox; if Instagram ever sends the inbox back to the feed - a forced interstitial,
     * say - the two would bounce off each other for ever. Past [MAX_REDIRECTS] the app
     * stops and says so instead.
     */
    private var redirectsSinceChat = 0

    /**
     * Put back what [lastAllowedUrl] and [lastChatUrl] held before the activity was
     * recreated. Without it the restored page is judged as if it came from nowhere, and a
     * shared post that was open is refused and replaced by the inbox.
     */
    fun restore(
        lastAllowed: String?,
        lastChat: String?,
    ) {
        lastAllowedUrl = lastAllowed
        lastChatUrl = lastChat
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        // Subframes are embeds and ads inside an allowed page, not somewhere the user went.
        if (!request.isForMainFrame) return false
        return route(view, request.url.toString(), committed = false)
    }

    override fun doUpdateVisitedHistory(
        view: WebView,
        url: String,
        isReload: Boolean,
    ) {
        super.doUpdateVisitedHistory(view, url, isReload)
        route(view, url, committed = true)
    }

    override fun onPageCommitVisible(
        view: WebView,
        url: String,
    ) {
        super.onPageCommitVisible(view, url)
        view.evaluateJavascript(hideChromeScript, null)
    }

    override fun onPageFinished(
        view: WebView,
        url: String,
    ) {
        super.onPageFinished(view, url)
        // Again at the end: commit-visible can fire before the document exists on some
        // loads, and the script is idempotent, so the second run costs nothing.
        view.evaluateJavascript(hideChromeScript, null)
    }

    /**
     * Apply the policy to [url]. [committed] says whether the WebView is already showing it.
     *
     * @return true when the navigation was refused or replaced.
     */
    private fun route(
        view: WebView,
        url: String,
        committed: Boolean,
    ): Boolean =
        when (val decision = UrlPolicy.decide(url, lastAllowedUrl)) {
            UrlPolicy.Decision.Allow -> {
                if (committed) accept(url)
                false
            }

            is UrlPolicy.Decision.Redirect -> {
                if (redirectsSinceChat < MAX_REDIRECTS) {
                    redirectsSinceChat++
                    view.loadUrl(decision.url)
                } else if (committed) {
                    // Whatever was refused is on screen. Replace it with an explanation
                    // rather than leave the feed up; the data: URL is itself refused, but
                    // the loop is already broken, so nothing reacts to that.
                    view.loadDataWithBaseURL(null, STUCK_PAGE, "text/html", "utf-8", null)
                }
                true
            }

            is UrlPolicy.Decision.External -> {
                onExternal(decision.url)
                if (committed) backOut(view)
                true
            }

            UrlPolicy.Decision.Block -> {
                if (committed) backOut(view)
                true
            }
        }

    private fun accept(url: String) {
        lastAllowedUrl = url
        if (UrlPolicy.isChat(url)) {
            lastChatUrl = url
            redirectsSinceChat = 0
        }
        onLocation(url)
    }

    /** Undo a navigation that already happened, returning to the last allowed URL. */
    private fun backOut(view: WebView) = returnTo(view, lastAllowedUrl ?: UrlPolicy.INBOX_URL)

    /** Where the back button goes from a shared post: the chat it was opened from. */
    fun backToChat(view: WebView) = returnTo(view, lastChatUrl ?: UrlPolicy.INBOX_URL)

    /**
     * Show [url] again.
     *
     * Going back is the smooth way - the SPA handles it as a popstate, with no reload - but
     * only when the entry behind is that URL. Otherwise it is loaded outright, which reloads
     * the page but cannot land anywhere unexpected.
     */
    private fun returnTo(
        view: WebView,
        url: String,
    ) {
        val history = view.copyBackForwardList()
        val previous = history.currentIndex - 1
        if (previous >= 0 && history.getItemAtIndex(previous)?.url == url) {
            view.goBack()
        } else {
            view.loadUrl(url)
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 4

        const val STUCK_PAGE =
            "<html><body style=\"font-family:sans-serif;padding:24px\">" +
                "<p>Instagram keeps sending this app away from your messages.</p>" +
                "<p>Close the app and open it again in a few minutes.</p></body></html>"
    }
}
