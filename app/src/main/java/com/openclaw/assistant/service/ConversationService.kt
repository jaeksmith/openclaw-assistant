package com.openclaw.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openclaw.assistant.R
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.data.repository.ChatRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import com.openclaw.assistant.util.AppLogger

/**
 * Foreground service that owns the voice conversation loop.
 * Survives screen-off / VoiceInteractionSession hide.
 */
class ConversationService : Service() {

    companion object {
        private const val TAG = "ConversationService"
        private const val CHANNEL_ID = "conversation_channel"
        private const val NOTIFICATION_ID = 2001
    }

    // Binder for OpenClawSession to connect
    inner class LocalBinder : Binder() {
        val service: ConversationService get() = this@ConversationService
    }

    private val binder = LocalBinder()

    private lateinit var settings: SettingsRepository
    private lateinit var apiClient: OpenClawClient
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager
    private lateinit var chatRepository: ChatRepository
    private lateinit var toneGenerator: ToneGenerator
    private var wakeLock: PowerManager.WakeLock? = null

    // SpeechRecognizer and TTS require the main thread. API calls inside
    // OpenClawClient already use withContext(Dispatchers.IO) so this is safe.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Observable state for UI
    private val _conversationState = MutableStateFlow(AssistantState.IDLE)
    val conversationState: StateFlow<AssistantState> = _conversationState.asStateFlow()

    private val _displayText = MutableStateFlow("")
    val displayText: StateFlow<String> = _displayText.asStateFlow()

    private val _userQuery = MutableStateFlow("")
    val userQuery: StateFlow<String> = _userQuery.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var currentSessionId: String? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var listeningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ConversationService onCreate")

        settings = SettingsRepository.getInstance(this)
        apiClient = OpenClawClient()
        speechManager = SpeechRecognizerManager(this)
        ttsManager = TTSManager(this)
        chatRepository = ChatRepository.getInstance(this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "ConversationService onDestroy")
        stopConversation()
        scope.cancel()
        speechManager.destroy()
        ttsManager.shutdown()
        toneGenerator.release()
        releaseWakeLock()
        sendResumeBroadcast()
        super.onDestroy()
    }

    // --- Public API for bound clients ---

    fun startConversation() {
        AppLogger.i(TAG, "Conversation started")
        acquireWakeLock()
        sendPauseBroadcast()

        // Reuse the active session from settings (shared with ChatActivity),
        // only create a new one if none exists yet
        if (currentSessionId == null) {
            val existingId = settings.sessionId.takeIf { it.isNotBlank() }
            if (existingId != null) {
                AppLogger.d(TAG, "Reusing existing session: $existingId")
                currentSessionId = existingId
            } else {
                scope.launch {
                    try {
                        currentSessionId = chatRepository.createSession(
                            title = String.format(
                                getString(R.string.default_session_title_format),
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date())
                            )
                        )
                        settings.sessionId = currentSessionId!!
                        AppLogger.i(TAG, "Created new session: $currentSessionId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create DB session", e)
                    }
                }
            }
        }

        // 設定チェック
        if (!settings.isConfigured()) {
            _conversationState.value = AssistantState.ERROR
            _errorMessage.value = getString(R.string.error_config_required)
            _displayText.value = getString(R.string.config_required)
            return
        }

        startListening()
    }

    fun stopConversation() {
        AppLogger.i(TAG, "Conversation stopped")
        listeningJob?.cancel()
        listeningJob = null
        ttsManager.stop()
        abandonAudioFocus()
        releaseWakeLock()
        sendResumeBroadcast()

        _conversationState.value = AssistantState.IDLE
        currentSessionId = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Internal conversation loop (moved from OpenClawSession) ---

    private fun startListening() {
        listeningJob?.cancel()

        _conversationState.value = AssistantState.PROCESSING
        _displayText.value = ""
        _userQuery.value = ""
        _partialText.value = ""
        _errorMessage.value = null
        _audioLevel.value = 0f

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false

            // Wait for resources
            delay(500)

            while (isActive && !hasActuallySpoken) {
                // Request audio focus
                requestAudioFocus()

                speechManager.startListening(null).collectLatest { result ->
                    when (result) {
                        is SpeechResult.Ready -> {
                            AppLogger.i(TAG, "Speech recognizer ready — listening")
                            _conversationState.value = AssistantState.LISTENING
                            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP)
                        }
                        is SpeechResult.Listening -> {
                            if (_conversationState.value != AssistantState.LISTENING) {
                                _conversationState.value = AssistantState.LISTENING
                            }
                        }
                        is SpeechResult.RmsChanged -> {
                            _audioLevel.value = result.rmsdB
                        }
                        is SpeechResult.PartialResult -> {
                            _partialText.value = result.text
                            if (_conversationState.value != AssistantState.LISTENING) {
                                _conversationState.value = AssistantState.LISTENING
                            }
                        }
                        is SpeechResult.Result -> {
                            AppLogger.i(TAG, "Speech recognized: \"${result.text}\"")
                            hasActuallySpoken = true
                            _userQuery.value = result.text
                            sendToOpenClaw(result.text)
                        }
                        is SpeechResult.Error -> {
                            val elapsed = System.currentTimeMillis() - startTime
                            val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                    result.code == SpeechRecognizer.ERROR_NO_MATCH

                            if (isTimeout && settings.continuousMode && elapsed < 5000) {
                                Log.d(TAG, "Speech timeout within 5s window ($elapsed ms), retrying...")
                                AppLogger.d(TAG, "Silence timeout within 5s window, retrying (elapsed=${elapsed}ms)")
                                // Continue to next loop iteration
                            } else if (isTimeout) {
                                // Silence timeout — show error with retry option
                                AppLogger.w(TAG, "Silence timeout (elapsed=${elapsed}ms) — no speech detected")
                                Log.d(TAG, "Silence timeout, showing error state")
                                _conversationState.value = AssistantState.ERROR
                                _errorMessage.value = getString(R.string.error_speech_input_timeout)
                                hasActuallySpoken = true
                                // Resume hotword NOW so wake word works again even while error is shown
                                abandonAudioFocus()
                                sendResumeBroadcast()
                                releaseWakeLock()
                                // Auto-cleanup after 30s in case screen is off and user can't see/dismiss
                                scope.launch {
                                    delay(30_000)
                                    if (_conversationState.value == AssistantState.ERROR) {
                                        AppLogger.d(TAG, "Auto-cleanup: error state timed out, stopping service")
                                        stopConversation()
                                    }
                                }
                            } else if (result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                AppLogger.w(TAG, "Speech recognizer busy, retrying after 1s")
                                speechManager.destroy()
                                delay(1000)
                            } else {
                                AppLogger.e(TAG, "Speech error code=${result.code}: ${result.message}")
                                _conversationState.value = AssistantState.ERROR
                                _errorMessage.value = result.message
                                hasActuallySpoken = true
                            }
                        }
                        else -> {}
                    }
                }

                if (!hasActuallySpoken) {
                    delay(300)
                }
            }
        }
    }

    private fun sendToOpenClaw(message: String) {
        AppLogger.i(TAG, "Sending to API: \"$message\"")
        _conversationState.value = AssistantState.THINKING
        _displayText.value = ""

        scope.launch {
            // Save User Message
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, message, isUser = true)
            }

            val result = apiClient.sendMessage(
                webhookUrl = settings.webhookUrl,
                message = message,
                sessionId = settings.sessionId,
                authToken = settings.authToken.takeIf { it.isNotBlank() }
            )

            result.fold(
                onSuccess = { response ->
                    val responseText = response.getResponseText()
                    if (responseText != null) {
                        AppLogger.i(TAG, "API response: \"${responseText.take(120)}${if (responseText.length > 120) "…" else ""}\"")
                        _displayText.value = responseText

                        // Save AI Message
                        currentSessionId?.let { sessionId ->
                            chatRepository.addMessage(sessionId, responseText, isUser = false)
                        }

                        if (settings.ttsEnabled) {
                            speakResponse(responseText)
                        } else if (settings.continuousMode) {
                            delay(500)
                            startListening()
                        }
                    } else if (response.error != null) {
                        _conversationState.value = AssistantState.ERROR
                        _errorMessage.value = response.error
                    } else {
                        _conversationState.value = AssistantState.ERROR
                        _errorMessage.value = getString(R.string.error_no_response)
                    }
                },
                onFailure = { error ->
                    AppLogger.e(TAG, "API error: ${error.message}")
                    Log.e(TAG, "API error", error)
                    _conversationState.value = AssistantState.ERROR
                    _errorMessage.value = error.message ?: getString(R.string.error_network)
                }
            )
        }
    }

    private fun speakResponse(text: String) {
        _conversationState.value = AssistantState.SPEAKING

        scope.launch {
            val success = ttsManager.speak(text)
            abandonAudioFocus()

            if (success) {
                // 読み上げ完了後、連続会話モードが有効なら再度リスニング開始
                if (settings.continuousMode) {
                    delay(500)
                    startListening()
                }
            } else {
                _conversationState.value = AssistantState.ERROR
                _errorMessage.value = getString(R.string.error_speech_general)
            }
        }
    }

    // --- Audio focus ---

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ).build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    // --- Wake lock ---

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OpenClawAssistant::ConversationWakeLock"
            )
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minute timeout safety
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    // --- Hotword broadcasts ---

    private fun sendPauseBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun sendResumeBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Conversation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a voice conversation is active"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Conversation active")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
    }
}
