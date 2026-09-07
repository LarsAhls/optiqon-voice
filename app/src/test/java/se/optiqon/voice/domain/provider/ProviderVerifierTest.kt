package se.optiqon.voice.domain.provider

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.optiqon.voice.data.api.ApiClientFactory
import se.optiqon.voice.testing.TlsMockServer

/**
 * The verifier is what onboarding trusts when it says "connected", so every answer it can
 * give is pinned here. The server is a local MockWebServer over TLS, because the app refuses
 * plain HTTP base URLs — no provider is contacted and no key is real.
 */
class ProviderVerifierTest {

    private lateinit var server: MockWebServer
    private lateinit var verifier: ProviderVerifier
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        val tls = TlsMockServer()
        server = tls.server
        baseUrl = tls.baseUrl
        verifier = ProviderVerifier(ApiClientFactory(tls.client))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a transcribing endpoint verifies`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":""}"""))

        val result = verifier.verifyTranscription(baseUrl, "gsk_not-a-real-key", "whisper-large-v3-turbo")

        assertEquals(VerificationResult.Ok, result)
        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer gsk_not-a-real-key", request.getHeader("Authorization"))
    }

    @Test
    fun `a refused key is reported as rejected, with the status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid api key"}"""))

        val result = verifier.verifyTranscription(baseUrl, "gsk_wrong", "whisper-large-v3-turbo")

        assertTrue(result is VerificationResult.Rejected)
        result as VerificationResult.Rejected
        assertEquals(401, result.status)
        assertTrue(result.detail.contains("invalid api key"))
    }

    @Test
    fun `a server error is rejected too, not mistaken for a network problem`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream is down"))

        val result = verifier.verifyTranscription(baseUrl, "gsk_key", "whisper-large-v3-turbo")

        assertEquals(500, (result as VerificationResult.Rejected).status)
    }

    @Test
    fun `a host that does not answer is unreachable`() = runTest {
        val deadUrl = baseUrl
        server.shutdown()

        val result = verifier.verifyTranscription(deadUrl, "gsk_key", "whisper-large-v3-turbo")

        assertTrue("expected Unreachable but was " + result, result is VerificationResult.Unreachable)
        server = MockWebServer().also { it.start() } // so tearDown has something to shut down
    }

    @Test
    fun `plain http is invalid, and never leaves the device`() = runTest {
        val result = verifier.verifyTranscription("http://api.example.com/", "gsk_key", "whisper-large-v3-turbo")

        assertTrue(result is VerificationResult.Invalid)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a blank base url is invalid`() = runTest {
        val result = verifier.verifyTranscription("   ", "gsk_key", "whisper-large-v3-turbo")

        assertTrue(result is VerificationResult.Invalid)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the probe is a real, short wav`() {
        val wav = verifier.silentWav()

        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals(44 + 1600 * 2, wav.size)
    }
}
