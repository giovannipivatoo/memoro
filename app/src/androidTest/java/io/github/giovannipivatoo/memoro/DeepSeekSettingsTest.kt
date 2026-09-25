// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.giovannipivatoo.memoro.ai.DeepSeekClient
import io.github.giovannipivatoo.memoro.ui.AppActions
import io.github.giovannipivatoo.memoro.ui.MemoroTheme
import io.github.giovannipivatoo.memoro.ui.SettingsScreen
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DeepSeekSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun hideKeyboard() {
        compose.runOnUiThread {
            val window = compose.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
        }
    }

    @Test fun connectionTestUsesOnlyExplicitClickAndShowsResultInline() {
        compose.activityRule.scenario.onActivity {
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        var savedKey by mutableStateOf("")
        var savedModel by mutableStateOf("deepseek-flash")
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val number = calls.incrementAndGet()
            val content = """{"verdict":"CORRECT","explanation":"Roma è corretta.","errors":[],"omissions":[],"source_conflict":false}"""
            val json = """{"choices":[{"finish_reason":"stop","message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (number == 1) 200 else 422)
                .message(if (number == 1) "OK" else "Unprocessable Entity")
                .body(json.toResponseBody()).build()
        }).build()
        val actions = AppActions(apiKey = { savedKey }, saveApiKey = { savedKey = it },
            model = { savedModel }, saveModel = { savedModel = it },
            petEnabled = { false }, savePetEnabled = {}, importApkg = { "" },
            exportApkg = { "" }, backup = { "" }, restore = { "" })
        compose.setContent { MemoroTheme { SettingsScreen(actions, DeepSeekClient(http), onMessage = {}) } }

        compose.onNodeWithText("Da configurare").performClick()
        compose.onNodeWithTag("testDeepSeek").assertIsNotEnabled()
        compose.onNodeWithTag("apiKey").performScrollTo().performTextInput("local-test-key")
        hideKeyboard()
        compose.onNodeWithTag("saveSettings").performScrollTo().performClick()
        compose.onNodeWithText("Chiave salvata").assertExists()
        assertEquals(0, calls.get())
        compose.onNodeWithTag("testDeepSeek").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("deepSeekStatus") and hasText("DeepSeek funziona", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, calls.get())

        compose.onNodeWithText("Modello avanzato").performClick()
        compose.onNodeWithTag("modelName").performScrollTo().performTextReplacement("invalid-model")
        hideKeyboard()
        compose.onNodeWithTag("testDeepSeek").assertIsNotEnabled()
        compose.onNodeWithTag("saveSettings").performScrollTo().performClick()
        assertEquals(1, calls.get())
        compose.onNodeWithTag("testDeepSeek").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("deepSeekStatus") and hasText("422", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, calls.get())
    }
}
