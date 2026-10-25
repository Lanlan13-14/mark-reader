package com.markreader.ui.editor

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.CountDownLatch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Debug-only host; native input is the production class, not an EditText substitute. */
class EditorInstrumentationActivity : ComponentActivity() {
    lateinit var native: NativeDocumentEditText
        private set
    lateinit var session: EditorDocumentSession
        private set
    private lateinit var container: FrameLayout
    var baseline by mutableStateOf(TextFieldValue())
        private set
    var baselineCallbacks = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }

    fun installNative(document: String) {
        session = EditorDocumentSession()
        session.replaceAll(document)
        reattachNative()
    }

    fun detachNative() {
        native.unbind()
        container.removeAllViews()
    }

    fun reattachNative() {
        native = NativeDocumentEditText(this)
        native.typeface = android.graphics.Typeface.MONOSPACE
        native.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        native.setLineSpacing(0f, 1.4f)
        native.showSoftInputOnFocus = false
        container.addView(native, FrameLayout.LayoutParams(-1, -1))
        native.bind(session)
        native.requestFocus()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun installBaseline(document: String, inputIntercepted: CountDownLatch) {
        baseline = TextFieldValue(document)
        baselineCallbacks = 0
        setContent {
            val focus = remember { FocusRequester() }
            // This baseline uses Compose semantics, NOT Android IME transport. Keep the
            // focused editor's input session alive but never connect a system keyboard.
            // Otherwise queued IME commands can race the test's selection/edit oracle.
            val interceptor = remember {
                PlatformTextInputInterceptor { _, _ ->
                    inputIntercepted.countDown()
                    awaitCancellation()
                }
            }
            InterceptPlatformTextInput(interceptor) {
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    value = baseline,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 22.4.sp),
                    onValueChange = { baselineCallbacks++; baseline = it },
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).focusRequester(focus)
                )
            }
        }
    }

    fun selectBaseline(offset: Int) {
        baseline = baseline.copy(selection = TextRange(offset))
    }
}
