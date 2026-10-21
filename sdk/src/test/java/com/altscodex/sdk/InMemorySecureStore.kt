// 테스트용 메모리 SecureStore. failWrites 로 저장 실패를 흉내 낸다.
package com.altscodex.sdk

class InMemorySecureStore : SecureStore {
    val values = linkedMapOf<String, String>()
    var failWrites = false
    var failReads = false

    @Synchronized override fun get(key: String): String? {
        if (failReads) throw IllegalStateException("read failed")
        return values[key]
    }

    @Synchronized override fun put(key: String, value: String?) {
        if (failWrites) throw IllegalStateException("write failed")
        if (value == null) values.remove(key) else values[key] = value
    }
}
