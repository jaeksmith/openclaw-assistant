package com.openclaw.assistant.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.R
import kotlinx.coroutines.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Voice Interaction Session
 * Thin UI layer that binds to ConversationService for the actual voice loop.
 */
class OpenClawSession(context: Context) : VoiceInteractionSession(context),
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "OpenClawSession"
    }

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: androidx.lifecycle.ViewModelStore = androidx.lifecycle.ViewModelStore()

    // Service binding — mutableStateOf so Compose recomposes when service connects
    private var conversationService by mutableStateOf<ConversationService?>(null)
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ConversationService.LocalBinder
            conversationService = localBinder.service
            bound = true
            Log.d(TAG, "Bound to ConversationService")
            // Start the conversation once bound
            conversationService?.startConversation()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            conversationService = null
            bound = false
            Log.d(TAG, "Unbound from ConversationService")
        }
    }

    override fun onCreate() {
        Log.e(TAG, "Session onCreate start")
        super.onCreate()

        try { savedStateRegistryController.performAttach() } catch (e: Exception) { Log.w(TAG, "SavedStateRegistry already attached?", e) }
        try { savedStateRegistryController.performRestore(null) } catch (e: Exception) { Log.w(TAG, "SavedStateRegistry already restored?", e) }
        try { lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE) } catch (e: Exception) { Log.w(TAG, "Lifecycle ON_CREATE failed", e) }

        Log.e(TAG, "Session onCreate completed")
    }

    override fun onCreateContentView(): View {
        Log.e(TAG, "Session onCreateContentView")
        val composeView = ComposeView(context).apply {
            try {
                setViewTreeLifecycleOwner(this@OpenClawSession)
                setViewTreeViewModelStoreOwner(this@OpenClawSession)
                setViewTreeSavedStateRegistryOwner(this@OpenClawSession)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ViewTree owners", e)
            }

            setContent {
                // Observe service flows (use defaults when not bound)
                val svc = conversationService
                val state = svc?.conversationState?.collectAsState()?.value ?: AssistantState.PROCESSING
                val display = svc?.displayText?.collectAsState()?.value ?: ""
                val query = svc?.userQuery?.collectAsState()?.value ?: ""
                val partial = svc?.partialText?.collectAsState()?.value ?: ""
                val error = svc?.errorMessage?.collectAsState()?.value
                val level = svc?.audioLevel?.collectAsState()?.value ?: 0f

                // Track whether we've ever been active so we don't dismiss before the
                // conversation even starts (e.g. speech recognizer fails immediately on launch)
                var wasEverActive by remember { mutableStateOf(false) }
                LaunchedEffect(state) {
                    if (state != AssistantState.IDLE) wasEverActive = true
                    if (state == AssistantState.IDLE && wasEverActive && svc != null) {
                        // Service stopped the conversation — close the overlay
                        finish()
                    }
                }

                AssistantUI(
                    state = state,
                    displayText = display,
                    userQuery = query,
                    partialText = partial,
                    errorMessage = error,
                    audioLevel = level,
                    onClose = {
                        conversationService?.stopConversation()
                        finish()
                    },
                    onRetry = {
                        conversationService?.startConversation()
                    }
                )
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        Log.d(TAG, "Session shown with flags: $showFlags")

        // Start and bind to ConversationService
        val intent = Intent(context, ConversationService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onHide() {
        super.onHide()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)

        // Unbind but do NOT stop the service — conversation continues in background
        if (bound) {
            context.unbindService(serviceConnection)
            bound = false
            conversationService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
    }
}

/**
 * アシスタントの状態
 */
enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    SPEAKING,
    ERROR
}

/**
 * アシスタントUI (Compose)
 */
@Composable
fun AssistantUI(
    state: AssistantState,
    displayText: String,
    userQuery: String,
    partialText: String,
    errorMessage: String?,
    audioLevel: Float,
    onClose: () -> Unit,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Closeボタン
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // マイクアイコン
            val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
            val baseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (state == AssistantState.LISTENING) 1.1f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "base_scale"
            )

            val normalizedLevel = ((audioLevel + 2f) / 10f).coerceIn(0f, 1f)
            val targetLevelScale = 1f + (normalizedLevel * 0.5f)

            val animatedLevelScale by animateFloatAsState(
                targetValue = if (state == AssistantState.LISTENING) targetLevelScale else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "audio_level_scale"
            )

            val finalScale = if (state == AssistantState.LISTENING) maxOf(baseScale, animatedLevelScale) else 1f

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = finalScale
                        scaleY = finalScale
                    }
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            AssistantState.LISTENING -> Color(0xFF4CAF50)
                            AssistantState.SPEAKING -> Color(0xFF2196F3)
                            AssistantState.THINKING, AssistantState.PROCESSING -> Color(0xFFFFC107)
                            AssistantState.ERROR -> Color(0xFFF44336)
                            else -> Color(0xFF9E9E9E)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state == AssistantState.ERROR) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 状態テキスト
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> stringResource(R.string.state_listening)
                    AssistantState.PROCESSING -> stringResource(R.string.state_processing)
                    AssistantState.THINKING -> stringResource(R.string.state_thinking)
                    AssistantState.SPEAKING -> stringResource(R.string.state_speaking)
                    AssistantState.ERROR -> stringResource(R.string.state_error)
                    else -> stringResource(R.string.state_ready)
                },
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 認識中のテキスト（部分結果）
            if (partialText.isNotBlank() && state == AssistantState.LISTENING) {
                Text(
                    text = partialText,
                    fontSize = 16.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }

            // User Query (Final Result)
            if (userQuery.isNotBlank()) {
                Text(
                    text = "$userQuery",
                    fontSize = 16.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // メインテキスト
            if (displayText.isNotBlank() && state != AssistantState.LISTENING) {
                Text(
                    text = displayText,
                    fontSize = 18.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Errorメッセージ
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    fontSize = 14.sp,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_try_again))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
