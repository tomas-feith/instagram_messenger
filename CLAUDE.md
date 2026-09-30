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
  on class names (they are generated). Not a security boundary.
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
- The debug build is a separate app id with its own WebView profile - a second login.
- Verifying real behaviour needs a logged-in account on a device; unit tests cover the
  policy, the parser and the HTTP client, not the site. `chrome://inspect` works on
  debug builds.
