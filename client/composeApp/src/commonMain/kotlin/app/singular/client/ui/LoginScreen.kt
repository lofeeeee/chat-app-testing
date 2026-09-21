package app.singular.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.singular.client.AppState
import app.singular.client.QrLoginState
import kotlin.math.roundToInt

@Composable
fun LoginScreen(state: AppState, qr: QrLoginState) {
    val colors = LocalSingularColors.current
    val windowWidth = LocalWindowWidth.current

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
            focus.clearFocus()
            if (registering) state.register(username.trim(), email.trim(), password)
            else state.login(loginEmail.trim(), password)
        }
    }

    // Enter submits, Tab walks the form
    fun Modifier.formKeys(): Modifier = formField(focus, canSubmit && !state.busy, submit)

    LaunchedEffect(registering, tab) {
        if (tab == 0) runCatching { firstField.requestFocus() }
    }

    // Shake animation when an error occurs
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 350
                    0f at 0
                    (-10f) at 50
                    10f at 100
                    (-8f) at 150
                    8f at 200
                    (-4f) at 250
                    4f at 300
                    0f at 350
                },
            )
        }
    }

    // Field color styling anchored to the sunken neutral role
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.sunken,
        unfocusedContainerColor = colors.sunken,
        disabledContainerColor = colors.sunken,
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = Color.Transparent,
        cursorColor = colors.accent,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.textMuted,
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        focusedSupportingTextColor = colors.textMuted,
        unfocusedSupportingTextColor = colors.textFaint,
    )

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
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.canvas),
        ) {
            if (windowWidth.atLeastMedium) {
                // Split-panel layout on wide / desktop viewports
                Row(Modifier.fillMaxSize()) {
                    // Left Brand Hero Panel
                    Box(
                        Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .background(composite(colors.canvas, colors.accent.copy(alpha = 0.05f)))
                            .drawBehind {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            colors.accent.copy(alpha = 0.12f),
                                            Color.Transparent,
                                        ),
                                        center = Offset(size.width * 0.35f, size.height * 0.45f),
                                        radius = size.minDimension * 0.85f,
                                    ),
                                )
                            }
                            .padding(horizontal = 48.dp, vertical = 52.dp),
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Brand Wordmark
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "Singular",
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.5).sp,
                                    ),
                                    color = colors.text,
                                )
                                Text(
                                    ".",
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = colors.accent,
                                )
                            }

                            // Mock chat preview demonstrating editorial rhythm & palette
                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.widthIn(max = 420.dp),
                            ) {
                                Surface(
                                    shape = SingularShapes.large,
                                    color = colors.raised,
                                    border = BorderStroke(1.dp, colors.line.copy(alpha = 0.5f)),
                                ) {
                                    Column(
                                        Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "Dieter",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                                color = colors.accentSoft,
                                            )
                                            Text(
                                                "10:42 AM",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textFaint,
                                            )
                                        }
                                        Text(
                                            "Good design is as little design as possible. Quiet, functional, and self-contained.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.text,
                                        )
                                    }
                                }

                                Box(
                                    Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Surface(
                                        shape = SingularShapes.large,
                                        color = colors.accent,
                                    ) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(
                                                "Zero external dependencies. 100% offline.",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Medium,
                                                ),
                                                color = colors.onAccent,
                                            )
                                        }
                                    }
                                }
                            }

                            // Quiet footer note
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(colors.accent),
                                )
                                Text(
                                    "Encrypted, ergonomic messaging.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }

                    VerticalDivider(color = colors.line.copy(alpha = 0.5f))

                    // Right Form Panel
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoginFormContent(
                            tab = tab,
                            onTabChange = { tab = it },
                            registering = registering,
                            onToggleRegister = {
                                registering = !registering
                                state.dismissError()
                            },
                            loginEmail = loginEmail,
                            onLoginEmailChange = { loginEmail = it },
                            username = username,
                            onUsernameChange = { username = it },
                            email = email,
                            onEmailChange = { email = it },
                            password = password,
                            onPasswordChange = { password = it },
                            firstField = firstField,
                            formKeys = { formKeys() },
                            submit = submit,
                            state = state,
                            qr = qr,
                            canSubmit = canSubmit,
                            shakeOffset = shakeOffset.value,
                            fieldColors = fieldColors,
                            showMobileWordmark = false,
                        )
                    }
                }
            } else {
                // Compact / Mobile layout: centered single column
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    LoginFormContent(
                        tab = tab,
                        onTabChange = { tab = it },
                        registering = registering,
                        onToggleRegister = {
                            registering = !registering
                            state.dismissError()
                        },
                        loginEmail = loginEmail,
                        onLoginEmailChange = { loginEmail = it },
                        username = username,
                        onUsernameChange = { username = it },
                        email = email,
                        onEmailChange = { email = it },
                        password = password,
                        onPasswordChange = { password = it },
                        firstField = firstField,
                        formKeys = { formKeys() },
                        submit = submit,
                        state = state,
                        qr = qr,
                        canSubmit = canSubmit,
                        shakeOffset = shakeOffset.value,
                        fieldColors = fieldColors,
                        showMobileWordmark = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginFormContent(
    tab: Int,
    onTabChange: (Int) -> Unit,
    registering: Boolean,
    onToggleRegister: () -> Unit,
    loginEmail: String,
    onLoginEmailChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    firstField: FocusRequester,
    formKeys: Modifier.() -> Modifier,
    submit: () -> Unit,
    state: AppState,
    qr: QrLoginState,
    canSubmit: Boolean,
    shakeOffset: Float,
    fieldColors: androidx.compose.material3.TextFieldColors,
    showMobileWordmark: Boolean,
) {
    val colors = LocalSingularColors.current

    Surface(
        shape = SingularShapes.medium,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.line),
        modifier = Modifier
            .widthIn(max = 440.dp)
            .padding(24.dp)
            .offset { IntOffset(shakeOffset.roundToInt(), 0) }
            .animateEntrance(),
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header / Wordmark
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showMobileWordmark) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "Singular",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = colors.text,
                        )
                        Text(
                            ".",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = colors.accent,
                        )
                    }
                } else {
                    Text(
                        if (registering) "Create account" else "Welcome back",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.text,
                    )
                }

                Text(
                    if (registering) {
                        "Pick a name and handle to get started."
                    } else {
                        "Sign in to access your channels and conversations."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }

            // Segmented pill control replacing clunky TabRow
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(SingularShapes.small)
                    .background(colors.sunken)
                    .padding(3.dp),
            ) {
                listOf("Password" to 0, "QR code" to 1).forEach { (label, index) ->
                    val isSelected = tab == index
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(SingularShapes.extraSmall)
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .background(colors.surface)
                                        .border(1.dp, colors.line, SingularShapes.extraSmall)
                                } else Modifier,
                            )
                            .clickable { onTabChange(index) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (isSelected) colors.text else colors.textMuted,
                        )
                    }
                }
            }

            // QR flow panel
            if (tab == 1) {
                QrLoginPanel(qr)
                return@Column
            }

            // Smooth crossfade between sign-in and register form fields
            AnimatedContent(
                targetState = registering,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                label = "auth-mode-transition",
            ) { isRegister ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (isRegister) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = onUsernameChange,
                            label = { Text("Username") },
                            supportingText = { Text("Letters, numbers, _ and . — 2 to 32 characters") },
                            singleLine = true,
                            shape = SingularShapes.small,
                            colors = fieldColors,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(firstField)
                                .formKeys(),
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = onEmailChange,
                            label = { Text("Email") },
                            singleLine = true,
                            shape = SingularShapes.small,
                            colors = fieldColors,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .formKeys(),
                        )
                    } else {
                        OutlinedTextField(
                            value = loginEmail,
                            onValueChange = onLoginEmailChange,
                            label = { Text("Email") },
                            placeholder = { Text("you@example.com") },
                            singleLine = true,
                            shape = SingularShapes.small,
                            colors = fieldColors,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(firstField)
                                .formKeys(),
                        )
                    }

                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text("Password") },
                        supportingText = if (isRegister) {
                            { Text("At least 10 characters. Length beats punctuation.") }
                        } else null,
                        singleLine = true,
                        shape = SingularShapes.small,
                        colors = fieldColors,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .formKeys(),
                    )
                }
            }

            // Real Error Banner
            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn(tween(150)) + expandVertically(tween(180)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(150)),
            ) {
                state.error?.let { msg ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(SingularShapes.small)
                            .background(colors.danger.copy(alpha = 0.10f))
                            .border(1.dp, colors.danger.copy(alpha = 0.30f), SingularShapes.small)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = colors.danger,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            msg,
                            color = colors.danger,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Primary Action Button with presence
            Button(
                onClick = submit,
                enabled = !state.busy && canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.accent.copy(alpha = 0.35f),
                    disabledContentColor = colors.onAccent.copy(alpha = 0.5f),
                ),
                shape = SingularShapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .pressScale(),
            ) {
                if (state.busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.5.dp,
                            color = colors.onAccent,
                        )
                        Text(
                            if (registering) "Creating account…" else "Signing in…",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                } else {
                    Text(
                        if (registering) "Create account" else "Sign in",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }

            // Toggle between login and registration
            TextButton(
                onClick = onToggleRegister,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(),
            ) {
                Text(
                    if (registering) "Already have an account? Sign in →"
                    else "Don't have an account? Create one →",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = colors.accentSoft,
                )
            }
        }
    }
}
