// AltsCodex OAuth(공개 클라이언트 + PKCE) 로그인·세션 유지(refresh 회전)·로그아웃을 담당하는 SDK 진입점.
package com.altscodex.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import java.security.MessageDigest

/**
 * Sign in with AltsCodex, keep the session fresh, and sign out.
 *
 * Create one instance per app (for example in your `Application`) and reuse it. Every suspend function is
 * main-safe. The browser callback is received by AppAuth's `RedirectUriReceiverActivity`, so set
 * `manifestPlaceholders["appAuthRedirectScheme"]` to the scheme of [AltsCodexConfig.redirectUri].
 *
 * ```
 * val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
 *     lifecycleScope.launch { render(auth.handleResult(it.data)) }
 * }
 * lifecycleScope.launch { launcher.launch(auth.loginIntent()) }
 * ```
 */
public class AltsCodexAuth internal constructor(
    private val config: AltsCodexConfig,
    private val store: SecureStore,
    private val gateway: TokenGateway,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) {
    /** Creates the client with [KeystoreSecureStore] unless [AltsCodexConfig.store] is set. */
    public constructor(context: Context, config: AltsCodexConfig) : this(
        config,
        config.store ?: KeystoreSecureStore(context.applicationContext, config.clientId),
        AppAuthGateway(context.applicationContext, config),
        System::currentTimeMillis,
        Dispatchers.IO,
    )

    private val refreshLock = Mutex()
    private val callbackLock = Mutex()

    // 로그아웃마다 증가한다. 저장 직전 값을 다시 확인해 로그아웃 뒤 늦게 도착한 토큰이 세션을 되살리지 못하게 한다.
    private val stateLock = Any()
    private var generation = 0L

    /**
     * The stored session without any network call, or `null` when signed out or storage is unreadable.
     * It may be expired; call [session] to get a fresh one. The first access may read from disk.
     */
    public val currentSession: Session?
        get() = try { readSession() } catch (_: Exception) { null }

    /**
     * Builds the sign-in Intent (Custom Tabs or the default browser) and stores the pending request so the
     * callback can still be matched after the process restarts. A previous pending sign-in is replaced.
     *
     * @throws AltsCodexException with [Stage.Authorization] when discovery fails, or [Stage.Storage].
     */
    public suspend fun loginIntent(): Intent = withContext(io) {
        val configuration = try { gateway.configuration() } catch (error: CancellationException) { throw error } catch (_: Exception) {
            log(Stage.Authorization, "event" to "discovery", "result" to "error")
            throw AltsCodexException(Stage.Authorization)
        }
        // scope 를 넣지 않는다. AltsCodex 는 openid scope·id_token 을 지원하지 않는다.
        val request = AuthorizationRequest.Builder(configuration, config.clientId, ResponseTypeValues.CODE, Uri.parse(config.redirectUri)).build()
        check(request.codeVerifier != null && request.codeVerifierChallengeMethod == "S256")
        val replaced = try {
            val previous = store.get(KEY_PENDING) != null
            store.put(KEY_PENDING, PendingRequest(request.jsonSerializeString(), clock()).toJson())
            previous
        } catch (_: Exception) {
            log(Stage.Storage, "op" to "write_pending")
            throw AltsCodexException(Stage.Storage)
        }
        log(Stage.Authorization, "event" to "login_start", "replacedPending" to replaced.toString())
        try { gateway.authorizationIntent(request) } catch (_: Exception) {
            clearPendingQuietly()
            log(Stage.Authorization, "event" to "browser", "result" to "error")
            throw AltsCodexException(Stage.Authorization)
        }
    }

    /**
     * Completes sign-in with the `data` Intent from the activity result. Never returns silently:
     * a null result or a user cancellation is [AuthResult.Cancelled], a declined consent is [AuthResult.Denied].
     * A callback from an older sign-in returns [Stage.StaleCallback] and keeps the newer pending sign-in.
     */
    public suspend fun handleResult(data: Intent?): AuthResult = withContext(io) {
        callbackLock.withLock {
            try {
                completeSignIn(data)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                log(Stage.Authorization, "event" to "callback", "result" to "unexpected")
                AuthResult.Failed(Stage.Authorization)
            }
        }
    }

    private suspend fun completeSignIn(data: Intent?): AuthResult {
        val pending = try { store.get(KEY_PENDING)?.let(PendingRequest::fromJson) } catch (_: Exception) {
            log(Stage.Storage, "op" to "read_pending")
            return AuthResult.Failed(Stage.Storage)
        }
        log(Stage.Authorization, "event" to "callback", "data" to presence(data), "pending" to presence(pending))
        if (data == null) return finishWithoutSession(pending, AuthResult.Cancelled)
        if (pending == null) {
            log(Stage.NoPendingRequest)
            return AuthResult.Failed(Stage.NoPendingRequest)
        }
        AuthorizationException.fromIntent(data)?.let { error ->
            val result = when (error) {
                AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW -> AuthResult.Cancelled
                AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED -> AuthResult.Denied
                else -> AuthResult.Failed(Stage.Authorization)
            }
            log(Stage.Authorization, "error" to errorKind(error))
            return finishWithoutSession(pending, result)
        }
        val response = AuthorizationResponse.fromIntent(data)
            ?: return finishWithoutSession(pending, AuthResult.Failed(Stage.Authorization))
        val expected = AuthorizationRequest.jsonDeserialize(pending.request)
        if (!sameSecret(response.state, expected.state)) {
            log(Stage.StaleCallback)
            return AuthResult.Failed(Stage.StaleCallback)
        }
        val age = clock() - pending.startedAt
        if (age < 0 || age > config.pendingTtlMs) {
            log(Stage.Authorization, "event" to "pending_expired")
            return finishWithoutSession(pending, AuthResult.Expired)
        }
        val code = response.authorizationCode?.takeIf { it.isNotBlank() }
            ?: return finishWithoutSession(pending, AuthResult.Failed(Stage.Authorization))
        // 코드는 1회용이므로 교환 전에 대기 요청을 소비한다. 교환이 실패해도 같은 코드로 재시도하지 않는다.
        try { store.put(KEY_PENDING, null) } catch (_: Exception) {
            log(Stage.Storage, "op" to "clear_pending")
            return AuthResult.Failed(Stage.Storage)
        }
        val startedIn = currentGeneration()
        // 토큰 요청은 AppAuth 가 되돌려준 요청이 아니라 우리가 저장한 요청(엔드포인트·redirect·verifier)으로 만든다.
        val exchange = TokenRequest.Builder(expected.configuration, config.clientId)
            .setAuthorizationCode(code)
            .setRedirectUri(expected.redirectUri)
            .setCodeVerifier(expected.codeVerifier)
            .build()
        val payload = try { gateway.token(exchange) } catch (error: CancellationException) { throw error } catch (error: Exception) {
            val invalidGrant = (error as? TokenException)?.invalidGrant == true
            log(Stage.TokenExchange, "result" to if (invalidGrant) "invalid_grant" else "error")
            return AuthResult.Failed(if (invalidGrant) Stage.CodeExpiredOrUsed else Stage.TokenExchange)
        }
        log(Stage.TokenExchange, "result" to "ok")
        val session = validate(payload, previous = null) ?: return AuthResult.Failed(Stage.TokenValidation)
        val saved = try { commitIfCurrent(startedIn) { store.put(KEY_SESSION, session.toJson()) } } catch (_: Exception) {
            log(Stage.Storage, "op" to "write_session")
            return AuthResult.Failed(Stage.Storage)
        }
        if (!saved) {
            log(Stage.Authorization, "event" to "dropped_after_sign_out")
            return AuthResult.Cancelled
        }
        return AuthResult.Success(session)
    }

    /**
     * Returns a valid session, refreshing it first when the access token expires within a minute.
     * Concurrent callers share a single refresh. Returns `null` when signed out or when the server rejected
     * the refresh token (`invalid_grant`), in which case the stored session is removed.
     *
     * @throws AltsCodexException with [Stage.Refresh] on a network or server error (the session is kept,
     *   retry later), or [Stage.Storage] when storage is unreadable.
     */
    public suspend fun session(): Session? = withContext(io) {
        val current = readSessionOrThrow() ?: return@withContext null
        if (isFresh(current)) return@withContext current
        refreshLock.withLock {
            // 기다리는 동안 다른 호출이 이미 갱신했을 수 있으므로 잠금 안에서 다시 읽는다.
            val latest = readSessionOrThrow() ?: return@withLock null
            if (isFresh(latest)) latest else refresh(latest)
        }
    }

    private suspend fun refresh(current: Session): Session? {
        val startedIn = currentGeneration()
        val payload = try {
            val request = TokenRequest.Builder(gateway.configuration(), config.clientId)
                .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setRefreshToken(current.refreshToken)
                .build()
            gateway.token(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if ((error as? TokenException)?.invalidGrant != true) {
                log(Stage.Refresh, "result" to "error")
                throw AltsCodexException(Stage.Refresh)
            }
            // 만료·폐기·이미 회전된 refresh token. 다시 쓸 수 없으므로 세션을 지운다.
            log(Stage.Refresh, "result" to "invalid_grant")
            clearSessionIfCurrent(startedIn)
            return null
        }
        // 서버는 이미 refresh token 을 회전했으므로 검증에 실패한 응답이면 이전 세션도 쓸 수 없다.
        val next = validate(payload, previous = current)
        val committed = try {
            commitIfCurrent(startedIn) { store.put(KEY_SESSION, next?.toJson()) }
        } catch (_: Exception) {
            log(Stage.Storage, "op" to "write_session")
            throw AltsCodexException(Stage.Storage)
        }
        if (!committed) {
            log(Stage.Refresh, "result" to "dropped_after_sign_out")
            return null
        }
        log(Stage.Refresh, "result" to if (next != null) "ok" else "rejected")
        return next
    }

    /**
     * Signs out. The local session and any pending sign-in are removed first, without waiting for the network,
     * then the refresh token is revoked on the server. The browser's AltsCodex session is not affected.
     */
    public suspend fun signOut(): SignOutResult = withContext(io) {
        val current = try { readSession() } catch (_: Exception) { null }
        val localCleared = synchronized(stateLock) {
            generation++
            try {
                store.put(KEY_SESSION, null)
                store.put(KEY_PENDING, null)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!localCleared) log(Stage.Storage, "op" to "clear_session")
        if (current == null) return@withContext SignOutResult(localCleared, serverRevoked = true)
        val revoked = try { gateway.revoke(current.refreshToken) } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
        log(Stage.Revocation, "result" to if (revoked) "ok" else "error")
        SignOutResult(localCleared, revoked)
    }

    private fun validate(payload: TokenPayload, previous: Session?): Session? {
        fun reject(reason: String, idType: String = "unchecked"): Session? {
            log(Stage.TokenValidation, "result" to "rejected", "reason" to reason, "idType" to idType)
            return null
        }
        val access = payload.accessToken?.takeIf { it.isNotBlank() } ?: return reject("access_missing")
        val refresh = payload.refreshToken?.takeIf { it.isNotBlank() } ?: return reject("refresh_missing")
        if (!"Bearer".equals(payload.tokenType, ignoreCase = true)) return reject("token_type")
        val expiresAt = payload.expiresAt ?: return reject("expiry_missing")
        if (expiresAt <= clock()) return reject("expiry_past")
        val slot = when (val parsed = SlotParser.parse(payload.slotJson, config.maxSlotIdLength)) {
            is SlotParse.Ok -> parsed.slot
            is SlotParse.Invalid -> return reject(parsed.reason, parsed.idType)
        }
        if (previous != null && previous.slot.id != slot.id) return reject("slot_changed")
        log(Stage.TokenValidation, "result" to "ok")
        return Session(access, refresh, expiresAt, slot)
    }

    private fun isFresh(session: Session) = session.expiresAt - REFRESH_MARGIN_MS > clock()

    private fun readSession(): Session? = store.get(KEY_SESSION)?.let(::sessionFromJson)

    private fun readSessionOrThrow(): Session? = try { readSession() } catch (_: Exception) {
        log(Stage.Storage, "op" to "read_session")
        throw AltsCodexException(Stage.Storage)
    }

    private fun currentGeneration(): Long = synchronized(stateLock) { generation }

    private fun commitIfCurrent(startedIn: Long, write: () -> Unit): Boolean = synchronized(stateLock) {
        if (startedIn != generation) return false
        write()
        true
    }

    private fun clearSessionIfCurrent(startedIn: Long) {
        try { commitIfCurrent(startedIn) { store.put(KEY_SESSION, null) } } catch (_: Exception) {
            log(Stage.Storage, "op" to "clear_session")
        }
    }

    private fun finishWithoutSession(pending: PendingRequest?, result: AuthResult): AuthResult {
        if (pending == null) return result
        return try {
            store.put(KEY_PENDING, null)
            result
        } catch (_: Exception) {
            log(Stage.Storage, "op" to "clear_pending")
            AuthResult.Failed(Stage.Storage)
        }
    }

    private fun clearPendingQuietly() {
        try { store.put(KEY_PENDING, null) } catch (_: Exception) { log(Stage.Storage, "op" to "clear_pending") }
    }

    // 로거에는 고정 어휘만 넘긴다. 토큰·코드·state·verifier·사용자 값은 어떤 경로로도 들어가지 않는다.
    private fun log(stage: Stage, vararg values: Pair<String, String>) {
        val logger = config.logger ?: return
        try { logger(stage, values.toMap()) } catch (_: Exception) { }
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_PENDING = "pending"
        const val REFRESH_MARGIN_MS = 60_000L

        fun presence(value: Any?) = if (value != null) "present" else "absent"

        fun errorKind(error: AuthorizationException): String = when (error) {
            AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW -> "user_canceled"
            AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED -> "access_denied"
            else -> when (error.type) {
                AuthorizationException.TYPE_GENERAL_ERROR -> "general"
                AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR -> "oauth"
                else -> "other"
            }
        }

        fun sameSecret(a: String?, b: String?): Boolean =
            a != null && b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
