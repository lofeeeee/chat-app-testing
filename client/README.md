# Singular — client

Kotlin Multiplatform + Compose Multiplatform. One codebase for desktop and Android, rendering
through Skia straight to the GPU.

**No webview.** That is the point of this stack rather than Electron or Tauri: ~180–260 MB
resident and a ~70–90 MB install, against Electron's 400–700 MB and 150–250 MB for the same app.

```bash
./gradlew :composeApp:run      # desktop
./gradlew :composeApp:installDebug   # Android, needs an SDK
```

Talks to a server on `localhost:8080` by default. Point elsewhere with
`-Dsingular.server=https://chat.example.com`.

---

## The Android target is conditional

`composeApp/build.gradle.kts` looks for an SDK in `local.properties` (`sdk.dir`), then
`ANDROID_HOME` / `ANDROID_SDK_ROOT`, then the platform default. Without one it logs

```
No Android SDK found — building desktop only. Set ANDROID_HOME to include Android.
```

and skips the target entirely.

This exists because the Android Gradle Plugin fails the **configuration** phase when it can't
find an SDK — which would make `:composeApp:run` on desktop impossible for anyone who hasn't
installed Android Studio. Install an SDK and the target reappears with no file edit.

---

## Layout

```
composeApp/src/
├── commonMain/     everything shared — all UI, all networking, all state
│   └── app/singular/client/
│       ├── App.kt              root composable, theme, screen routing
│       ├── AppState.kt         single source of UI truth
│       ├── QrLoginState.kt     QR sign-in + session/device management
│       ├── net/                GraphQL client, wire models, operation strings
│       ├── platform/           expect declarations for platform capabilities
│       └── ui/                 screens and components
├── desktopMain/    Swing file picker, ZXing QR encoder, window entry point
└── androidMain/    document picker, ZXing, MainActivity
```

Almost everything is in `commonMain`. The platform source sets hold only what genuinely cannot
be shared: file picking, QR encoding, the install-id store, and the entry point.

---

## Decisions worth not re-litigating

### State lists are `val`, and that is load-bearing

```kotlin
val messages: SnapshotStateList<MessageDto> = mutableStateListOf()
```

Compose observes **reads of a list's contents**. It does not observe reassignment of a plain
`var`. Writing `messages = newList()` swaps in a list nothing is subscribed to, schedules no
recomposition, and leaves the UI rendering the old one.

That bug shipped once here and looked like *"the chat never loads, and messages I send only
appear after a restart"*. Replace contents, never the instance.

### Every id is a `String`

The server mints 64-bit snowflakes and serialises them quoted. Parsing them into a numeric type
on any platform with a 53-bit safe integer range — which includes the Wasm/JS target — corrupts
them silently. They are opaque handles here.

### Images cache on the attachment id, never the URL

Attachment URLs are **presigned and short-lived**. The same picture gets a fresh signature on
every fetch, so keying Coil's cache on the URL would miss every single time and re-download
forever. The snowflake never changes.

The frame is laid out from the server-recorded width and height **before** any pixels arrive, so
the message list never jumps as images resolve — the most irritating thing a chat client can do
while you're reading.

### Two chat layouts, one grouping rule

**Bubbles** (WhatsApp-style) and **Compact** (Discord-style), chosen in Settings — it's a
preference you set once, not an action on a particular chat.

Both group consecutive messages by author, breaking on a gap over 5 minutes. Without the time
break, two messages five hours apart render as one continuous block and read as though they were
said together. The avatar appears once per run; continuation lines get a blank gutter so text
stays aligned.

**Handles never appear in the message list.** `name#0971` is how people find and add each other;
it belongs in the sidebar and on a profile, not above every line of a conversation.

### Keyboard navigation is a layer, not a scattering of handlers

`ui/Keyboard.kt` owns every binding. The shortcut table lives there once and the F1 help sheet is
generated from it, so a binding that changes cannot leave the help lying about it.

| Keys | Action |
|---|---|
| `Esc` | Back one level: dialog → screen → open conversation |
| `Tab` / `Shift+Tab` | Move focus |
| `Enter` / `Shift+Enter` | Send or confirm / newline |
| `Alt+↑` `Alt+↓` | Previous / next conversation or channel |
| `Ctrl+Alt+↑` `Ctrl+Alt+↓` | Previous / next server |
| `Ctrl+0`…`Ctrl+9` | Direct messages, then servers by position |
| `Ctrl+,` `Ctrl+D` `Ctrl+S` | Settings / Devices / Stories |
| `Ctrl+E` `Ctrl+F` | Focus the message box / the handle box |
| `F1` or `Ctrl+/` | Show the list |

Ctrl is read as Ctrl-or-Cmd (`KeyEvent.isCommand`), so macOS needs no second table.

Two things make it work, and both are easy to get wrong:

**Preview events, not ordinary ones.** Compose gives a key to the focused node first, and a text
field swallows Tab, Enter and sometimes Escape. An ancestor using `onKeyEvent` would never fire
while anyone is typing — exactly when shortcuts matter. Preview events travel from the root of
the focused path *down*, so an ancestor gets first refusal.

**`KeyboardScope`, because preview events need something focused.** A node only sees preview
events if it is *on* the focused path; a screen with nothing focused receives no keys at all. The
scope makes its own root focusable and takes focus on arrival. It also takes focus again on
`refocusKey` change — without that, navigating to a screen made only of text (Settings, Devices)
leaves focus nowhere and Escape silently stops working once you are there.

Scopes nest, and the nesting *is* the back stack: `App` handles Escape first and only falls
through to `ChatScreen` — which closes the conversation — when no screen is open above it.
`goBack()` in `App.kt` is the single definition of what "back" means, so Escape, a back arrow and
a Done button cannot disagree.

Two bindings are deliberately absent. Enter does not approve a QR sign-in (granting full account
access must be a deliberate click, never a stray Enter from the field behind the dialog), and
Backspace is not a back gesture (it is a text-editing key; binding it to navigation is how you
lose a half-written message).

### Enter sends, Shift+Enter newlines

Implemented with `onPreviewKeyEvent`, not `onKeyEvent`. The field must never *see* the Enter that
sends, or it inserts a newline first and leaves a blank line behind after the message goes.

### Story overlays are data, not pixels

A story stores a JSON list of positioned elements; the client composites them over the media at
view time. Nothing is baked into the uploaded image, so a story stays restylable and a mention
re-renders with someone's **current** name rather than the one frozen in at posting time.

Coordinates are **fractions of the frame**. A story composed on a phone has to put the sticker in
the same place when read on a desktop; absolute pixels drift with every screen size.

### The GraphQL client is hand-rolled

No Apollo, no codegen. Operation strings and response wrappers are hand-written and nothing
checks them against the schema at build time — a real trade. What it buys: a dependency surface
of Ktor plus kotlinx.serialization, and no build step that reaches over the network for a schema.

Worth revisiting once the schema outgrows one file.

### `expect fun`, not `expect object`

`expect object` is still a Beta Kotlin feature and warns on every build. Top-level expect
functions aren't.

---

## Platform pieces

| Capability | Desktop | Android |
|---|---|---|
| File picking | `JFileChooser`, read on the IO dispatcher | `ACTION_OPEN_DOCUMENT` via `FilePickerBridge` |
| QR encoding | ZXing `Encoder` → module matrix | same artifact (`zxing:core` is pure Java) |
| Install id | file under the config dir | `SharedPreferences` |
| Platform name | `"Windows desktop"` | `"Pixel 8 (Android 15)"` |
| Notifications | AWT `TrayIcon.displayMessage` | `NotificationManagerCompat` |

**Notifications are not push.** Feature 7 (push) wakes a process that isn't running and needs FCM
and APNs credentials this project doesn't have. `showNotification` is the other half: the app is
open, a message arrived somewhere you aren't looking, and something has to say so. It rides the
`notifications` subscription — one socket carrying every channel you can read — because
`messageCreated` is scoped to the conversation you have open, which is the one you never need
telling about.

Both actuals swallow every failure. They are called from inside the socket's collect loop, so a
headless session, a missing tray, or an ungranted `POST_NOTIFICATIONS` must not take message
delivery down with it. The desktop tray icon is generated at the size the OS asks for rather than
shipped as a PNG, and is registered once for the life of the process — removing it after each
toast makes Windows drop the notification it was in the middle of showing.

**`FilePickerBridge`** exists because Android hands results back through an Activity callback
rather than returning them, and launchers must be registered before the Activity reaches
STARTED — long before anyone taps *attach*. The bridge parks the suspending picker on a deferred
that the callback completes. `MainActivity` detaches it in `onDestroy`, since it holds a
`ContentResolver` tied to that Activity.

QR codes are drawn as a **module matrix on a Compose Canvas**, not a bitmap — so they take theme
colours and stay crisp at any density. The 4-module quiet zone is drawn here rather than left to
the caller's padding, because "the QR sometimes doesn't scan" is a miserable bug to track down.

---

## Launching without Gradle

`:composeApp:exportLaunchArgs` writes `build/singular-args.txt` for `java @argfile`.

Two concurrent `gradlew :composeApp:run` invocations **deadlock** — `run` holds the build open
for the app's whole lifetime and the second blocks on the project lock. Opening a second window
for testing therefore needs a Gradle-free path.

An argfile rather than a literal `-cp`: the classpath runs to ~11,000 characters and Windows caps
a command line at 8191.

`../start_another.bat` uses exactly this.

---

## Known gaps

- **Voice recording.** Voice notes are modelled, uploaded and rendered from precomputed peaks;
  capturing needs `AudioRecord` (Android) / `javax.sound` (desktop) plus Opus encoding.
- **Dragging story overlays.** The model carries `x`, `y`, `rot`, `scale`; the gesture handler
  isn't wired.
- **Camera QR scanning.** Generation works; the approval screen takes a pasted code.
- **iOS.** Compose Multiplatform has been Stable on iOS since 1.8.0 — this is a scheduling
  decision, not a technical blocker.
