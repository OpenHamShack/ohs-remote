package com.openhamshack.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import com.openhamshack.core.net.BoxTrust
import java.net.InetAddress

/** Trust on first use against a server with its own CA, as a Dock360 host has. */
class BoxTrustTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Local Authority").build()
    private val leaf = HeldCertificate.Builder().signedBy(ca).addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName).build()
    private val server = MockWebServer()

    @Before fun up() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).build().sslSocketFactory(), false)
        server.start()
    }

    @After fun down() = server.shutdown()

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url(server.url("/icom/api/auth")).build()).execute().use { it.code }

    @Test fun `an unknown CA is refused, and said to be the certificate`() {
        server.enqueue(MockResponse().setBody("{}"))
        try {
            get(OkHttpClient())
            fail("trusted a CA nobody pinned")
        } catch (e: Exception) {
            assertTrue(BoxTrust.isUntrusted(e))
        }
    }

    @Test fun `the pinned CA is trusted for that client, on top of the system's`() {
        server.enqueue(MockResponse().setBody("{}"))
        val pem = BoxTrust.toPem(ca.certificate)
        assertEquals(200, get(BoxTrust.client(OkHttpClient(), pem)))
    }

    @Test fun `PEM and DER both parse, and the fingerprint is SHA-256 in pairs`() {
        val fromPem = BoxTrust.parse(ca.certificatePem().toByteArray())
        val fromDer = BoxTrust.parse(ca.certificate.encoded)
        assertEquals(fromPem, fromDer)
        val fp = BoxTrust.fingerprint(fromPem)
        assertEquals(32, fp.split(':').size)
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }
}
