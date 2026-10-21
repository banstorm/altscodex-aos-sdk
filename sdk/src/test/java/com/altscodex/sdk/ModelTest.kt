// 설정 검증·Session 토큰 가림·예외 메시지·revoke 본문 형식 테스트.
package com.altscodex.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelTest {
    @Test fun configDefaults() {
        val config = AltsCodexConfig(CLIENT_ID, REDIRECT)
        assertEquals("https://api.altscodex.com", config.issuer)
        assertEquals(600_000L, config.pendingTtlMs)
        assertEquals(512, config.maxSlotIdLength)
    }

    @Test fun configRejectsInvalidValues() {
        val invalid = listOf<() -> AltsCodexConfig>(
            { AltsCodexConfig(" ", REDIRECT) },
            { AltsCodexConfig(CLIENT_ID, "callback") },
            { AltsCodexConfig(CLIENT_ID, "com.example.app:/callback#x") },
            { AltsCodexConfig(CLIENT_ID, REDIRECT, issuer = "http://api.altscodex.com") },
            { AltsCodexConfig(CLIENT_ID, REDIRECT, pendingTtlMs = 0) },
            { AltsCodexConfig(CLIENT_ID, REDIRECT, maxSlotIdLength = 0) },
        )
        for (build in invalid) try { build(); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun sessionToStringRedactsTokens() {
        val text = storedSession("x").toString()
        assertFalse(text.contains("ACCESS-SECRET"))
        assertFalse(text.contains("REFRESH-SECRET"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test fun sessionJsonRoundTrip() {
        val session = Session("a", "r", NOW, Slot("9007199254740993", "N", "0xabc", 5))
        assertEquals(session, sessionFromJson(session.toJson()))
        val noAddress = session.copy(slot = session.slot.copy(contentAddress = null))
        assertEquals(noAddress, sessionFromJson(noAddress.toJson()))
    }

    @Test fun exceptionMessageCarriesOnlyStage() {
        assertEquals("AltsCodex operation failed at stage Refresh", AltsCodexException(Stage.Refresh).message)
    }

    @Test fun revokeBodyIsFormEncoded() {
        assertEquals("token=a%2Bb%3D&token_type_hint=refresh_token&client_id=c+1", Revoker.formBody("c 1", "a+b="))
    }
}
