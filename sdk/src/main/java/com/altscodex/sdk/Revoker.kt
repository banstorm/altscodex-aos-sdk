// 로그아웃 시 refresh token 을 서버에서 폐기하는 form POST(10초 timeout, 리다이렉트 미추종).
package com.altscodex.sdk

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object Revoker {
    private const val TIMEOUT_MS = 10_000

    /** 서버가 200 으로 응답하면 true. 네트워크 오류·비 200 은 false 이며 예외를 밖으로 내보내지 않는다. */
    fun revoke(endpoint: String, clientId: String, refreshToken: String): Boolean {
        if (!endpoint.startsWith("https://")) return false
        val connection = try { URL(endpoint).openConnection() as HttpURLConnection } catch (_: Exception) { return false }
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(formBody(clientId, refreshToken).toByteArray(Charsets.UTF_8)) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun formBody(clientId: String, refreshToken: String): String =
        listOf("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId)
            .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
}
