# WA Focus

A quiet inbox that sits next to WhatsApp and shows **only**:

- unread **personal (1:1) messages**
- **group messages that @mention you** (plus optional keyword hits)

Everything else in your groups is ignored. Personal chats are collapsed per contact; mentions stay one line per message.

## How it works

WhatsApp has no public API for reading your chats, and a "duplicate" client would risk your account. WA Focus instead
uses Android's `NotificationListenerService` to read the notifications WhatsApp already posts to your phone,
filters them, and stores the result in a small local SQLite database. Nothing leaves the device (the app has no
network permission). Tapping an item opens WhatsApp.

## Build the APK

No local Android toolchain needed:

1. Create an empty GitHub repo and push this folder to it.
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. Download the `wa-focus-apk` artifact, unzip it, and install `app-debug.apk` on your phone.

Or locally with Android Studio / SDK: `gradle assembleDebug` (JDK 17, Gradle 8.7).

## First-run setup on the phone

1. Open WA Focus; in Settings enter your names/numbers as people @mention you (e.g. `Iqbal, Munahar, 0812xxxxxxx`).
2. Grant **notification access**. On Android 13+ a sideloaded app is blocked until you open
   *Settings → Apps → WA Focus → ⋮ → Allow restricted settings*.
3. Allow background running (battery) so Android doesn't kill the listener.
4. In WhatsApp, keep notifications on.

### Strongly recommended: silence the group channel instead of muting groups

Muted WhatsApp chats generally post **no notification**, so WA Focus can't see mentions in them.
Instead: Android Settings → Apps → WhatsApp → Notifications → **Group messages** channel → set to *Silent*.
Group notifications still reach WA Focus, but won't buzz your phone.

## Known limitations

- Mention detection is text-based: it looks for `@YourName` / `@yourNumber` in the notification text. If WhatsApp
  renders your name differently, add that variant to your names list. Replies to your messages (without an @) can't be detected.
- Only messages that produced a notification are captured; history from before install isn't available.
- WhatsApp trims notification text, so very long messages are truncated.
- "Auto-mark read" relies on WhatsApp cancelling its notification when you read the chat; swiping a notification away does not mark it read.
- The **Diagnostics log** (Settings) shows what the parser saw, including dropped group chatter, to help tune the filter. Turn it off when done.
