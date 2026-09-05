package app.singular.client.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * The app's keyboard layer.
 *
 * Everything here is built on **preview** key events rather than ordinary ones, and that is the
 * whole trick. Compose delivers a key to the focused node first; a text field with focus
 * swallows Tab (to insert a tab), Enter (to insert a newline) and, on some platforms, Escape.
 * A handler on an ancestor using `onKeyEvent` would therefore never fire while anyone is
 * typing — which is precisely when shortcuts matter. Preview events travel the other way, from
 * the root of the focused path down to the focused node, so an ancestor gets first refusal.
 *
 * The second half of the trick is [KeyboardScope]. Preview events only reach a node if it is
 * *on* the focused path, so a screen with nothing focused receives no keys at all. The scope
 * makes its own root focusable and takes focus on arrival, which is what makes Escape work on
 * a screen that is only a list of text.
 *
 * ## The map
 *
 * | Keys | What happens |
 * |---|---|
 * | `Esc` | Back, one level: dialog, then screen, then the open conversation |
 * | `Tab` / `Shift+Tab` | Move focus forward / back |
 * | `Enter` | Send, or confirm the dialog you're in |
 * | `Shift+Enter` | Newline in the message box |
 * | `Alt+↑` / `Alt+↓` | Previous / next conversation or channel |
 * | `Ctrl+Alt+↑` / `Ctrl+Alt+↓` | Previous / next server |
 * | `Ctrl+1`…`Ctrl+9` | Jump straight to that server |
 * | `Ctrl+0` | Direct messages |
 * | `Ctrl+,` | Settings |
 * | `Ctrl+D` | Devices and sign-ins |
 * | `Ctrl+S` | Stories |
 * | `Ctrl+M` | Mentions inbox |
 * | `Ctrl+E` | Focus the message box |
 * | `Ctrl+F` | Focus the handle box (Friends) |
 * | `F1` / `Ctrl+/` | This list |
 *
 * Ctrl is read as Ctrl-or-Cmd throughout ([isCommand]), so the same bindings work on macOS
 * without a second table to keep in sync.
 */
object Shortcuts {

    /** One row of the help sheet. */
    data class Entry(val keys: String, val description: String)

    val entries: List<Entry> = listOf(
        Entry("Esc", "Go back — closes a dialog, a screen, then the open conversation"),
        Entry("Tab / Shift+Tab", "Move focus between controls"),
        Entry("Enter", "Send the message, or confirm the open dialog"),
        Entry("Shift+Enter", "New line in the message box"),
        Entry("Alt+Up / Alt+Down", "Previous / next conversation or channel"),
        Entry("Ctrl+Alt+Up / Down", "Previous / next server"),
        Entry("Ctrl+1 … Ctrl+9", "Jump to a server by position"),
        Entry("Ctrl+0", "Back to direct messages"),
        Entry("Ctrl+,", "Settings"),
        Entry("Ctrl+D", "Devices and sign-ins"),
        Entry("Ctrl+S", "Stories"),
        Entry("Ctrl+M", "Mentions inbox"),
        Entry("Ctrl+E", "Focus the message box"),
        Entry("Ctrl+F", "Focus the add-by-handle box"),
        Entry("F1 or Ctrl+/", "Show this list"),
    )
}

/**
 * Ctrl on Windows and Linux, Cmd on macOS.
 *
 * Read through one property so a binding is written once. Checking `isCtrlPressed` alone would
 * give Mac users a set of shortcuts that fight the platform's own conventions; checking both
 * at every call site would eventually miss one.
 */
val KeyEvent.isCommand: Boolean get() = isCtrlPressed || isMetaPressed

/** True for a real press — not the repeat or the release. */
val KeyEvent.isPress: Boolean get() = type == KeyEventType.KeyDown

/**
 * A region that can receive keys even when nothing inside it is focused.
 *
 * Takes focus once on arrival. When a child later takes focus — a text field, a button — this
 * node stays on the focused path as its ancestor, so it keeps getting preview events and the
 * shortcuts carry on working while someone types.
 *
 * [onPreviewKey] returns true to consume a key. Return false for anything you don't handle, or
 * you will silently eat every keystroke in every field underneath.
 */
@Composable
fun KeyboardScope(
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    /**
     * Re-takes focus whenever this value changes.
     *
     * Needed on navigation. When the focused control belongs to a screen that just went away,
     * focus has nowhere obvious to land, and a screen made only of text — Settings, Devices —
     * offers nothing to catch it. Without a nudge on each change, Escape would work going in
     * and then quietly stop working once you were there.
     */
    refocusKey: Any? = Unit,
    onPreviewKey: (KeyEvent) -> Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val requester = remember { FocusRequester() }

    LaunchedEffect(autoFocus, refocusKey) {
        // runCatching: requesting focus on a node that hasn't been placed yet throws, which
        // happens on a screen that is briefly empty. A missing initial focus is a small
        // annoyance; a crash on navigation is not.
        if (autoFocus) runCatching { requester.requestFocus() }
    }

    Box(
        modifier
            .fillMaxSize()
            .focusRequester(requester)
            .focusable()
            .onPreviewKeyEvent(onPreviewKey),
        content = content,
    )
}

/**
 * Enter confirms, Tab traverses — the standard behaviour of a form field.
 *
 * Shift+Enter is left alone so a field that accepts newlines still can. [enabled] gates only
 * the confirm: Tab must keep working even when the form is incomplete, or you cannot reach the
 * field you still need to fill in.
 */
fun Modifier.formField(
    focus: FocusManager,
    enabled: Boolean = true,
    onConfirm: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (!event.isPress) return@onPreviewKeyEvent false
    when {
        event.key == Key.Tab -> {
            focus.moveFocus(
                if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
            )
            true
        }
        (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed -> {
            if (enabled) onConfirm()
            // Consumed either way: a disabled confirm must still not leave a stray newline in
            // a field the user is about to submit.
            true
        }
        else -> false
    }
}

/**
 * Escape dismisses and Enter confirms, for a dialog's body.
 *
 * Wrapped around a dialog's content rather than relying on the platform, because Escape-closes
 * is only guaranteed for real OS windows — a dialog drawn in a popup inside our own window is
 * ours to handle. [onConfirm] is optional: a dialog holding a multi-line field must not treat
 * Enter as "OK", or it becomes impossible to type a second line.
 */
@Composable
fun DialogKeys(
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }

    Box(
        Modifier
            .focusRequester(requester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!event.isPress) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Escape -> { onDismiss(); true }
                    onConfirm != null && !event.isShiftPressed &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        if (confirmEnabled) onConfirm()
                        true
                    }
                    else -> false
                }
            },
        content = content,
    )
}

/**
 * The navigation shortcuts that work anywhere in the app shell.
 *
 * Kept in one function so the bindings are defined once and cannot drift between the screens
 * that host them. Returns true when it consumed the key.
 */
fun handleGlobalShortcut(
    event: KeyEvent,
    onSettings: () -> Unit,
    onSessions: () -> Unit,
    onStories: () -> Unit,
    onMentions: () -> Unit,
    onHelp: () -> Unit,
): Boolean {
    if (!event.isPress) return false

    // F1 needs no modifier; every other binding here is a Ctrl/Cmd chord.
    if (event.key == Key.F1) { onHelp(); return true }
    if (!event.isCommand) return false

    return when (event.key) {
        Key.Comma -> { onSettings(); true }
        Key.D -> { onSessions(); true }
        Key.S -> { onStories(); true }
        Key.M -> { onMentions(); true }
        Key.Slash -> { onHelp(); true }
        else -> false
    }
}

/** Alt-arrow and Ctrl-digit navigation. Returns true when it consumed the key. */
fun handleNavigationShortcut(
    event: KeyEvent,
    onChannelStep: (Int) -> Unit,
    onGuildStep: (Int) -> Unit,
    onGuildIndex: (Int) -> Unit,
    onFocusComposer: () -> Unit,
    onFocusSearch: () -> Unit,
): Boolean {
    if (!event.isPress) return false

    // Ctrl+Alt+arrow before Alt+arrow: the server binding is a superset of the channel one,
    // and testing the looser condition first would make Ctrl+Alt+Up move the channel instead.
    if (event.isAltPressed && event.isCommand) {
        return when (event.key) {
            Key.DirectionUp -> { onGuildStep(-1); true }
            Key.DirectionDown -> { onGuildStep(1); true }
            else -> false
        }
    }

    if (event.isAltPressed) {
        return when (event.key) {
            Key.DirectionUp -> { onChannelStep(-1); true }
            Key.DirectionDown -> { onChannelStep(1); true }
            else -> false
        }
    }

    if (!event.isCommand) return false

    DIGIT_KEYS[event.key]?.let { position ->
        // 0 is home (direct messages), 1..9 are servers by position — the arrangement every
        // tabbed app uses, so it needs no explaining.
        onGuildIndex(position - 1)
        return true
    }

    return when (event.key) {
        Key.E -> { onFocusComposer(); true }
        Key.F -> { onFocusSearch(); true }
        else -> false
    }
}

/**
 * Digit keys, including the numpad.
 *
 * A map rather than arithmetic on the key code: `Key.One`'s numeric value is a platform
 * detail, and deriving a position from it is the kind of thing that works on desktop and
 * quietly returns nonsense elsewhere.
 */
private val DIGIT_KEYS: Map<Key, Int> = mapOf(
    Key.Zero to 0, Key.NumPad0 to 0,
    Key.One to 1, Key.NumPad1 to 1,
    Key.Two to 2, Key.NumPad2 to 2,
    Key.Three to 3, Key.NumPad3 to 3,
    Key.Four to 4, Key.NumPad4 to 4,
    Key.Five to 5, Key.NumPad5 to 5,
    Key.Six to 6, Key.NumPad6 to 6,
    Key.Seven to 7, Key.NumPad7 to 7,
    Key.Eight to 8, Key.NumPad8 to 8,
    Key.Nine to 9, Key.NumPad9 to 9,
)
