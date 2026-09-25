// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
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
            val payload = Json.parseToJsonElement(body).jsonObject
            assertEquals("deepseek-flash", payload.getValue("model").jsonPrimitive.content)
            assertEquals("disabled", payload.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(0, payload.getValue("temperature").jsonPrimitive.int)
            assertEquals("json_object", payload.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
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
        for (status in listOf(400, 401, 402, 422, 429, 500, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status))
            val result = DeepSeekClient(endpoint = server.url("/chat/completions").toString()).evaluate(input, "private-key")
            assertTrue(result is EvaluationResult.Failure)
            assertTrue((result as EvaluationResult.Failure).message.contains(status.toString()))
            assertFalse(result.message.contains("private-key"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun malformedKeyCannotEscapeIntoErrorOrRequest() = runBlocking {
        MockWebServer().use { server ->
            val secret = "private\nkey"
            val result = DeepSeekClient(endpoint = server.url("/chat/completions").toString()).evaluate(input, secret)
            assertTrue(result is EvaluationResult.Failure)
            assertFalse((result as EvaluationResult.Failure).message.contains("private"))
            assertEquals(0, server.requestCount)
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
