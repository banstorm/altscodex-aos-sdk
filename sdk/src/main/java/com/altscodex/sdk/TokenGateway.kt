// AppAuth 네트워크 호출(디스커버리·토큰 요청·폐기)을 감싸 테스트에서 가짜로 바꿀 수 있게 하는 경계.
package com.altscodex.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import net.openid.appauth.connectivity.ConnectionBuilder
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 토큰 응답에서 검증에 필요한 값만 옮긴 것. 검증 전 값이므로 어디에도 기록하지 않는다. */
internal class TokenPayload(
    val accessToken: String?,
    val refreshToken: String?,
    val tokenType: String?,
    val expiresAt: Long?,
    val slotJson: String?,
) {
    companion object {
        // AppAuth 는 표준 외 필드를 additionalParameters 에 JSON 문자열로 넣는다(slot 객체 → 문자열).
        fun from(response: TokenResponse): TokenPayload = TokenPayload(
            response.accessToken,
            response.refreshToken,
            response.tokenType,
            response.accessTokenExpirationTime,
            response.additionalParameters["slot"],
        )
    }
}

/** [invalidGrant] 는 서버가 OAuth 오류 invalid_grant 를 돌려준 경우만 true. 그 밖(네트워크·서버 오류)은 false. */
internal class TokenException(val invalidGrant: Boolean) : Exception()

internal interface TokenGateway {
    suspend fun configuration(): AuthorizationServiceConfiguration
    fun authorizationIntent(request: AuthorizationRequest): Intent
    suspend fun token(request: TokenRequest): TokenPayload
    suspend fun revoke(refreshToken: String): Boolean
}

internal object HttpsConnections : ConnectionBuilder {
    override fun openConnection(uri: Uri): HttpURLConnection {
        require(uri.scheme == "https")
        return (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
        }
    }
}

internal class AppAuthGateway(private val context: Context, private val config: AltsCodexConfig) : TokenGateway {
    private val service by lazy {
        AuthorizationService(context, AppAuthConfiguration.Builder().setConnectionBuilder(HttpsConnections).build())
    }
    private val discoveryLock = Mutex()
    @Volatile private var cached: AuthorizationServiceConfiguration? = null

    override suspend fun configuration(): AuthorizationServiceConfiguration =
        cached ?: discoveryLock.withLock { cached ?: discover().also { cached = it } }

    private suspend fun discover(): AuthorizationServiceConfiguration {
        val discovered = suspendCancellableCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.issuer), { result, error ->
                if (result != null) continuation.resume(result)
                else continuation.resumeWithException(error ?: IOException("discovery failed"))
            }, HttpsConnections)
        }
        // 다른 issuer 의 문서가 돌아오면(설정 오류·중간자) 그 엔드포인트로 코드를 보내지 않는다.
        if (discovered.discoveryDoc?.issuer != config.issuer) throw IOException("issuer mismatch")
        return discovered
    }

    override fun authorizationIntent(request: AuthorizationRequest): Intent =
        service.getAuthorizationRequestIntent(request)

    override suspend fun token(request: TokenRequest): TokenPayload = suspendCancellableCoroutine { continuation ->
        service.performTokenRequest(request) { response, error ->
            if (response != null) continuation.resume(TokenPayload.from(response))
            else continuation.resumeWithException(TokenException(
                error?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR && error.error == "invalid_grant"))
        }
    }

    override suspend fun revoke(refreshToken: String): Boolean {
        val endpoint = configuration().discoveryDoc?.docJson?.optString("revocation_endpoint").orEmpty()
        return Revoker.revoke(endpoint, config.clientId, refreshToken)
    }
}
