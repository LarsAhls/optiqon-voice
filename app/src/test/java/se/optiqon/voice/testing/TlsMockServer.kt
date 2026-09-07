package se.optiqon.voice.testing

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress

/**
 * A local HTTPS server and a client that trusts it. HTTPS because the app refuses plain HTTP
 * base URLs; local because no test here may talk to a real provider or hold a real key.
 */
class TlsMockServer {
    private val heldCertificate: HeldCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
        .build()

    val server: MockWebServer = MockWebServer().apply {
        useHttps(
            HandshakeCertificates.Builder().heldCertificate(heldCertificate).build().sslSocketFactory(),
            false
        )
        start()
    }

    val baseUrl: String get() = server.url("/").toString()

    val client: OkHttpClient by lazy {
        val certificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCertificate.certificate)
            .build()
        OkHttpClient.Builder()
            .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
            .build()
    }

    fun shutdown() = server.shutdown()
}
