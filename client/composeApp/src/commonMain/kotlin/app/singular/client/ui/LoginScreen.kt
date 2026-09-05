package app.singular.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.QrLoginState

@Composable
fun LoginScreen(state: AppState, qr: QrLoginState) {
    var tab by remember { mutableStateOf(0) }
    var registering by remember { mutableStateOf(false) }
    var loginEmail by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val focus = LocalFocusManager.current
    val firstField = remember { FocusRequester() }

    val canSubmit =
        if (registering) username.isNotBlank() && email.isNotBlank() && password.isNotBlank()
        else loginEmail.isNotBlank() && password.isNotBlank()

    val submit = {
        if (canSubmit && !state.busy) {
            // Drop focus first so the soft keyboard closes on mobile and the field stops
            // swallowing keys while the request is in flight.
            focus.clearFocus()
            if (registering) state.register(username.trim(), email.trim(), password)
            else state.login(loginEmail.trim(), password)
        }
    }

    // Enter submits, Tab walks the form. Shared with every other form in the app so the
    // behaviour cannot drift between them.
    fun Modifier.formKeys(): Modifier = formField(focus, canSubmit && !state.busy, submit)

    // Lands in the first field on arrival and whenever the form swaps between sign-in and
    // register, so the whole thing is typeable without reaching for the mouse.
    LaunchedEffect(registering, tab) {
        if (tab == 0) runCatching { firstField.requestFocus() }
    }

    // Escape backs out of the register form to sign-in — the only "back" this screen has.
    // There is nowhere further to go, so it does nothing on the sign-in form rather than
    // consuming the key.
    KeyboardScope(
        onPreviewKey = { event ->
            if (event.isPress && event.key == Key.Escape && (registering || tab == 1)) {
                registering = false
                tab = 0
                state.dismissError()
                true
            } else false
        },
    ) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 400.dp).padding(24.dp)) {
            Column(
                Modifier.padding(28.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Singular", style = MaterialTheme.typography.headlineMedium)

                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Password") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("QR code") })
                }

                // The panel starts and stops the rotation loop with its own lifecycle, so
                // switching back to the password tab tears down the socket rather than leaving
                // it minting codes nobody is looking at.
                if (tab == 1) {
                    QrLoginPanel(qr)
                    return@Column
                }

                Text(
                    if (registering) {
                        "Pick a name. We'll add a random number to it — that handle is how " +
                            "friends find you. You'll sign in with your email."
                    } else {
                        "Sign in with your email address."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (registering) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        supportingText = { Text("Letters, numbers, _ and . — 2 to 32 characters") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField).formKeys(),
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth().formKeys(),
                    )
                } else {
                    OutlinedTextField(
                        value = loginEmail,
                        onValueChange = { loginEmail = it },
                        label = { Text("Email") },
                        placeholder = { Text("you@example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField).formKeys(),
                    )
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    supportingText = if (registering) {
                        { Text("At least 10 characters. Length beats punctuation.") }
                    } else null,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().formKeys(),
                )

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = submit,
                    enabled = !state.busy && canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (registering) "Create account" else "Sign in")
                    }
                }

                TextButton(
                    onClick = { registering = !registering; state.dismissError() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (registering) "I already have an account"
                        else "Create an account"
                    )
                }
            }
        }
      }
    }
}
