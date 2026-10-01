# Stack

Native Kotlin + Jetpack Compose, Android only. Set up to mirror the sibling
`habit_tracker`: same version catalog, ktlint + detekt 2 (type-resolved `detektMain` /
`detektTest`) + Android lint with `warningsAsErrors`, same absent-tolerant
`keystore.properties` signing block, same CI shape.

The app is a WebView on instagram.com's messaging, fenced in, plus a WorkManager job for
notifications. There is no official API for personal Instagram DMs; the Messaging API is
Business/Creator-only. Unofficial mobile-API libraries were rejected because they risk the
account; the WebView is Instagram's own web client and looks like a browser to it.

## Deliberate departures from the global defaults

- **No Room.** There is no user data to persist: messages are server-side and the login
  is the WebView's cookie jar. The notifier's watermark and one flag live in
  SharedPreferences (`notify/NotifierState.kt`). No schemas, so no instrumentation job
  in CI either.
- **Auto Backup off**, including device transfer, because the only data is a login.

## Where the logic is

- `web/UrlPolicy.kt` - the guarantee that only chat is shown. Pure and tested. Any change
  to what is reachable goes here, with a test.
- `web/ChatWebViewClient.kt` - applies it to both kinds of navigation. The site is an
  SPA: `shouldOverrideUrlLoading` sees real loads *before* they happen, but pushState
  changes are only reported through `doUpdateVisitedHistory` *after*, so those are backed
  out rather than refused. Forgetting the second path is how the feed would leak back in.
- `assets/hide_chrome.js` - cosmetic hiding of the nav bar, keyed on link targets, never
  on class names (they are generated). Not a security boundary. Also the **reel lock**:
  the single-reel page loads suggested reels below and swiping to them changes no URL
  the policy sees, so on a reel path the script freezes scrolling and swallows vertical
  swipes. It restores the site's own inline styles on the way out; deleting them instead
  once left a scroller dead after returning to chat. Reported by the user on 0.2.
- **The reel viewer never changes the URL.** Tapping a reel in a chat opens a viewer over
  `/direct/t/<id>/` - a vertical scroll-snap container with the shared reel and ~14
  suggested ones stacked below (inspected on the phone, 0.3). No path check can see it,
  and a hard flick is turned into a programmatic scroll by the site, which overflow and
  swipe blocking do not stop. `lockReelViewers()` finds it by structure (video in a
  snap-aligned item in a vertical snap container), freezes it, and hides every branch
  but the reel that was on screen when it opened. To inspect a release install, flip
  the `FLAG_DEBUGGABLE` check in `createWebView` locally and never commit it.
- `notify/Inbox.kt` - parses the undocumented `/api/v1/direct_v2/inbox/` response.
  Defensive by design: a malformed thread is skipped, not fatal; ids and timestamps are
  accepted as numbers or strings. Timestamps are **microseconds**.
- `notify/InboxWorker.kt` - reads cookies from `CookieManager` on the main thread, fetches
  with the WebView's (de-`wv`'d) user agent, notifies, advances the watermark.

## Things that are not obvious

- Notification thread ids use `thread_v2_id` (what web URLs use), falling back to the
  legacy `thread_id`. If tapping a notification opens "thread not found", this is the
  first thing to check.
- `X-IG-App-ID: 936619743392459` is the public id of the instagram.com web client. The
  endpoint refuses requests without it.
- Redirects are not followed by the worker's OkHttp client: a 3xx from the inbox endpoint
  always means login or a checkpoint.
- Back never walks the WebView history blindly. A refusal that cannot `goBack()` cleanly
  reloads the allowed page on top, leaving the refused one in history; stepping onto it
  would bounce forever. See `MainActivity.handleBack`.
- `FileChooserParams.createIntent()` is not used: it breaks on the site's multi-type
  accept list and ignores multi-select.
- The WebView must have MATCH_PARENT layout params. AndroidView's default is
  WRAP_CONTENT, which makes the page's root 0px tall; instagram.com lays everything out
  from `height: 100%`, so 0.1 rendered a blank background on every page. The emulator
  showed the same symptom and it was first misread as an outdated WebView - it was not.
- **Notifications need the battery exemption.** With Power saving mode on, Android cuts
  every non-exempt background app off the network, so the job's CONNECTIVITY constraint
  is never met and no check runs - silently. 0.4 delivered nothing on the S23 for this
  reason. `notify/BatteryExemption.kt` asks on launch (once, and again if it is lost);
  the worker warns once if it runs without it. Diagnose with `adb logcat -s InboxWorker`
  (one line per check) and `dumpsys netpolicy` (`blocked_state` for the app's uid).
- The debug build is a separate app id with its own WebView profile - a second login.
- Verifying real behaviour needs a logged-in account on a device; unit tests cover the
  policy, the parser and the HTTP client, not the site. `chrome://inspect` works on
  debug builds.
