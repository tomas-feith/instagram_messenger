# Installing and updating on your own phone

This app is not on the Play Store and is not meant to be. It is side-loaded: you build the
APK yourself and install it over USB. Commands are given for PowerShell on Windows.

Unlike the sibling apps, there is no database to lose here - your messages live on
Instagram's servers. What an uninstall costs is the login: the WebView's cookies go with
the app, so you log in (and possibly pass a two-factor check) again. That is the only
reason the signing key matters, but it is still a reason.

## One-time phone setup

1. Settings > About phone > tap **Build number** seven times.
2. Settings > System > **Developer options** > enable **USB debugging**.
3. Connect the phone with a cable that carries data, and accept **Allow USB debugging?**.

```powershell
adb devices
```

The phone should be listed as `device`. `unauthorized` means step 3 has not been accepted.

## First install

Set up signing once per machine ([below](#the-signing-key)), then:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat installRelease
```

If the output is named `app-release-unsigned.apk`, signing is not configured on this machine
and the APK cannot be installed. Fix the configuration rather than falling back to a debug
build, which would install fine and then be unupdatable.

`installDebug` installs a separate app, `com.instachat.app.debug`, labelled
"Insta Chat (debug)", with its own login. Use it to try changes without touching the
release install.

## First run

1. Allow notifications when asked. Without that the app still works; it just never
   tells you about a message.
2. Then answer **OK** to "Stop optimising battery usage?". Without it no check runs while
   Power saving mode is on ([below](#when-notifications-stop)). The app asks once, and
   again whenever it finds an exemption it had gone. If you say no, a banner over the
   chat says what that costs, with **Allow** and **Dismiss**.
3. Log in. The app opens straight on Instagram's login page and, once you are in, on your
   inbox.
4. The first background check, up to 15 minutes later, only records where your inbox is.
   Notifications start with the messages that arrive after it.

## Updating

```powershell
.\gradlew.bat installRelease
```

An in-place upgrade keeps you logged in. It succeeds when the `applicationId`
(`com.instachat.app`) and the signing key both match the installed app. Bump
`versionCode` in `app/build.gradle.kts` for every build that goes on the phone, so the
installed build can be identified.

## The signing key

Android identifies an app by `applicationId` **plus signing certificate**. An update must be
signed by the same key as the install it replaces. The debug key at
`~/.android/debug.keystore` is machine-local and silently regenerated; do not use it for the
install on your phone.

Generate a dedicated key once, **outside the repository** (it is public):

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore "$env:USERPROFILE\.android\instachat-release.jks" `
  -alias instachat -keyalg RSA -keysize 4096 -validity 10950 `
  -dname "CN=Insta Chat, OU=Personal, O=Insta Chat, C=PT"
```

Then create `keystore.properties` in the repository root. It is gitignored:

```properties
# Forward slashes: java.util.Properties treats a backslash as an escape character.
storeFile=C:/Users/<you>/.android/instachat-release.jks
storePassword=<password>
keyAlias=instachat
keyPassword=<password>
```

Back the `.jks` and its password up somewhere durable. Losing them means the next update
needs an uninstall - and a fresh login.

Verify which key signed an APK:

```powershell
apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk
```

## A blank dark screen

Fixed in 0.2. Version 0.1 showed Instagram's background colour and nothing else, on every
page: Compose gave the WebView WRAP_CONTENT layout params, which size the page to its
content, so the root element was 0px tall and instagram.com's `height: 100%` layout
collapsed. `MainActivity.createWebView` now sets MATCH_PARENT. If a blank page ever comes
back, attach `chrome://inspect` to a debug build and check whether `<html>` has a height.

The first page shows Instagram's cookie-consent dialog in the EU. Its policy links open in
the browser, by design.

## When notifications stop

- **"Logged out of Instagram"** was posted: open the app and log in again.
- **Nothing at all**: set the app to **Unrestricted** under App info > Battery. This is
  required, not optional, if Power saving mode is ever on: it cuts every background app
  off the network, except those set to Unrestricted. Messaging apps get through on
  Google's push service, which this app cannot use, so its check never comes due -
  `dumpsys netpolicy` shows `blocked=BATTERY_SAVER|APP_BACKGROUND` for its uid. Found on
  the S23 on 0.4. Since 0.7 the app asks for it, shows a banner while it is missing,
  and a check that finds it gone posts "Message notifications may stop" (the check no
  longer waits for a network, so it runs even when cut off). The same over adb (survives
  updates, not an uninstall):

```powershell
adb shell dumpsys deviceidle whitelist +com.instachat.app
```

- **Still nothing**: every check logs one line - thread count, how many are unread, and
  for each thread newer than the last check whether it was `own`, `read`, `muted` or
  `unread`:

```powershell
adb logcat -s InboxWorker
```

## When the feed shows up again

The nav bar is hidden by `app/src/main/assets/hide_chrome.js`, which finds it by its links,
not by class names. If Instagram changes the markup enough, the bar may reappear - but
tapping it still goes nowhere, because `UrlPolicy.kt` refuses the navigation regardless.
To see what changed, run a debug build and open `chrome://inspect` in desktop Chrome.
