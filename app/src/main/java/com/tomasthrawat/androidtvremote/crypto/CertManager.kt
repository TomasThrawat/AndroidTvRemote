package com.tomasthrawat.androidtvremote.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal

/**
 * Generates and stores the self-signed client TLS certificate that identifies
 * this app to the Android TV during pairing, using the AndroidKeyStore so the
 * private key never leaves secure hardware/software backing.
 */
object CertManager {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "atv_remote_client_key"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** Creates the client key pair + self-signed certificate if not already present. */
    fun ensureClientIdentity() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return
        val notBefore = Date()
        val notAfter = Date(notBefore.time + TimeUnit.DAYS.toMillis(3650))
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setKeySize(2048)
            .setCertificateSubject(X500Principal("CN=atvremote"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    fun clientCertificate(): X509Certificate {
        ensureClientIdentity()
        return keyStore().getCertificate(ALIAS) as X509Certificate
    }

    fun alias(): String = ALIAS

    fun androidKeyStore(): KeyStore {
        ensureClientIdentity()
        return keyStore()
    }
}
