// Sign in / Session / Sign out 세 버튼으로 SDK 공개 API 를 호출하고 결과(토큰 제외)를 보여 주는 데모 화면.
package com.altscodex.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.altscodex.sdk.AltsCodexAuth
import com.altscodex.sdk.AltsCodexException
import com.altscodex.sdk.AuthResult
import com.altscodex.sdk.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class MainActivity : ComponentActivity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        output = findViewById(R.id.output)
        val auth = (application as SampleApp).auth

        // Activity 재생성·프로세스 재시작 뒤에도 콜백을 받도록 onCreate 에서 항상 등록한다.
        val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (auth != null) lifecycleScope.launch { show(describe(auth.handleResult(result.data))) }
        }

        if (auth == null) {
            show(getString(R.string.missing_client_id))
            listOf(R.id.sign_in, R.id.session, R.id.sign_out).forEach { findViewById<Button>(it).isEnabled = false }
            return
        }

        findViewById<Button>(R.id.sign_in).setOnClickListener {
            lifecycleScope.launch { runCatchingStage { launcher.launch(auth.loginIntent()) } }
        }
        findViewById<Button>(R.id.session).setOnClickListener {
            lifecycleScope.launch { runCatchingStage { show(auth.session()?.let(::describe) ?: getString(R.string.signed_out)) } }
        }
        findViewById<Button>(R.id.sign_out).setOnClickListener {
            lifecycleScope.launch {
                val result = auth.signOut()
                show("Signed out: localCleared=${result.localCleared}, serverRevoked=${result.serverRevoked}")
            }
        }
        lifecycleScope.launch { showStored(auth) }
    }

    // currentSession 은 디스크를 읽을 수 있으므로 IO 에서 꺼낸다.
    private suspend fun showStored(auth: AltsCodexAuth) {
        val stored = withContext(Dispatchers.IO) { auth.currentSession }
        show(stored?.let { "Stored session\n" + describe(it) } ?: getString(R.string.signed_out))
    }

    private suspend fun runCatchingStage(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: AltsCodexException) {
            show("Failed at stage ${error.stage}")
        }
    }

    private fun describe(result: AuthResult): String = when (result) {
        is AuthResult.Success -> "Success\n" + describe(result.session)
        AuthResult.Cancelled -> "Cancelled"
        AuthResult.Denied -> "Denied"
        AuthResult.Expired -> "Expired"
        is AuthResult.Failed -> "Failed at stage ${result.stage}"
    }

    // 토큰은 절대 표시하지 않는다. slot id·닉네임·만료 시각만 보여 준다.
    private fun describe(session: Session): String =
        "slot id: ${session.slot.id}\nnickname: ${session.slot.nickname}\nexpires: ${Instant.ofEpochMilli(session.expiresAt)}"

    private fun show(text: String) {
        output.text = text
    }
}
