package com.instachat.app.web

import java.net.URI

/**
 * The one rule this app exists to enforce: the WebView shows Instagram's messaging and
 * nothing else.
 *
 * This is the hard guarantee. The injected script (`assets/hide_chrome.js`) hides the nav
 * bar so the feed is not one tap away, but that is cosmetic and breaks whenever Instagram
 * renames a class. Even with it broken, every main-frame navigation - a real page load or
 * one of the SPA's history.pushState calls - comes through here, and nothing outside chat
 * is allowed to stay on screen.
 *
 * Pure, so it can be tested without a device.
 */
object UrlPolicy {
    const val INBOX_URL = "https://www.instagram.com/direct/inbox/"

    /** What a URL is, independent of where it is being navigated from. */
    enum class Kind {
        /** Anything under /direct/: the inbox, a thread, a new-message screen. */
        CHAT,

        /** Login, two-factor, checkpoints and account settings. Needed to get in at all. */
        AUTH,

        /** One shared post, reel or story, as opened from a message. */
        ITEM,

        /** Instagram, but not chat: the feed, explore, reels, profiles. */
        BLOCKED,

        /** Not Instagram at all. Handed to the browser. */
        EXTERNAL,
    }

    sealed interface Decision {
        /** Let the WebView show it. */
        data object Allow : Decision

        /** Show this URL instead. */
        data class Redirect(
            val url: String,
        ) : Decision

        /** Hand the URL to another app. */
        data class External(
            val url: String,
        ) : Decision

        /** Refuse, and stay where we were. */
        data object Block : Decision
    }

    private val INSTAGRAM_HOSTS = setOf("instagram.com", "www.instagram.com", "m.instagram.com")

    private val FACEBOOK_HOSTS = setOf("facebook.com", "www.facebook.com", "m.facebook.com")

    /**
     * Facebook paths that "Log in with Facebook" and Meta's account checks pass through.
     * Everything else on facebook.com is as much a feed as Instagram's own.
     */
    private val FACEBOOK_AUTH_PATH =
        Regex(
            """^/(login|checkpoint|two_step_verification|auth|(v\d+\.\d+/)?dialog/oauth)(/|$).*""",
        )

    private val AUTH_PREFIXES =
        listOf("/accounts/", "/challenge/", "/two_factor", "/auth_platform/", "/api/v1/web/")

    /** The accounts pages that are really content: the likes-and-follows activity feed. */
    private val AUTH_EXCEPTIONS = listOf("/accounts/activity")

    // Codes are base64url shortcodes; usernames are letters, digits, dots and underscores.
    private const val CODE = """[A-Za-z0-9_-]+"""
    private const val USER = """[A-Za-z0-9._]+"""

    /** A single post or reel, with or without the username prefix newer links carry. */
    private val SINGLE_POST = Regex("""^(/$USER)?/(p|reel|tv)/($CODE)/?$""")

    /**
     * The reels *viewer*: /reels/<code>/ plays that reel and then swipes on into the
     * reels feed. It is rewritten to the single-reel page rather than allowed.
     */
    private val REELS_VIEWER = Regex("""^/reels/($CODE)/?$""")

    /** One story (a numeric media id), or one highlight - never a user's whole tray. */
    private val SINGLE_STORY = Regex("""^/stories/($USER/\d+|highlights/\d+)/?$""")

    fun kind(url: String): Kind {
        val uri = parse(url) ?: return Kind.BLOCKED
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            // about:blank, data:, blob: and friends are the page's own business and never
            // reach here as a main-frame navigation the user chose. intent:, mailto: and
            // similar belong to other apps.
            return if (scheme == "about" || scheme == "data" || scheme == "blob") {
                Kind.BLOCKED
            } else {
                Kind.EXTERNAL
            }
        }
        val host = uri.host?.lowercase() ?: return Kind.BLOCKED
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }

        return when {
            host in INSTAGRAM_HOSTS -> instagramKind(path)

            host in FACEBOOK_HOSTS && FACEBOOK_AUTH_PATH.matches(path) -> Kind.AUTH

            // Includes l.instagram.com, the shim a link pasted into a chat goes through.
            else -> Kind.EXTERNAL
        }
    }

    private fun instagramKind(path: String): Kind =
        when {
            path.startsWith("/direct/") || path == "/direct" -> Kind.CHAT
            AUTH_EXCEPTIONS.any { path.startsWith(it) } -> Kind.BLOCKED
            AUTH_PREFIXES.any { path.startsWith(it) } -> Kind.AUTH
            SINGLE_POST.matches(path) || REELS_VIEWER.matches(path) -> Kind.ITEM
            SINGLE_STORY.matches(path) -> Kind.ITEM
            else -> Kind.BLOCKED
        }

    /**
     * Decide a main-frame navigation to [target], made while [from] was on screen.
     *
     * [from] matters for shared items: a post is allowed when it was opened from a chat,
     * but not from another post, because the single-post page offers "more posts from
     * this account" underneath and following those is how a quick look becomes the feed.
     */
    fun decide(
        target: String,
        from: String?,
    ): Decision {
        val fromKind = from?.let(::kind)
        return when (kind(target)) {
            Kind.CHAT, Kind.AUTH -> {
                Decision.Allow
            }

            Kind.EXTERNAL -> {
                Decision.External(target)
            }

            Kind.ITEM -> {
                when (fromKind) {
                    Kind.CHAT -> {
                        reelsViewerAsSingleReel(target)?.let(Decision::Redirect)
                            ?: Decision.Allow
                    }

                    // The same item again: a reload, or the page settling its own URL.
                    Kind.ITEM -> {
                        if (sameItem(target, from)) Decision.Allow else Decision.Block
                    }

                    else -> {
                        Decision.Block
                    }
                }
            }

            // Instagram lands on the feed after login and after some checkpoints. Coming
            // from anywhere that is not already chat, the right place to go is the inbox.
            Kind.BLOCKED -> {
                if (fromKind == Kind.CHAT || fromKind == Kind.ITEM) {
                    Decision.Block
                } else {
                    Decision.Redirect(INBOX_URL)
                }
            }
        }
    }

    /** /reels/<code>/ as /reel/<code>/, or null when [url] is not the reels viewer. */
    fun reelsViewerAsSingleReel(url: String): String? {
        val uri = parse(url) ?: return null
        val match = REELS_VIEWER.matchEntire(uri.rawPath.orEmpty()) ?: return null
        return "https://www.instagram.com/reel/${match.groupValues[1]}/"
    }

    /** Whether [url] is somewhere the app may open from a notification or another app. */
    fun isChat(url: String): Boolean = kind(url) == Kind.CHAT

    private fun sameItem(
        a: String,
        b: String?,
    ): Boolean {
        val pathA = parse(a)?.rawPath?.trimEnd('/') ?: return false
        val pathB = b?.let(::parse)?.rawPath?.trimEnd('/') ?: return false
        return pathA == pathB
    }

    private fun parse(url: String): URI? =
        try {
            URI(url.trim())
        } catch (_: java.net.URISyntaxException) {
            null
        }
}
