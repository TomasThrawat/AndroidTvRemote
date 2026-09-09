package com.tomasthrawat.androidtvremote.net

import com.tomasthrawat.androidtvremote.crypto.CertManager
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Builds an SSLContext that presents our AndroidKeyStore client certificate
 * and records whatever certificate the TV presents. The TV is not signed by
 * a CA we can validate against - the pairing code the user reads off the TV
 * screen is what proves we're talking to the right device (same trust model
 * the official Google TV app uses), so we deliberately trust-on-connect here
 * and rely on [PairingSession.submitCode] to prove it cryptographically.
 */
object TvTlsFactory {

    class CapturingTrustManager : X509TrustManager {
        var lastServerChain: Array<X509Certificate>? = null
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            lastServerChain = chain
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun create(): Pair<SSLContext, CapturingTrustManager> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(CertManager.androidKeyStore(), null)
        val trustManager = CapturingTrustManager()
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(kmf.keyManagers, arrayOf<TrustManager>(trustManager), null)
        return ctx to trustManager
    }
}
