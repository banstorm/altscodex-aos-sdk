// session() 의 무네트워크 반환·단일 refresh·invalid_grant·네트워크 오류·slot 변경·로그아웃 경합 테스트.
package com.altscodex.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.openid.appauth.GrantTypeValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionTest {
    private fun TestScope.harness() = Harness(StandardTestDispatcher(testScheduler))

    // 만료 30초 전이면 60초 여유 기준에 걸려 refresh 대상이다.
    private val nearExpiry = NOW + 30_000

    @Test fun signedOutReturnsNullWithoutNetwork() = runTest {
        val h = harness()
        assertNull(h.auth.session())
        assertEquals(0, h.gateway.tokenRequests.size)
    }

    @Test fun validSessionIsReturnedWithoutNetwork() = runTest {
        val h = harness()
        h.saveSession(storedSession("old"))
        assertEquals(storedSession("old"), h.auth.session())
        assertEquals(0, h.gateway.tokenRequests.size)
    }

    @Test fun nearExpiryRefreshesAndPersistsRotatedPair() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", nearExpiry))
        val next = h.auth.session()!!
        assertEquals("next-REFRESH-SECRET", next.refreshToken)
        assertEquals(next, sessionFromJson(h.store.values.getValue("session")))
        val request = h.gateway.tokenRequests.single()
        assertEquals(GrantTypeValues.REFRESH_TOKEN, request.grantType)
        assertEquals("old-REFRESH-SECRET", request.refreshToken)
    }

    @Test fun tenConcurrentCallersShareOneRefresh() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", nearExpiry))
        val barrier = CompletableDeferred<Unit>()
        h.gateway.onToken = { barrier.await(); payload("next") }
        val calls = List(10) { async { h.auth.session() } }
        runCurrent()
        assertEquals(1, h.gateway.refreshCalls)
        barrier.complete(Unit)
        val results = calls.awaitAll()
        assertEquals(1, h.gateway.refreshCalls)
        results.forEach { assertEquals("next-REFRESH-SECRET", it!!.refreshToken) }
    }

    @Test fun invalidGrantClearsSessionAndReturnsNull() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", nearExpiry))
        h.gateway.onToken = { throw TokenException(invalidGrant = true) }
        assertNull(h.auth.session())
        assertNull(h.store.values["session"])
    }

    @Test fun networkErrorKeepsSessionAndPropagates() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", nearExpiry))
        h.gateway.onToken = { throw TokenException(invalidGrant = false) }
        try { h.auth.session(); fail() } catch (error: AltsCodexException) { assertEquals(Stage.Refresh, error.stage) }
        assertEquals(storedSession("old", nearExpiry), h.auth.currentSession)
        h.gateway.onToken = { payload("next") }
        assertEquals("next-REFRESH-SECRET", h.auth.session()!!.refreshToken)
    }

    @Test fun discoveryFailureDuringRefreshKeepsSession() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", nearExpiry))
        h.gateway.discoveryFails = true
        try { h.auth.session(); fail() } catch (error: AltsCodexException) { assertEquals(Stage.Refresh, error.stage) }
        assertNotNull(h.store.values["session"])
    }

    @Test fun refreshWithDifferentSlotIdIsRejected() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", nearExpiry, slotId = "42"))
        h.gateway.onToken = { payload("next", slot = slotJson(id = 43)) }
        assertNull(h.auth.session())
        assertNull(h.store.values["session"])
    }

    @Test fun refreshWithSameIdAsStringIsAccepted() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", nearExpiry, slotId = "42"))
        h.gateway.onToken = { payload("next", slot = slotJson(id = "42")) }
        assertEquals("42", h.auth.session()!!.slot.id)
    }

    @Test fun lateRefreshAfterSignOutIsDropped() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", nearExpiry))
        val barrier = CompletableDeferred<Unit>()
        h.gateway.onToken = { barrier.await(); payload("late") }
        val pending = async { h.auth.session() }
        runCurrent()
        h.auth.signOut()
        assertNull(h.store.values["session"])
        barrier.complete(Unit)
        assertNull(pending.await())
        assertNull(h.store.values["session"])
        assertNull(h.auth.currentSession)
        assertEquals(listOf("old-REFRESH-SECRET"), h.gateway.revoked)
    }

    @Test fun currentSessionReturnsStoredValueEvenWhenExpired() = runTest {
        val h = harness()
        h.saveSession(storedSession("old", NOW - 1))
        assertEquals(storedSession("old", NOW - 1), h.auth.currentSession)
        assertEquals(0, h.gateway.tokenRequests.size)
    }

    @Test fun unreadableStorageThrowsStorage() = runTest {
        val h = harness()
        h.saveSession(storedSession("old"))
        h.store.failReads = true
        assertNull(h.auth.currentSession)
        try { h.auth.session(); fail() } catch (error: AltsCodexException) { assertEquals(Stage.Storage, error.stage) }
    }
}
