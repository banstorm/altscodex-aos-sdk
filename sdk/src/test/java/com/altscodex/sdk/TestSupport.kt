// 단위 테스트 공용 가짜 TokenGateway·토큰 응답·SDK 생성 도우미.
package com.altscodex.sdk

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.TokenRequest
import org.json.JSONObject

const val NOW = 1_700_000_000_000L
const val HOUR = 3_600_000L
const val CLIENT_ID = "client-123"
const val REDIRECT = "com.example.app:/callback"

internal class FakeGateway : TokenGateway {
    val config = AuthorizationServiceConfiguration(
        Uri.parse("https://www.altscodex.com/oauth/login"),
        Uri.parse("https://api.altscodex.com/v1/oauth2/token"),
    )
    var lastAuthorization: AuthorizationRequest? = null
    val tokenRequests = mutableListOf<TokenRequest>()
    val revoked = mutableListOf<String>()
    var onToken: suspend (TokenRequest) -> TokenPayload = { payload("next") }
    var onRevoke: suspend () -> Boolean = { true }
    var discoveryFails = false

    val refreshCalls get() = tokenRequests.count { it.grantType == GrantTypeValues.REFRESH_TOKEN }

    override suspend fun configuration(): AuthorizationServiceConfiguration {
        if (discoveryFails) throw java.io.IOException("offline")
        return config
    }

    override fun authorizationIntent(request: AuthorizationRequest): Intent {
        lastAuthorization = request
        return Intent("test.AUTHORIZE")
    }

    override suspend fun token(request: TokenRequest): TokenPayload {
        tokenRequests += request
        return onToken(request)
    }

    override suspend fun revoke(refreshToken: String): Boolean {
        revoked += refreshToken
        return onRevoke()
    }
}

fun slotJson(id: Any? = 42, nickname: Any? = "Nick", address: Any? = JSONObject.NULL, transfers: Any? = 3): String =
    JSONObject().put("id", id ?: JSONObject.NULL).put("token_nickname", nickname ?: JSONObject.NULL)
        .put("content_address", address).put("tr_cnt", transfers ?: JSONObject.NULL).toString()

internal fun payload(
    prefix: String,
    access: String? = "$prefix-ACCESS-SECRET",
    refresh: String? = "$prefix-REFRESH-SECRET",
    tokenType: String? = "Bearer",
    expiresAt: Long? = NOW + HOUR,
    slot: String? = slotJson(),
) = TokenPayload(access, refresh, tokenType, expiresAt, slot)

fun storedSession(prefix: String, expiresAt: Long = NOW + HOUR, slotId: String = "42") =
    Session("$prefix-ACCESS-SECRET", "$prefix-REFRESH-SECRET", expiresAt, Slot(slotId, "Nick", null, 3))

internal class Harness(
    dispatcher: CoroutineDispatcher,
    val store: InMemorySecureStore = InMemorySecureStore(),
    val gateway: FakeGateway = FakeGateway(),
    val logs: MutableList<Pair<Stage, Map<String, String>>> = mutableListOf(),
    ttl: Long = AltsCodexConfig.DEFAULT_PENDING_TTL_MS,
) {
    var now = NOW
    val auth = AltsCodexAuth(
        AltsCodexConfig(CLIENT_ID, REDIRECT, pendingTtlMs = ttl, store = store, logger = { stage, values -> logs += stage to values }),
        store, gateway, { now }, dispatcher,
    )

    fun saveSession(session: Session) = store.put("session", session.toJson())
}
