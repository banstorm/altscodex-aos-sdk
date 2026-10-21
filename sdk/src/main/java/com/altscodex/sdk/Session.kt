// 로그인 세션·부계정(slot) 모델과 저장용 JSON 직렬화.
package com.altscodex.sdk

import org.json.JSONObject

/**
 * A signed-in session. [expiresAt] is the access token expiry in epoch milliseconds.
 * [toString] redacts both tokens so a session can never leak through logs by accident.
 */
public data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val slot: Slot,
) {
    override fun toString(): String =
        "Session(accessToken=<redacted>, refreshToken=<redacted>, expiresAt=$expiresAt, slot=$slot)"
}

/**
 * The AltsCodex account slot the user signed in with.
 *
 * @property id Decimal string form of `slot.id`. A JSON number `42` and the string `"42"` give the same id.
 * @property nickname `slot.token_nickname`.
 * @property contentAddress `slot.content_address`, or `null`.
 * @property transferCount `slot.tr_cnt`, or 0 when absent.
 */
public data class Slot(
    val id: String,
    val nickname: String,
    val contentAddress: String?,
    val transferCount: Long,
)

// 저장 형식 버전. 필드를 바꾸면 올리고 decode 에서 이전 버전을 처리한다.
private const val SESSION_FORMAT = 1

internal fun Session.toJson(): String = JSONObject()
    .put("v", SESSION_FORMAT)
    .put("access", accessToken)
    .put("refresh", refreshToken)
    .put("expiresAt", expiresAt)
    .put("slot", JSONObject()
        .put("id", slot.id)
        .put("nickname", slot.nickname)
        .put("contentAddress", slot.contentAddress ?: JSONObject.NULL)
        .put("transferCount", slot.transferCount))
    .toString()

internal fun sessionFromJson(raw: String): Session {
    val json = JSONObject(raw)
    require(json.getInt("v") == SESSION_FORMAT)
    val slot = json.getJSONObject("slot")
    return Session(
        json.getString("access"),
        json.getString("refresh"),
        json.getLong("expiresAt"),
        Slot(
            slot.getString("id"),
            slot.getString("nickname"),
            if (slot.isNull("contentAddress")) null else slot.getString("contentAddress"),
            slot.getLong("transferCount"),
        ),
    )
}

/** Result of [AltsCodexAuth.signOut]. */
public data class SignOutResult(
    /** `true` when the session was removed from this device. */
    val localCleared: Boolean = true,
    /** `true` when the server confirmed revocation, or when there was no session to revoke. */
    val serverRevoked: Boolean,
)
