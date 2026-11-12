package com.example.sonicchat

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

object Crypto {
    /** کلید متنی را به ۳۲ بایت AES-256 تبدیل می‌کند */
    private fun deriveKey(pass: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(pass.toByteArray(Charsets.UTF_8))
    }

    fun encrypt(plain: ByteArray, pass: String): ByteArray {
        if (pass.isEmpty()) return plain
        val key = SecretKeySpec(deriveKey(pass), "AES")
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
        val enc = c.doFinal(plain)
        return iv + enc
    }

    fun decrypt(data: ByteArray, pass: String): ByteArray {
        if (pass.isEmpty()) return data
        if (data.size < 17) return data
        val iv = data.copyOfRange(0, 16)
        val enc = data.copyOfRange(16, data.size)
        val key = SecretKeySpec(deriveKey(pass), "AES")
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
        return c.doFinal(enc)
    }
}
