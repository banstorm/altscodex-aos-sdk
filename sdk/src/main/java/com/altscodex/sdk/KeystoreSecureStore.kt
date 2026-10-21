// Android Keystore AES-GCM 키로 암호화한 파일을 AtomicFile 로 원자 기록하는 기본 SecureStore.
package com.altscodex.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Default [SecureStore]. Values are kept as one JSON map, encrypted with an AES-GCM key held in the
 * Android Keystore (alias `altscodex.sdk.<clientId>`), and written atomically to a file in
 * [Context.getNoBackupFilesDir], so they are excluded from backup and device transfer.
 *
 * A file that cannot be decrypted (for example after the Keystore key was removed) is deleted and treated
 * as empty, so the user signs in again instead of being stuck.
 */
public class KeystoreSecureStore(context: Context, clientId: String) : SecureStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "altscodex-sdk-${digest(clientId)}.bin"))
    private val alias = "altscodex.sdk.$clientId"
    private var cache: Map<String, String>? = null

    @Synchronized override fun get(key: String): String? = load()[key]

    @Synchronized override fun put(key: String, value: String?) {
        val current = load()
        val next = if (value == null) current - key else current + (key to value)
        if (next == current) return
        if (next.isEmpty()) delete() else write(next)
        cache = next
    }

    private fun load(): Map<String, String> = cache ?: read().also { cache = it }

    private fun read(): Map<String, String> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyMap()
        return try {
            val bytes = file.openRead().use { it.readBytes() }
            require(bytes.size in MIN_SIZE..MAX_SIZE && bytes[0] == FORMAT)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 1, IV_SIZE))
            val json = JSONObject(String(cipher.doFinal(bytes, 1 + IV_SIZE, bytes.size - 1 - IV_SIZE), Charsets.UTF_8))
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (_: Exception) {
            // 복호화 불가 파일을 그대로 두면 매 실행마다 실패하므로 지우고 재로그인으로 회복시킨다.
            delete()
            emptyMap()
        }
    }

    private fun write(values: Map<String, String>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(JSONObject(values).toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(FORMAT) + cipher.iv + encrypted)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw IOException("SecureStore write failed", error)
        }
    }

    private fun delete() {
        file.delete()
        if (file.baseFile.exists()) throw IOException("SecureStore delete failed")
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT: Byte = 1
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val MIN_SIZE = 1 + IV_SIZE + TAG_BITS / 8
        const val MAX_SIZE = 262_144

        // clientId 에 파일명으로 못 쓰는 문자가 있을 수 있어 해시 앞 16자리로 파일명을 만든다.
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }
}
