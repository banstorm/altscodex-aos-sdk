// 토큰 응답의 slot JSON 을 타입 검증하며 Slot 으로 변환한다(임의 값을 문자열로 강제 변환하지 않음).
package com.altscodex.sdk

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface SlotParse {
    data class Ok(val slot: Slot) : SlotParse

    /** [reason] 과 [idType] 은 로거로 그대로 나가므로 고정 어휘만 담는다. */
    data class Invalid(val reason: String, val idType: String = "unchecked") : SlotParse
}

internal object SlotParser {
    // 선행 0 을 막아 "042" 와 "42" 가 서로 다른 ID 로 저장되지 않게 한다.
    private val DECIMAL = Regex("0|[1-9][0-9]*")

    /**
     * JSON 숫자(Int/Long, 0 이상) 또는 십진 숫자 문자열만 받아 십진 문자열로 돌려준다.
     * Android org.json 은 Long 범위를 넘는 정수와 소수를 Double 로 주므로 Double 은 손실 가능성 때문에 거부한다.
     */
    fun id(value: Any?, maxLength: Int): String? = when (value) {
        is Int -> value.takeIf { it >= 0 }?.toString()
        is Long -> value.takeIf { it >= 0 }?.toString()
        is String -> value.takeIf { it.length <= maxLength && DECIMAL.matches(it) }
        else -> null
    }

    fun parse(raw: String?, maxIdLength: Int): SlotParse {
        if (raw == null) return SlotParse.Invalid("slot_missing")
        val slot = try { JSONObject(raw) } catch (_: Exception) { return SlotParse.Invalid("slot_json") }
        val rawId = slot.opt("id")
        val id = id(rawId, maxIdLength) ?: return SlotParse.Invalid("slot_id", kind(rawId))
        val nickname = slot.opt("token_nickname") as? String ?: return SlotParse.Invalid("nickname", kind(rawId))
        val address = when (val value = slot.opt("content_address")) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> return SlotParse.Invalid("content_address", kind(rawId))
        }
        // tr_cnt 는 표시용 부가 정보라 없으면 0 으로 두고, 있는데 정수가 아니면 거부한다.
        val transfers = when (val value = slot.opt("tr_cnt")) {
            null, JSONObject.NULL -> 0L
            is Int -> value.toLong()
            is Long -> value
            else -> return SlotParse.Invalid("transfer_count", kind(rawId))
        }
        return SlotParse.Ok(Slot(id, nickname, address, transfers))
    }

    fun kind(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> "string"
        is Int, is Long -> "integer"
        is Number -> "number"
        is Boolean -> "boolean"
        is JSONObject -> "object"
        is JSONArray -> "array"
        else -> "other"
    }
}
