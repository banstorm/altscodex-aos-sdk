// slot.id 정규화 규칙(정수·십진 문자열만 허용) 순수 JVM 테스트.
package com.altscodex.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotParserTest {
    private fun id(value: Any?, max: Int = 512) = SlotParser.id(value, max)

    @Test fun integerAndDecimalStringGiveSameId() {
        assertEquals("42", id(42))
        assertEquals("42", id(42L))
        assertEquals("42", id("42"))
        assertEquals("0", id(0))
    }

    @Test fun longAboveDoublePrecisionIsLossless() {
        // 2^53 + 1 은 Double 로 표현할 수 없다.
        assertEquals("9007199254740993", id(9_007_199_254_740_993L))
        assertEquals(Long.MAX_VALUE.toString(), id(Long.MAX_VALUE))
    }

    @Test fun rejectsLossyOrNonIntegerTypes() {
        for (value in listOf<Any?>(42.0, 42.5, 9_007_199_254_740_992.0, 42f, true, false, Any(), mapOf("id" to 1), listOf(1), null)) {
            assertNull("accepted $value", id(value))
        }
    }

    @Test fun rejectsNegativeBlankAndMalformedStrings() {
        for (value in listOf<Any?>(-1, -1L, "-1", "", " ", " 42", "42 ", "042", "4.2", "1e3", "0x2A", "abc")) {
            assertNull("accepted '$value'", id(value))
        }
    }

    @Test fun enforcesConfiguredMaximumLength() {
        assertEquals("1".repeat(512), id("1".repeat(512)))
        assertNull(id("1".repeat(513)))
        assertNull(id("1234", max = 3))
    }
}
