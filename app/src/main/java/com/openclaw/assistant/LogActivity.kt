package com.openclaw.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import com.openclaw.assistant.util.AppLogger
import kotlinx.coroutines.launch

class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenClawAssistantTheme {
                LogScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val allEntries by AppLogger.entries.collectAsState()
    val scope = rememberCoroutineScope()

    // Level filter: null = show all
    var minLevel by remember { mutableStateOf(AppLogger.Level.DEBUG) }
    val levels = AppLogger.Level.entries.toList()

    val filtered = remember(allEntries, minLevel) {
        allEntries.filter { it.level >= minLevel }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Logs (${filtered.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Copy to clipboard
                    IconButton(onClick = {
                        val text = AppLogger.export()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("App Logs", text))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                    }
                    // Clear
                    IconButton(onClick = {
                        AppLogger.clear()
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Level filter tabs
            ScrollableTabRow(
                selectedTabIndex = levels.indexOf(minLevel),
                modifier = Modifier.fillMaxWidth()
            ) {
                levels.forEach { level ->
                    Tab(
                        selected = minLevel == level,
                        onClick = { minLevel = level },
                        text = {
                            Text(
                                text = level.name,
                                color = if (minLevel == level) levelColor(level)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    )
                }
            }

            HorizontalDivider()

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No log entries yet",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered) { entry ->
                        LogEntryRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: AppLogger.LogEntry) {
    val bg = when (entry.level) {
        AppLogger.Level.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        AppLogger.Level.WARN  -> Color(0xFFFFF3E0).copy(alpha = 0.5f)
        AppLogger.Level.INFO  -> Color.Transparent
        AppLogger.Level.DEBUG -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Level badge
        Text(
            text = entry.level.label,
            fontSize = 10.sp,
            color = levelColor(entry.level),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(14.dp)
                .padding(top = 1.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Timestamp
        Text(
            text = entry.formattedTime,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(80.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Tag + message
        Column {
            Text(
                text = entry.tag,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = entry.message,
                fontSize = 12.sp,
                color = when (entry.level) {
                    AppLogger.Level.ERROR -> MaterialTheme.colorScheme.error
                    AppLogger.Level.WARN  -> Color(0xFFE65100)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun levelColor(level: AppLogger.Level): Color = when (level) {
    AppLogger.Level.ERROR -> MaterialTheme.colorScheme.error
    AppLogger.Level.WARN  -> Color(0xFFE65100)
    AppLogger.Level.INFO  -> MaterialTheme.colorScheme.primary
    AppLogger.Level.DEBUG -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
}
