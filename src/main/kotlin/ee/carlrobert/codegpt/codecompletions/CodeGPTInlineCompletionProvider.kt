package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.completions.CompletionRequestService
import ee.carlrobert.codegpt.completions.HackSettings
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.codegpt.ui.OverlayUtil.showNotification
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.sse.EventSource
import java.util.concurrent.atomic.AtomicReference

@Suppress("UnstableApiUsage")
class CodeGPTInlineCompletionProvider : DebouncedInlineCompletionProvider() {
    companion object {
        private val logger = thisLogger()
    }

    private val currentCall = AtomicReference<EventSource>(null)

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("CodeGPTInlineCompletionProvider")

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return HackSettings.CompletionDebounceDelayMs.milliseconds
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        if (request.editor.project == null) {
            logger.error("Could not find project")
            return InlineCompletionSuggestion.empty()
        }

        return InlineCompletionSuggestion.Default(channelFlow {
            val infillRequest = withContext(Dispatchers.EDT) {
                InfillRequestDetails.fromInlineCompletionRequest(request)
            }
            currentCall.set(
                CompletionRequestService.getInstance().getCodeCompletionAsync(
                    infillRequest,
                    CodeCompletionEventListener {
                        val inlineText = it
                            .dropWhile { ch -> ch.isWhitespace() &&
                                HackSettings.TrimIndentFirstCompletionLine
                            }

                            // Either take first line, or everything if
                            // AllowMultilineSuggestions is enabled.
                            .takeWhile { message -> message != '\n' ||
                                HackSettings.AllowMultilineSuggestions }

                            .toString()
                            .trim()
                        request.editor.putUserData(CodeGPTKeys.PREVIOUS_INLAY_TEXT, inlineText)
                        launch {
                            try {
                                trySend(InlineCompletionGrayTextElement(inlineText))
                            } catch (e: Exception) {
                                logger.error("Failed to send inline completion suggestion", e)
                            }
                        }
                    }
                )
            )
            awaitClose { cancelCurrentCall() }
        })
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val selectedService = GeneralSettings.getCurrentState().selectedService
        val codeCompletionsEnabled = when (selectedService) {
            ServiceType.OPENAI -> OpenAISettings.getCurrentState().isCodeCompletionsEnabled
            ServiceType.CUSTOM_OPENAI -> service<CustomServiceSettings>().state.codeCompletionSettings.codeCompletionsEnabled
            ServiceType.LLAMA_CPP -> LlamaSettings.getCurrentState().isCodeCompletionsEnabled
            else -> false
        }
        return event is InlineCompletionEvent.DocumentChange && codeCompletionsEnabled
    }

    private fun cancelCurrentCall() {
        currentCall.getAndSet(null)?.cancel()
    }

    class CodeCompletionEventListener(
        private val completed: (StringBuilder) -> Unit
    ) : CompletionEventListener<String> {

        override fun onMessage(message: String?, eventSource: EventSource?) {
            if (HackSettings.AllowMultilineSuggestions) {
                return
            }

            if (message != null && message.contains('\n')) {
                eventSource?.cancel()
            }
        }

        override fun onComplete(messageBuilder: StringBuilder) {
            completed(messageBuilder)
        }

        override fun onCancelled(messageBuilder: StringBuilder) {
            completed(messageBuilder)
        }

        override fun onError(error: ErrorDetails, ex: Throwable) {
            if (ex.message == null || (ex.message != null && ex.message != "Canceled")) {
                showNotification(error.message, NotificationType.ERROR)
                logger.error(error.message, ex)
            }
        }
    }
}