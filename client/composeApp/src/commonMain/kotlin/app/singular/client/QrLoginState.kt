package app.singular.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.singular.client.net.ApproveLoginData
import app.singular.client.net.AuthOperations
import app.singular.client.net.ClaimLoginData
import app.singular.client.net.CreateLoginData
import app.singular.client.net.DenyLoginData
import app.singular.client.net.DeviceSessionDto
import app.singular.client.net.LoginRequestUpdatedData
import app.singular.client.net.RevokeOthersData
import app.singular.client.net.RevokeSessionData
import app.singular.client.net.RotateLoginData
import app.singular.client.net.ScannedLoginRequestDto
import app.singular.client.net.SessionsData
import app.singular.client.net.SingularClient
import app.singular.client.net.UserDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class QrPhase { IDLE, WAITING, SCANNED, APPROVED, DENIED, EXPIRED, FAILED }

/**
 * QR sign-in, requesting side.
 *
 * The device asks for a request and gets back two things: a **public token** that goes in the
 * QR and rotates every 20 seconds, and a **private poll secret** that never rotates and never
 * appears on screen. It renders the token, swaps it on a timer, and listens on a subscription
 * authenticated by the poll secret — which is exactly why rotating the visible half doesn't
 * drop the channel that eventually carries the tokens.
 *
 * Rotation bounds replay of a code captured off a screen (a screen share, a photo, a leaked
 * screenshot). It does nothing against someone tricking you into scanning *their* QR; the
 * confirmation screen in [SessionState.pendingApproval] is what defends against that.
 */
class QrLoginState(
    private val client: SingularClient,
    private val scope: CoroutineScope,
    private val platform: String,
    private val onSignedIn: suspend (accessToken: String, refreshToken: String, user: UserDto) -> Unit,
) {
    var phase by mutableStateOf(QrPhase.IDLE)
        private set
    var qrPayload by mutableStateOf<String?>(null)
        private set

    /** Counts down to the next swap, so the UI can show progress instead of a code that blinks. */
    var secondsUntilRotate by mutableStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var approvedBy by mutableStateOf<String?>(null)
        private set

    private var rotateJob: Job? = null
    private var watchJob: Job? = null

    fun start() {
        cancel()
        phase = QrPhase.WAITING
        message = null
        approvedBy = null

        scope.launch {
            try {
                val created = client.execute<CreateLoginData>(
                    AuthOperations.CREATE_LOGIN_REQUEST,
                    buildJsonObject {
                        put("deviceId", deviceId())
                        put("platform", platform)
                    },
                ).created

                qrPayload = created.request.qrPayload

                // Subscribe before the first rotation, so a phone that scans within the first
                // second can't resolve the request before we're listening for the result.
                watch(created.request.id, created.pollSecret)
                rotate(created.request.id, created.pollSecret, created.request.rotateAfterSeconds)
            } catch (e: Exception) {
                phase = QrPhase.FAILED
                message = e.message ?: "Couldn't start QR sign-in."
            }
        }
    }

    /**
     * Swaps the displayed code on an interval.
     *
     * Stops once a phone has scanned. The server freezes rotation at that point so the code the
     * phone is holding stays valid through the approval screen; continuing to ask would be noise.
     */
    private fun rotate(id: String, secret: String, intervalSeconds: Int) {
        rotateJob = scope.launch {
            while (isActive && phase == QrPhase.WAITING) {
                for (remaining in intervalSeconds downTo 1) {
                    if (phase != QrPhase.WAITING) return@launch
                    secondsUntilRotate = remaining
                    delay(1_000)
                }
                if (phase != QrPhase.WAITING) return@launch

                try {
                    val next = client.execute<RotateLoginData>(
                        AuthOperations.ROTATE_LOGIN_TOKEN,
                        buildJsonObject { put("id", id); put("pollSecret", secret) },
                    ).request

                    // A blank payload means the server declined to rotate because the request
                    // was scanned. Keep the current code up and let the watcher drive from here.
                    if (next.qrPayload.isNotBlank()) qrPayload = next.qrPayload
                } catch (_: Exception) {
                    phase = QrPhase.EXPIRED
                    message = "This code expired. Start again."
                    return@launch
                }
            }
        }
    }

    private fun watch(id: String, secret: String) {
        watchJob = scope.launch {
            try {
                client.subscribe(
                    AuthOperations.LOGIN_REQUEST_UPDATED,
                    buildJsonObject { put("id", id); put("pollSecret", secret) },
                ).collect { data ->
                    val event = SingularClient.codec
                        .decodeFromJsonElement(LoginRequestUpdatedData.serializer(), data)
                        .event

                    when (event.status) {
                        "SCANNED" -> {
                            phase = QrPhase.SCANNED
                            message = "Scanned. Confirm on your other device."
                        }

                        "APPROVED" -> {
                            approvedBy = event.approvedBy?.handle
                            event.auth?.let {
                                phase = QrPhase.APPROVED
                                onSignedIn(it.accessToken, it.refreshToken, it.user)
                            }
                            cancel()
                        }

                        "DENIED" -> {
                            phase = QrPhase.DENIED
                            message = "Sign-in was denied on the other device."
                            cancel()
                        }

                        "EXPIRED" -> {
                            phase = QrPhase.EXPIRED
                            message = "This request expired. Start again."
                            cancel()
                        }
                    }
                }
            } catch (e: Exception) {
                if (phase == QrPhase.WAITING || phase == QrPhase.SCANNED) {
                    phase = QrPhase.FAILED
                    message = e.message ?: "Lost contact with the server."
                }
            }
        }
    }

    fun cancel() {
        rotateJob?.cancel(); rotateJob = null
        watchJob?.cancel(); watchJob = null
    }

    fun reset() {
        cancel()
        phase = QrPhase.IDLE
        qrPayload = null
        message = null
        approvedBy = null
    }
}

/**
 * The approving side, plus "where you're signed in".
 *
 * Both need an already-authenticated user — which is the point. A device that can approve a QR
 * sign-in is by definition one the account already trusts.
 */
class SessionState(
    private val client: SingularClient,
    private val scope: CoroutineScope,
) {
    var devices = mutableStateListOf<DeviceSessionDto>()
        private set
    var pendingApproval by mutableStateOf<ScannedLoginRequestDto?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    fun load() = launchGuarded {
        val loaded = client.execute<SessionsData>(AuthOperations.SESSIONS).sessions
        devices.clear()
        devices.addAll(loaded)
    }

    fun revoke(familyId: String) = launchGuarded {
        client.execute<RevokeSessionData>(
            AuthOperations.REVOKE_SESSION,
            buildJsonObject { put("id", familyId) },
        )
        devices.removeAll { it.id == familyId }
    }

    fun revokeOthers() = launchGuarded {
        val count = client.execute<RevokeOthersData>(AuthOperations.REVOKE_OTHERS).count
        message = if (count == 0) "No other devices were signed in." else "Signed out $count device(s)."
        devices.removeAll { !it.current }
    }

    /**
     * Step one of approving a sign-in: claim the code and find out what is asking.
     *
     * Deliberately does not sign anything in. The user has to see the device, platform and IP
     * first and then call [approve] — that separation is the whole defence against being talked
     * into scanning someone else's code.
     */
    fun claim(scanned: String) = launchGuarded {
        // Accept the raw token or the whole singular://login?id=…&t=… deep link, so a camera
        // scan and a hand-typed code both work.
        val token = scanned.substringAfter("t=", scanned).substringBefore("&").trim()
        pendingApproval = client.execute<ClaimLoginData>(
            AuthOperations.CLAIM_LOGIN_REQUEST,
            buildJsonObject { put("qrToken", token) },
        ).scanned
    }

    fun approve() = launchGuarded {
        val request = pendingApproval ?: return@launchGuarded
        client.execute<ApproveLoginData>(
            AuthOperations.APPROVE_LOGIN_REQUEST,
            buildJsonObject { put("id", request.id) },
        )
        pendingApproval = null
        message = "Signed in on the other device."
        load()
    }

    fun deny() = launchGuarded {
        val request = pendingApproval ?: return@launchGuarded
        client.execute<DenyLoginData>(
            AuthOperations.DENY_LOGIN_REQUEST,
            buildJsonObject { put("id", request.id) },
        )
        pendingApproval = null
        message = "Denied."
    }

    fun dismiss() {
        pendingApproval = null
        message = null
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                message = e.message ?: "Something went wrong."
            } finally {
                busy = false
            }
        }
    }
}
