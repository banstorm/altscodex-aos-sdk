// loginIntent·handleResult 분기(취소·거부·stale·TTL·교환 오류·응답 검증·저장 실패) 테스트.
package com.altscodex.sdk

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.GrantTypeValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HandleResultTest {
    private fun TestScope.harness(ttl: Long = AltsCodexConfig.DEFAULT_PENDING_TTL_MS) =
        Harness(StandardTestDispatcher(testScheduler), ttl = ttl)

    private suspend fun Harness.begin(): AuthorizationRequest {
        auth.loginIntent()
        return checkNotNull(gateway.lastAuthorization)
    }

    private fun callback(request: AuthorizationRequest, code: String = "CODE-SECRET-1") =
        AuthorizationResponse.Builder(request).setState(request.state).setAuthorizationCode(code).build().toIntent()

    @Test fun loginIntentStoresPendingPkceRequestWithoutScope() = runTest {
        val h = harness()
        val request = h.begin()
        assertEquals(CLIENT_ID, request.clientId)
        assertEquals(REDIRECT, request.redirectUri.toString())
        assertEquals("S256", request.codeVerifierChallengeMethod)
        assertNotNull(request.codeVerifier)
        assertNull(request.scope)
        val pending = PendingRequest.fromJson(h.store.values.getValue("pending"))
        assertEquals(NOW, pending.startedAt)
        assertEquals(request.state, AuthorizationRequest.jsonDeserialize(pending.request).state)
    }

    @Test fun loginIntentDiscoveryFailureThrowsAuthorizationStage() = runTest {
        val h = harness()
        h.gateway.discoveryFails = true
        try { h.auth.loginIntent(); fail() } catch (error: AltsCodexException) { assertEquals(Stage.Authorization, error.stage) }
        assertNull(h.store.values["pending"])
    }

    @Test fun successExchangesWithStoredVerifierAndPersistsSession() = runTest {
        val h = harness()
        val request = h.begin()
        val result = h.auth.handleResult(callback(request))
        val session = (result as AuthResult.Success).session
        assertEquals("next-ACCESS-SECRET", session.accessToken)
        assertEquals(Slot("42", "Nick", null, 3), session.slot)
        assertEquals(session, sessionFromJson(h.store.values.getValue("session")))
        assertNull(h.store.values["pending"])
        val exchange = h.gateway.tokenRequests.single()
        assertEquals(GrantTypeValues.AUTHORIZATION_CODE, exchange.grantType)
        assertEquals("CODE-SECRET-1", exchange.authorizationCode)
        assertEquals(request.codeVerifier, exchange.codeVerifier)
        assertEquals(REDIRECT, exchange.redirectUri.toString())
        assertEquals(session, h.auth.currentSession)
    }

    @Test fun nullResultIsCancelledAndClearsPending() = runTest {
        val h = harness()
        h.begin()
        assertEquals(AuthResult.Cancelled, h.auth.handleResult(null))
        assertNull(h.store.values["pending"])
    }

    @Test fun nullResultWithoutPendingIsCancelled() = runTest {
        assertEquals(AuthResult.Cancelled, harness().auth.handleResult(null))
    }

    @Test fun userCancelledIsCancelled() = runTest {
        val h = harness()
        h.begin()
        val result = h.auth.handleResult(AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.toIntent())
        assertEquals(AuthResult.Cancelled, result)
        assertNull(h.store.values["pending"])
    }

    @Test fun accessDeniedIsDenied() = runTest {
        val h = harness()
        h.begin()
        assertEquals(AuthResult.Denied, h.auth.handleResult(AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED.toIntent()))
        assertNull(h.store.values["pending"])
    }

    @Test fun otherAuthorizationErrorIsFailedAuthorization() = runTest {
        val h = harness()
        h.begin()
        val result = h.auth.handleResult(AuthorizationException.GeneralErrors.NETWORK_ERROR.toIntent())
        assertEquals(AuthResult.Failed(Stage.Authorization), result)
    }

    @Test fun callbackWithoutPendingIsNoPendingRequest() = runTest {
        val h = harness()
        val request = h.begin()
        h.store.values.remove("pending")
        assertEquals(AuthResult.Failed(Stage.NoPendingRequest), h.auth.handleResult(callback(request)))
        assertTrue(h.gateway.tokenRequests.isEmpty())
    }

    @Test fun staleCallbackKeepsNewerPendingRequest() = runTest {
        val h = harness()
        val older = h.begin()
        val newer = h.begin()
        val pendingBefore = h.store.values["pending"]
        assertEquals(AuthResult.Failed(Stage.StaleCallback), h.auth.handleResult(callback(older)))
        assertEquals(pendingBefore, h.store.values["pending"])
        assertTrue(h.gateway.tokenRequests.isEmpty())
        assertTrue(h.auth.handleResult(callback(newer)) is AuthResult.Success)
    }

    @Test fun pendingOlderThanTtlIsExpiredAndRemoved() = runTest {
        val h = harness(ttl = 1_000)
        val request = h.begin()
        h.now += 1_001
        assertEquals(AuthResult.Expired, h.auth.handleResult(callback(request)))
        assertNull(h.store.values["pending"])
        assertTrue(h.gateway.tokenRequests.isEmpty())
    }

    @Test fun invalidGrantOnExchangeIsCodeExpiredOrUsed() = runTest {
        val h = harness()
        val request = h.begin()
        h.gateway.onToken = { throw TokenException(invalidGrant = true) }
        assertEquals(AuthResult.Failed(Stage.CodeExpiredOrUsed), h.auth.handleResult(callback(request)))
        assertNull(h.store.values["pending"])
        assertNull(h.store.values["session"])
    }

    @Test fun networkErrorOnExchangeIsTokenExchange() = runTest {
        val h = harness()
        val request = h.begin()
        h.gateway.onToken = { throw TokenException(invalidGrant = false) }
        assertEquals(AuthResult.Failed(Stage.TokenExchange), h.auth.handleResult(callback(request)))
    }

    @Test fun invalidTokenResponsesAreTokenValidation() = runTest {
        val cases = mapOf(
            "access missing" to payload("x", access = null),
            "access blank" to payload("x", access = " "),
            "refresh missing" to payload("x", refresh = null),
            "token type mismatch" to payload("x", tokenType = "mac"),
            "token type missing" to payload("x", tokenType = null),
            "expiry missing" to payload("x", expiresAt = null),
            "expiry in past" to payload("x", expiresAt = NOW - 1),
            "expiry now" to payload("x", expiresAt = NOW),
            "slot missing" to payload("x", slot = null),
            "slot not json" to payload("x", slot = "slot"),
            "slot id not a number" to payload("x", slot = slotJson(id = "abc")),
            // org.json 은 put(42.0) 을 "42" 로 직렬화하므로 서버 원문 그대로 넣는다.
            "slot id double" to payload("x", slot = """{"id":42.0,"token_nickname":"Nick"}"""),
            "nickname number" to payload("x", slot = slotJson(nickname = 7)),
            "nickname null" to payload("x", slot = slotJson(nickname = null)),
        )
        for ((name, response) in cases) {
            val h = harness()
            val request = h.begin()
            h.gateway.onToken = { response }
            assertEquals(name, AuthResult.Failed(Stage.TokenValidation), h.auth.handleResult(callback(request)))
            assertNull(name, h.store.values["session"])
        }
    }

    @Test fun lowercaseBearerIsAccepted() = runTest {
        val h = harness()
        val request = h.begin()
        h.gateway.onToken = { payload("x", tokenType = "bearer") }
        assertTrue(h.auth.handleResult(callback(request)) is AuthResult.Success)
    }

    @Test fun sessionWriteFailureIsStorage() = runTest {
        val h = harness()
        val request = h.begin()
        h.gateway.onToken = { h.store.failWrites = true; payload("x") }
        assertEquals(AuthResult.Failed(Stage.Storage), h.auth.handleResult(callback(request)))
    }

    @Test fun signOutDuringExchangeDropsLateTokens() = runTest {
        val h = harness()
        val request = h.begin()
        h.gateway.onToken = { h.auth.signOut(); payload("late") }
        assertEquals(AuthResult.Cancelled, h.auth.handleResult(callback(request)))
        assertNull(h.store.values["session"])
    }
}
