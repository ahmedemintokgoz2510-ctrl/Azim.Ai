package com.azim.app.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Yerel hesap güvenliği: parola PBKDF2 ile tuzlanıp özetlenir, düz metin saklanmaz. */
object Auth {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun verify(password: String, user: User): Boolean {
        val expected = decode(user.hash)
        val actual = decode(hash(password, decode(user.salt)))
        return MessageDigest.isEqual(expected, actual)
    }
}
