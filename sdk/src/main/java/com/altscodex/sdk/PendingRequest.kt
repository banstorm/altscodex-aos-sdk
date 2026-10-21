// 브라우저로 넘긴 로그인 요청을 프로세스 재시작 뒤에도 대조할 수 있게 저장하는 형식.
package com.altscodex.sdk

import org.json.JSONObject

/** [request] 는 AuthorizationRequest.jsonSerializeString() 결과로 state·PKCE verifier 를 포함하므로 보안 저장소에만 둔다. */
internal class PendingRequest(val request: String, val startedAt: Long) {
    fun toJson(): String = JSONObject().put("request", request).put("startedAt", startedAt).toString()

    companion object {
        fun fromJson(raw: String): PendingRequest =
            JSONObject(raw).let { PendingRequest(it.getString("request"), it.getLong("startedAt")) }
    }
}
