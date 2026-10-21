// KeystoreSecureStore 실기기·에뮬레이터 검증(왕복·재생성 복원·백업 제외 경로·평문 미노출·손상 파일 회복).
package com.altscodex.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreSecureStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clientId = "keystore-test-client"

    private fun files() = context.noBackupFilesDir.listFiles { file -> file.name.startsWith("altscodex-sdk-") }.orEmpty().toList()

    @Before @After fun clean() {
        files().forEach(File::delete)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("altscodex.sdk.$clientId")
    }

    @Test fun roundTripSurvivesNewInstance() {
        KeystoreSecureStore(context, clientId).put("session", "SECRET-VALUE")
        assertEquals("SECRET-VALUE", KeystoreSecureStore(context, clientId).get("session"))
    }

    @Test fun fileLivesInNoBackupDirAndHidesPlaintext() {
        KeystoreSecureStore(context, clientId).put("session", "SECRET-VALUE")
        val file = files().single()
        assertEquals(context.noBackupFilesDir, file.parentFile)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("SECRET-VALUE"))
    }

    @Test fun removingLastKeyDeletesFile() {
        val store = KeystoreSecureStore(context, clientId)
        store.put("session", "a")
        store.put("session", null)
        assertNull(KeystoreSecureStore(context, clientId).get("session"))
        assertTrue(files().isEmpty())
    }

    @Test fun corruptFileIsTreatedAsEmpty() {
        KeystoreSecureStore(context, clientId).put("session", "a")
        files().single().writeBytes(byteArrayOf(1, 2, 3))
        val store = KeystoreSecureStore(context, clientId)
        assertNull(store.get("session"))
        store.put("session", "b")
        assertEquals("b", KeystoreSecureStore(context, clientId).get("session"))
    }

    @Test fun clientsAreIsolated() {
        KeystoreSecureStore(context, clientId).put("session", "a")
        assertNull(KeystoreSecureStore(context, "other-client").get("session"))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("altscodex.sdk.other-client")
    }
}
