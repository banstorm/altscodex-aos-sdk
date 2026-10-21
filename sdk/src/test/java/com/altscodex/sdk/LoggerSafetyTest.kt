// 로거로 나가는 모든 키·값에 토큰·코드·state·verifier·닉네임이 섞이지 않음을 고정하는 테스트.
package com.altscodex.sdk

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoggerSafetyTest {
    @Test fun loggerNeverReceivesSecrets() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        val nickname = "NICKNAME-SECRET"
        val secrets = mutableListOf(nickname, "CODE-SECRET-9")
        h.gateway.onToken = { payload("first", slot = slotJson(nickname = nickname)) }

        // 성공 로그인
        h.auth.loginIntent()
        val request = h.gateway.lastAuthorization!!
        secrets += listOf(request.state!!, request.codeVerifier!!, request.codeVerifierChallenge!!)
        val success = AuthorizationResponse.Builder(request).setState(request.state).setAuthorizationCode("CODE-SECRET-9").build().toIntent()
        assertTrue(h.auth.handleResult(success) is AuthResult.Success)
        secrets += listOf("first-ACCESS-SECRET", "first-REFRESH-SECRET")

        // stale 콜백과 취소
        h.auth.loginIntent()
        val second = h.gateway.lastAuthorization!!
        secrets += listOf(second.state!!, second.codeVerifier!!)
        h.auth.handleResult(success)
        h.auth.handleResult(AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.toIntent())

        // refresh 성공, 검증 실패, 네트워크 실패, invalid_grant
        h.now += 2 * HOUR
        h.gateway.onToken = { payload("second", expiresAt = h.now + HOUR, slot = slotJson(nickname = nickname)) }
        h.auth.session()
        secrets += listOf("second-ACCESS-SECRET", "second-REFRESH-SECRET")
        h.now += 2 * HOUR
        h.gateway.onToken = { throw TokenException(invalidGrant = false) }
        runCatching { h.auth.session() }
        h.gateway.onToken = { payload("third", tokenType = "mac") }
        h.auth.session()
        secrets += listOf("third-ACCESS-SECRET", "third-REFRESH-SECRET")

        // 로그아웃(revoke 실패)
        h.saveSession(storedSession("fourth"))
        secrets += listOf("fourth-ACCESS-SECRET", "fourth-REFRESH-SECRET")
        h.gateway.onRevoke = { false }
        h.auth.signOut()

        assertTrue(h.logs.size > 10)
        val logged = h.logs.flatMap { (_, values) -> values.keys + values.values }
        for (secret in secrets) {
            for (text in logged) assertFalse("logged secret fragment: $text", text.contains(secret) || secret.contains(text) && text.length >= 8)
        }
    }

    @Test fun throwingLoggerDoesNotBreakSignIn() = runTest {
        val store = InMemorySecureStore()
        val gateway = FakeGateway()
        val auth = AltsCodexAuth(
            AltsCodexConfig(CLIENT_ID, REDIRECT, store = store, logger = { _, _ -> throw IllegalStateException("boom") }),
            store, gateway, { NOW }, StandardTestDispatcher(testScheduler),
        )
        auth.loginIntent()
        val request = gateway.lastAuthorization!!
        val intent = AuthorizationResponse.Builder(request).setState(request.state).setAuthorizationCode("c").build().toIntent()
        assertTrue(auth.handleResult(intent) is AuthResult.Success)
    }
}
