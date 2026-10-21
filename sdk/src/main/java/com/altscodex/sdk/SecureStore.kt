// 세션·대기 로그인 요청을 보관하는 교체 가능한 키-값 저장소 계약.
package com.altscodex.sdk

/**
 * Key-value storage for the session and the pending sign-in.
 *
 * Implementations must encrypt values at rest, keep them out of backups, and make each [put] a single
 * atomic commit. Throw an exception when a read or write fails; the SDK reports it as [Stage.Storage].
 * The default implementation is [KeystoreSecureStore].
 */
public interface SecureStore {
    /** Returns the value for [key], or `null` when absent. */
    public fun get(key: String): String?

    /** Stores [value] under [key]. A `null` value removes the key. */
    public fun put(key: String, value: String?)
}
