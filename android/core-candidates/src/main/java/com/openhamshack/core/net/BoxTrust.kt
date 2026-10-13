package com.openhamshack.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS to a box on the LAN, whose certificate no device trusts out of the
 * box: Dock360 serves its modules with certificates from Caddy's internal CA.
 *
 * Trust on first use, said as what it is. The app fetches the CA certificate
 * once over plain HTTP from `http://<host>/dock360-ca.crt`, shows the operator
 * its SHA-256 fingerprint (which Dock360's own admin page also shows), and
 * on their OK keeps it for that box. From then on that box's connections
 * trust the system's CAs plus that one certificate - nothing else, and no
 * other box's. A tailnet address (publicly trusted certificate) never needs
 * this.
 *
 * Deliberately not "trust all certificates": that would hand a session cookie
 * to anything on the LAN that answered first.
 */
object BoxTrust {
    /** The CA's well-known address on a Dock360 host: plain HTTP, port 80 (another port only for testing). */
    fun caUrl(address: BoxAddress, port: Int = 80): String =
        "http://${address.urlHost}" + (if (port == 80) "" else ":$port") + "/dock360-ca.crt"

    fun parse(bytes: ByteArray): X509Certificate {
        val text = runCatching { String(bytes, Charsets.US_ASCII) }.getOrDefault("")
        val der = if (text.contains("-----BEGIN CERTIFICATE-----")) {
            val b64 = text.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----")
                .filterNot { it.isWhitespace() }
            Base64.getDecoder().decode(b64)
        } else bytes
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    fun toPem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) +
            "\n-----END CERTIFICATE-----\n"

    /** AA:BB:... as an admin page prints it. */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** Fetch the host's CA certificate over plain HTTP. The caller shows its fingerprint before keeping it. */
    fun fetchCa(address: BoxAddress, http: OkHttpClient, port: Int = 80): X509Certificate {
        val url = caUrl(address, port)
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("No certificate at $url (HTTP ${r.code})")
            return try {
                parse(r.body?.bytes() ?: ByteArray(0))
            } catch (e: Exception) {
                throw IOException("$url is not a certificate")
            }
        }
    }

    /** Whether [e] (or a cause) is "the server's certificate is not trusted". */
    fun isUntrusted(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SSLHandshakeException || t is CertificateException ||
                t.javaClass.name.contains("CertPathValidatorException")) return true
            t = t.cause
        }
        return false
    }

    /**
     * [base] with trust widened by [pinnedPem] (the system's CAs plus that
     * one), or [base] unchanged when there is nothing pinned.
     */
    fun client(base: OkHttpClient, pinnedPem: String?): OkHttpClient {
        if (pinnedPem.isNullOrBlank()) return base
        val pinned = parse(pinnedPem.toByteArray())
        val tm = SystemPlusPinned(pinned)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return base.newBuilder().sslSocketFactory(ctx.socketFactory, tm).build()
    }

    private class SystemPlusPinned(pinned: X509Certificate) : X509TrustManager {
        private val system = trustManager(null)
        private val mine = trustManager(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("pinned", pinned)
        })

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                mine.checkServerTrusted(chain, authType)
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + mine.acceptedIssuers

        private fun trustManager(ks: KeyStore?): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
