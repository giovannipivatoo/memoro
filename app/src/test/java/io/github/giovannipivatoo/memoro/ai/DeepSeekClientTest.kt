// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeepSeekClientTest {
    private val input = EvaluationInput("Domanda", "Riferimento", "Risposta", "Punto", "Estratto")

    @Test fun sendsOnlyExplicitCardContentAndValidatesSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response("{\"verdict\":\"PARTIAL\",\"explanation\":\"Manca un punto\",\"errors\":[],\"omissions\":[\"Punto\"],\"source_conflict\":false}")))
            val result = DeepSeekClient(endpoint = server.url("/chat/completions").toString()).evaluate(input, "private-key")
            assertEquals(Verdict.PARTIAL, (result as EvaluationResult.Success).evaluation.verdict)
            val request = server.takeRequest()
            assertEquals("Bearer private-key", request.getHeader("Authorization"))
            assertEquals("/chat/completions", request.path)
            val body = request.body.readUtf8()
            for (value in listOf("Domanda", "Riferimento", "Risposta", "Punto", "Estratto")) assertTrue(body.contains(value))
            assertFalse(body.contains("sourceUrl"))
            assertTrue(body.contains("deepseek-flash"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun conflictMustBeUngradingAndMalformedResponsesStayTechnical() = runBlocking {
        val validConflict = "{\"verdict\":\"UNGRADABLE\",\"explanation\":\"Conflitto\",\"errors\":[],\"omissions\":[],\"source_conflict\":true}"
        assertTrue(DeepSeekClient().parse(response(validConflict)) is EvaluationResult.Success)
        assertTrue(DeepSeekClient().parse(response(validConflict.replace("UNGRADABLE", "CORRECT"))) is EvaluationResult.Failure)
        assertTrue(DeepSeekClient().parse(response("")) is EvaluationResult.Failure)
        assertTrue(DeepSeekClient().parse("not json") is EvaluationResult.Failure)
        assertTrue(DeepSeekClient().parse(response(validConflict).replace("\"stop\"", "\"length\"")) is EvaluationResult.Failure)
    }

    @Test fun httpFailuresDoNotRetryOrBecomeWrong() = runBlocking {
        for (status in listOf(401, 429, 500)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status))
            val result = DeepSeekClient(endpoint = server.url("/chat/completions").toString()).evaluate(input, "private-key")
            assertTrue(result is EvaluationResult.Failure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun timeoutLeavesTechnicalFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
            val result = DeepSeekClient(http, server.url("/chat/completions").toString()).evaluate(input, "private-key")
            assertTrue(result is EvaluationResult.Failure)
            assertEquals(1, server.requestCount)
        }
    }

    private fun response(content: String) = """{"choices":[{"finish_reason":"stop","message":{"content":${JsonPrimitive(content)}}}]}"""
}
