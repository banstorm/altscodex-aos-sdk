// 실제 Android org.json(Robolectric)으로 slot JSON 과 AppAuth TokenResponse 경로를 검증한다.
package com.altscodex.sdk

import android.net.Uri
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SlotJsonTest {
    private fun parse(raw: String?) = SlotParser.parse(raw, 512)

    private fun invalid(raw: String?) = parse(raw) as SlotParse.Invalid

    @Test fun parsesFullSlot() {
        val slot = (parse("""{"id":42,"token_nickname":"Nick","content_address":"0xabc","tr_cnt":7}""") as SlotParse.Ok).slot
        assertEquals(Slot("42", "Nick", "0xabc", 7), slot)
    }

    @Test fun numberAndStringIdAreTheSame() {
        val number = (parse("""{"id":42,"token_nickname":"N"}""") as SlotParse.Ok).slot
        val text = (parse("""{"id":"42","token_nickname":"N"}""") as SlotParse.Ok).slot
        assertEquals(number.id, text.id)
    }

    @Test fun jsonIdAboveDoublePrecisionIsLossless() {
        val slot = (parse("""{"id":9007199254740993,"token_nickname":"N"}""") as SlotParse.Ok).slot
        assertEquals("9007199254740993", slot.id)
    }

    @Test fun rejectsBadIdTypesWithTypeName() {
        assertEquals("number", invalid("""{"id":42.0,"token_nickname":"N"}""").idType)
        assertEquals("boolean", invalid("""{"id":true,"token_nickname":"N"}""").idType)
        assertEquals("object", invalid("""{"id":{"v":1},"token_nickname":"N"}""").idType)
        assertEquals("null", invalid("""{"id":null,"token_nickname":"N"}""").idType)
        assertEquals("null", invalid("""{"token_nickname":"N"}""").idType)
        assertEquals("integer", invalid("""{"id":-5,"token_nickname":"N"}""").idType)
        // unsigned bigint 상한(2^64-1)은 Long 을 넘어 Double 이 되므로 손실 위험으로 거부한다.
        assertEquals("number", invalid("""{"id":18446744073709551615,"token_nickname":"N"}""").idType)
    }

    @Test fun rejectsBadFieldTypes() {
        assertEquals("nickname", invalid("""{"id":1,"token_nickname":7}""").reason)
        assertEquals("nickname", invalid("""{"id":1}""").reason)
        assertEquals("content_address", invalid("""{"id":1,"token_nickname":"N","content_address":5}""").reason)
        assertEquals("transfer_count", invalid("""{"id":1,"token_nickname":"N","tr_cnt":"3"}""").reason)
        assertEquals("slot_json", invalid("not json").reason)
        assertEquals("slot_json", invalid("[1]").reason)
        assertEquals("slot_missing", invalid(null).reason)
    }

    @Test fun ignoresUnknownLeaseObject() {
        val none = parse("""{"id":42,"token_nickname":"N","content_address":"0xabc","tr_cnt":1,"lease":{"state":"none","expires_at":null}}""")
        assertEquals(Slot("42", "N", "0xabc", 1), (none as SlotParse.Ok).slot)
        val borrowed = parse("""{"id":42,"token_nickname":"N","lease":{"state":"borrowed","expires_at":"2026-11-30T00:00:00Z"}}""")
        assertEquals(Slot("42", "N", null, 0), (borrowed as SlotParse.Ok).slot)
    }

    @Test fun optionalFieldsDefault() {
        val slot = (parse("""{"id":1,"token_nickname":"N","content_address":null}""") as SlotParse.Ok).slot
        assertNull(slot.contentAddress)
        assertEquals(0L, slot.transferCount)
    }

    @Test fun appAuthTokenResponseKeepsSlotAsLosslessJsonString() {
        val config = AuthorizationServiceConfiguration(Uri.parse("https://a.example/auth"), Uri.parse("https://a.example/token"))
        val request = TokenRequest.Builder(config, CLIENT_ID).setGrantType(GrantTypeValues.REFRESH_TOKEN).setRefreshToken("r").build()
        val response = TokenResponse.Builder(request).fromResponseJsonString(
            """{"access_token":"a","token_type":"Bearer","expires_in":86400,"refresh_token":"r2",
               "slot":{"id":9007199254740993,"token_nickname":"Nick","content_address":null,"tr_cnt":2}}""",
        ).build()
        val payload = TokenPayload.from(response)
        assertEquals("a", payload.accessToken)
        assertEquals("r2", payload.refreshToken)
        assertTrue(payload.expiresAt!! > System.currentTimeMillis())
        val slot = (parse(payload.slotJson) as SlotParse.Ok).slot
        assertEquals(Slot("9007199254740993", "Nick", null, 2), slot)
    }
}
