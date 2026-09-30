package com.instachat.app.web

/**
 * The WebView's default user agent, minus the two markers that identify it as an embedded
 * WebView rather than Chrome.
 *
 * Instagram's mobile site treats an in-app WebView as a second-class browser - it nags to
 * "open the app" and has at times withheld features from it. The rest of the string, the
 * Chrome version included, is left exactly as the engine reports it, so the site still sees
 * the real rendering engine it is talking to.
 *
 * The notification worker sends the same string, so the session's requests all look like
 * one browser.
 */
fun chromeLikeUserAgent(webViewDefault: String): String =
    webViewDefault
        .replace("; wv)", ")")
        .replace(Regex("""Version/\d+(\.\d+)* """), "")
