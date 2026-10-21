// 로그인 콜백 처리 결과(AuthResult)와 로그에 안전한 단계 분류(Stage), SDK 예외를 정의한다.
package com.altscodex.sdk

/** Outcome of [AltsCodexAuth.handleResult]. Show each case to the user and offer a new sign-in where it makes sense. */
public sealed class AuthResult {
    /** Sign-in finished and the session was stored. */
    public data class Success(val session: Session) : AuthResult()

    /** The user closed the browser or cancelled the flow. */
    public object Cancelled : AuthResult() {
        override fun toString(): String = "Cancelled"
    }

    /** The user declined consent (`error=access_denied`). */
    public object Denied : AuthResult() {
        override fun toString(): String = "Denied"
    }

    /** The pending sign-in was older than [AltsCodexConfig.pendingTtlMs]. Start a new sign-in. */
    public object Expired : AuthResult() {
        override fun toString(): String = "Expired"
    }

    /** Sign-in failed at [stage]. The stage never contains tokens, codes or user values. */
    public data class Failed(val stage: Stage) : AuthResult()
}

/** Log-safe classification of where an operation failed or what a diagnostics event refers to. */
public enum class Stage {
    /** A callback arrived but no sign-in was pending. */
    NoPendingRequest,

    /** The callback belongs to an older sign-in. The newer pending sign-in is kept. */
    StaleCallback,

    /** Starting the sign-in or reading the browser callback failed. */
    Authorization,

    /** The code exchange failed for a reason other than an expired or used code (network, server). */
    TokenExchange,

    /** The server answered `invalid_grant`: the code expired (60 seconds) or was already used. */
    CodeExpiredOrUsed,

    /** The token response was missing a field or had a field of the wrong type. */
    TokenValidation,

    /** Reading or writing the [SecureStore] failed. */
    Storage,

    /** Refreshing the session failed temporarily (network or server). The stored session is kept. */
    Refresh,

    /** Server-side revocation during [AltsCodexAuth.signOut]. */
    Revocation,
}

/**
 * Thrown by [AltsCodexAuth.loginIntent] and [AltsCodexAuth.session] when the operation cannot finish.
 * The message contains only the [stage], never a token or user value.
 */
public class AltsCodexException internal constructor(public val stage: Stage) :
    Exception("AltsCodex operation failed at stage $stage")
