// SDK 설정값(클라이언트 ID·redirect URI·issuer·앱 정책 한도·저장소·로거)을 담는 불변 객체.
package com.altscodex.sdk

/**
 * Configuration for [AltsCodexAuth].
 *
 * @property clientId The `client_id` of the public client registered in the AltsCodex developer center.
 *   Never put a client secret in an app.
 * @property redirectUri The redirect URI exactly as registered, for example `com.example.app:/callback`.
 *   Its scheme must also be set as `manifestPlaceholders["appAuthRedirectScheme"]`.
 * @property issuer The AltsCodex issuer. Endpoints are loaded from its discovery document.
 * @property pendingTtlMs How long a started sign-in may wait for its callback. This is an app policy,
 *   separate from the 60-second lifetime of an authorization code. Default 10 minutes.
 * @property maxSlotIdLength The longest decimal `slot.id` string accepted. Default 512.
 * @property store Where the session and the pending sign-in are kept. `null` uses [KeystoreSecureStore].
 * @property logger Optional diagnostics hook. It receives a [Stage] and a map of fixed, non-secret
 *   values (presence, type, result). Tokens, codes, `state`, PKCE verifiers and user values are never passed.
 */
public class AltsCodexConfig(
    public val clientId: String,
    public val redirectUri: String,
    public val issuer: String = DEFAULT_ISSUER,
    public val pendingTtlMs: Long = DEFAULT_PENDING_TTL_MS,
    public val maxSlotIdLength: Int = DEFAULT_MAX_SLOT_ID_LENGTH,
    public val store: SecureStore? = null,
    public val logger: ((Stage, Map<String, String>) -> Unit)? = null,
) {
    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(redirectUri.isNotBlank() && ':' in redirectUri && '#' !in redirectUri) { "redirectUri must be an absolute URI without a fragment" }
        require(issuer.startsWith("https://")) { "issuer must use https" }
        require(pendingTtlMs > 0) { "pendingTtlMs must be positive" }
        require(maxSlotIdLength > 0) { "maxSlotIdLength must be positive" }
    }

    public companion object {
        /** The production AltsCodex issuer. */
        public const val DEFAULT_ISSUER: String = "https://api.altscodex.com"

        /** Default pending sign-in timeout: 10 minutes. */
        public const val DEFAULT_PENDING_TTL_MS: Long = 10 * 60_000L

        /** Default maximum length of a `slot.id` string. */
        public const val DEFAULT_MAX_SLOT_ID_LENGTH: Int = 512
    }
}
