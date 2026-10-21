// signOut 의 로컬 우선 삭제·revoke 결과 보고 테스트.
package com.altscodex.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SignOutTest {
    private fun TestScope.harness() = Harness(StandardTestDispatcher(testScheduler))

    @Test fun clearsLocallyBeforeRevocationFinishes() = runTest {
        val h = harness()
        h.saveSession(storedSession("old"))
        h.store.put("pending", PendingRequest("{}", NOW).toJson())
        val barrier = CompletableDeferred<Boolean>()
        h.gateway.onRevoke = { barrier.await() }
        val result = async { h.auth.signOut() }
        runCurrent()
        assertTrue(h.store.values.isEmpty())
        assertNull(h.auth.currentSession)
        barrier.complete(true)
        assertEquals(SignOutResult(localCleared = true, serverRevoked = true), result.await())
        assertEquals(listOf("old-REFRESH-SECRET"), h.gateway.revoked)
    }

    @Test fun revocationFailureReportsServerNotRevoked() = runTest {
        val h = harness()
        h.saveSession(storedSession("old"))
        h.gateway.onRevoke = { false }
        assertEquals(SignOutResult(localCleared = true, serverRevoked = false), h.auth.signOut())
        assertNull(h.store.values["session"])
    }

    @Test fun revocationExceptionReportsServerNotRevoked() = runTest {
        val h = harness()
        h.saveSession(storedSession("old"))
        h.gateway.onRevoke = { throw java.io.IOException("offline") }
        assertEquals(SignOutResult(localCleared = true, serverRevoked = false), h.auth.signOut())
    }

    @Test fun signedOutHasNothingToRevoke() = runTest {
        val h = harness()
        assertEquals(SignOutResult(localCleared = true, serverRevoked = true), h.auth.signOut())
        assertTrue(h.gateway.revoked.isEmpty())
    }

    @Test fun storageFailureReportsLocalNotCleared() = runTest {
        val h = harness()
        h.saveSession(storedSession("old"))
        h.store.failWrites = true
        assertEquals(SignOutResult(localCleared = false, serverRevoked = true), h.auth.signOut())
    }
}
