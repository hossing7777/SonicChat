package com.example.sonicchat

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

object Crypto {
    private fun deriveKey(pass: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(Charsets.UTF_8))

    /** برمی‌گرداند: [0x01][iv 16][enc]  یا  [0x00][plain] */
    fun wrap(plain: ByteArray, pass: String, encrypt: Boolean): ByteArray {
        if (!encrypt || pass.isEmpty()) {
            val o = ByteArray(1 + plain.size)
            o[0] = 0
            System.arraycopy(plain, 0, o, 1, plain.size)
            return o
        }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(pass), "AES"), IvParameterSpec(iv))
        val enc = c.doFinal(plain)
        val o = ByteArray(1 + 16 + enc.size)
        o[0] = 1
        System.arraycopy(iv, 0, o, 1, 16)
        System.arraycopy(enc, 0, o, 17, enc.size)
        return o
    }

    /** data = [flag][...]; اگر flag=1 رمزگشایی، وگرنه همان plain */
    fun unwrap(data: ByteArray, pass: String): ByteArray {
        if (data.isEmpty()) return data
        val flag = data[0].toInt() and 0xFF
        if (flag == 0) return data.copyOfRange(1, data.size)
        if (data.size < 17) return data
        val iv = data.copyOfRange(1, 17)
        val enc = data.copyOfRange(17, data.size)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(pass), "AES"), IvParameterSpec(iv))
        return c.doFinal(enc)
    }
}
