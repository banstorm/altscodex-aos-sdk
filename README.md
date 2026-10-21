# AltsCodex Android SDK

Sign in with [AltsCodex](https://www.altscodex.com) from a native Android app. The SDK wraps
[AppAuth-Android](https://github.com/openid/AppAuth-Android) 0.11.1 with the AltsCodex rules already applied:
public client with PKCE S256, pending-request matching across process restarts, typed `slot` validation,
encrypted session storage, single-flight refresh rotation and local-first sign-out.

Requires minSdk 26.

## Install

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

`app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        // Scheme part only of your redirect URI (com.example.app:/callback -> com.example.app)
        manifestPlaceholders["appAuthRedirectScheme"] = "com.example.app"
    }
}

dependencies {
    implementation("com.github.banstorm:altscodex-aos-sdk:0.1.0")
}
```

Register a public client in the AltsCodex developer center and use its `client_id`. The redirect URI must
match the registered value character for character. Never put a client secret in the app.

## 5-minute quick start

```kotlin
val auth = AltsCodexAuth(applicationContext, AltsCodexConfig(clientId = "YOUR_CLIENT_ID", redirectUri = "com.example.app:/callback"))

// In onCreate, every time, so results arrive after Activity recreation or process restart.
val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    lifecycleScope.launch { render(auth.handleResult(result.data)) }
}

signInButton.setOnClickListener { lifecycleScope.launch { launcher.launch(auth.loginIntent()) } }
lifecycleScope.launch { val session = auth.session() /* null = signed out */ }
signOutButton.setOnClickListener { lifecycleScope.launch { auth.signOut() } }
```

Create one `AltsCodexAuth` per app (for example in your `Application`) and reuse it.

## API

| Member | What it does |
|---|---|
| `AltsCodexAuth(context, config)` | Creates the client. Uses `KeystoreSecureStore` unless `config.store` is set. |
| `suspend fun loginIntent(): Intent` | Loads discovery, stores the pending request and returns the Custom Tabs Intent. Throws `AltsCodexException`. |
| `suspend fun handleResult(data: Intent?): AuthResult` | Validates the callback, exchanges the code, validates and stores the session. Never throws. |
| `suspend fun session(): Session?` | Valid session, refreshed first when it expires within a minute. Concurrent calls share one refresh. `null` when signed out or the refresh token was rejected. Throws `AltsCodexException(Stage.Refresh)` on network errors (session kept). |
| `suspend fun signOut(): SignOutResult` | Deletes the local session first, then revokes the refresh token. `serverRevoked = false` when revocation was not confirmed. |
| `val currentSession: Session?` | Stored session without a network call. It may be expired. |
| `AltsCodexConfig(clientId, redirectUri, issuer, pendingTtlMs, maxSlotIdLength, store, logger)` | `issuer` defaults to `https://api.altscodex.com`, `pendingTtlMs` to 10 minutes, `maxSlotIdLength` to 512. |
| `Session(accessToken, refreshToken, expiresAt, slot)` | `toString()` redacts both tokens. |
| `Slot(id, nickname, contentAddress, transferCount)` | `id` is a decimal string: JSON `42` and `"42"` are the same id. |
| `SecureStore` | Replaceable key-value store (`get`, `put`). Must encrypt at rest and stay out of backups. |

## Handling `AuthResult`

```kotlin
fun render(result: AuthResult) = when (result) {
    is AuthResult.Success -> showSignedIn(result.session.slot.nickname)
    AuthResult.Cancelled -> showMessage("Sign-in cancelled")
    AuthResult.Denied -> showMessage("Access was declined")
    AuthResult.Expired -> showMessage("Sign-in took too long. Please try again.")
    is AuthResult.Failed -> when (result.stage) {
        Stage.StaleCallback -> Unit // an older sign-in returned; the newer one is still pending
        Stage.CodeExpiredOrUsed -> showMessage("Sign-in expired. Please try again.")
        else -> showMessage("Sign-in failed (${result.stage})")
    }
}
```

`Stage` is safe to log or show in a support ticket. It never contains a token, code or user value.

## Security notes

- **No token logging.** The SDK logs nothing by default. `AltsCodexConfig.logger` receives only a `Stage`
  and fixed values such as presence, type and result. Tokens, authorization codes, `state`, PKCE verifiers
  and user values are never passed; unit tests enforce this.
- **Keystore storage.** `KeystoreSecureStore` encrypts the session and pending request with an AES-GCM key
  held in the Android Keystore and writes the file atomically.
- **Backup excluded.** The file lives in `noBackupFilesDir`, so it is excluded from cloud backup and device
  transfer without extra manifest rules.
- **Sign-out is local first.** The device is signed out even when the network is down. The AltsCodex session
  in the browser is separate, so the next sign-in may show a "Continue" screen. That is expected.
- The authorization code is valid for 60 seconds and once only. `pendingTtlMs` is a separate app policy.

## License

MIT. See [LICENSE](LICENSE).

---

## 한국어 요약

AltsCodex 로그인을 Android 앱에 붙이는 SDK 입니다. AppAuth-Android 0.11.1 위에서 PKCE S256 공개 클라이언트,
프로세스 재시작 뒤 콜백 대조, `slot` 타입 검증, Keystore 암호화 저장, 단일 refresh 회전, 로컬 우선 로그아웃을
처리합니다.

- 설치는 위 Install 절과 같습니다. `jitpack.io` 저장소를 추가하고 `appAuthRedirectScheme` 에 redirect URI 의 스킴만 넣습니다.
- `loginIntent()` 로 로그인 화면을 열고, `handleResult()` 결과(`AuthResult`)를 경우별로 안내합니다. 조용히 무시하지 마세요.
- `session()` 은 만료 1분 전이면 한 번만 갱신합니다. `null` 이면 다시 로그인해야 합니다. 네트워크 오류는 예외로 알리고 세션은 유지합니다.
- `signOut()` 은 기기 세션을 먼저 지우고 서버 폐기를 시도합니다. `serverRevoked = false` 이면 서버 폐기를 확인하지 못한 것입니다.
- 토큰·코드·state·verifier 는 로그로 나가지 않습니다. 저장 파일은 `noBackupFilesDir` 에 있어 백업에서 제외됩니다.
