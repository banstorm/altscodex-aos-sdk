// 앱 전체에서 AltsCodexAuth 하나를 공유하는 Application. client_id 가 없으면 auth 는 null 이다.
package com.altscodex.sample

import android.app.Application
import android.util.Log
import com.altscodex.sdk.AltsCodexAuth
import com.altscodex.sdk.AltsCodexConfig

class SampleApp : Application() {
    // SDK 는 인스턴스 하나를 재사용해야 refresh 단일 실행이 보장되므로 Activity 가 아니라 여기서 만든다.
    val auth: AltsCodexAuth? by lazy {
        if (BuildConfig.ALTSCODEX_CLIENT_ID.isBlank() || BuildConfig.ALTSCODEX_REDIRECT_URI.isBlank()) return@lazy null
        AltsCodexAuth(
            this,
            AltsCodexConfig(
                clientId = BuildConfig.ALTSCODEX_CLIENT_ID,
                redirectUri = BuildConfig.ALTSCODEX_REDIRECT_URI,
                // SDK 는 단계와 고정 값만 넘기므로 그대로 찍어도 토큰이 새지 않는다.
                logger = { stage, values -> Log.d(TAG, "$stage $values") },
            ),
        )
    }

    companion object {
        const val TAG = "AltsCodexSample"
    }
}
