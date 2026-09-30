# Insta Chat

Instagram's direct messages and nothing else. No feed, no reels, no explore, no
suggestions: the app opens on your inbox and cannot be navigated anywhere else.

A native Android shell (Kotlin + Jetpack Compose) around instagram.com's own messaging
pages. Android only, side-loaded, personal. See [docs/INSTALLING.md](docs/INSTALLING.md).

## How it keeps you in the chat

There is no official API for personal Instagram messaging, so the app shows Instagram's
own web client in a WebView and fences it in. Two layers, deliberately unequal:

- **`UrlPolicy`** is the guarantee. Every main-frame navigation - page loads and the
  site's in-page `pushState` changes alike - is classified, and only chat, login and
  account pages may stay on screen. The feed, explore, reels and profiles are refused
  and the WebView returns to where it was. It is pure Kotlin with its own tests.
- **`hide_chrome.js`** is cosmetic. It hides the nav bar and links to the feed, finding
  them by where they point rather than by Instagram's generated class names. If it ever
  stops matching, the links reappear but still lead nowhere.

A post or reel a friend sends you opens on its own, with a "Back to chat" bar. It is
reachable only from a chat, never from another post, so "more posts from this account"
under it goes nowhere. The reels viewer is rewritten to the single-reel page, so a
shared reel cannot be swiped on into the reels feed.

Links to anything outside Instagram open in your browser. `intent:` links - how the site
tries to hand you over to the real Instagram app - are dropped.

## Notifications

A WebView cannot receive Instagram's web push, and there is no server, so a
[WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) job
asks Instagram's web inbox endpoint about every 15 minutes, using the WebView's own
session cookies. A thread whose last message is from someone else, unread by you, not
muted, and newer than anything already announced gets a notification; tapping it opens
that thread.

Trade-offs, stated plainly:

- **Late.** Fifteen minutes is Android's minimum, and Doze stretches it.
- **Undocumented.** The endpoint is the one instagram.com uses for its own inbox; it is
  not a published API and can change. The parser reads defensively, and a response it
  cannot read is logged, not crashed on.
- **Seeds silently.** The first check after install only records the newest message, so
  months of unread chats are not announced at once.

When Instagram logs the session out, one notification says so.

## Privacy

No server, no analytics, no account of our own. The only network traffic is between the
WebView (or the worker, with the same cookies) and Instagram. The only state the app keeps
itself is the notification watermark. Auto Backup is off: a login should be entered on a
new phone, not restored onto it.

## Development

```powershell
.\gradlew.bat staticAnalysis     # ktlint, detekt (type-resolved), Android lint
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
git config core.hooksPath .githooks   # ktlint + detekt before every commit
```

CI runs the same on every push to `main`.
