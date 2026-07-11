package com.torentchat.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torentchat.domain.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The active chat screen for a single conversation.
 *
 * Renders an end-to-end-encrypted thread of decrypted [Message] bubbles. The
 * outgoing/incoming alignment + colors visually reinforce which messages are
 * ours. A bottom input bar composes and sends new text messages.
 *
 * TODO: wire to a ChatViewModel (Hilt) that:
 *  - loads the conversation + peer display name from ConversationRepository
 *  - observes MessageRepository.streamMessages(conversationId)
 *  - exposes send(message) which encrypts via SignalSession + dispatches over the WebRTC data channel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
) {
    // TODO: val viewModel: ChatViewModel = hiltViewModel()
    //  val messages by viewModel.messages.collectAsStateWithLifecycle()
    //  val peerName by viewModel.peerDisplayName.collectAsStateWithLifecycle()

    val peerDisplayName = remember { "Alya" } // TODO: from ViewModel

    val listState = rememberLazyListState()

    // Mock message thread. Real impl replaces this with the ViewModel stream.
    val messages = remember {
        mutableStateListOf(
            Message(
                id = "m1",
                conversationId = conversationId,
                senderId = "peer-alya",
                content = "Hai, sudah nyoba versi barunya?",
                timestamp = 1_719_800_000_000L,
                isOutgoing = false,
            ),
            Message(
                id = "m2",
                conversationId = conversationId,
                senderId = "self",
                content = "Belum, link-nya mana?",
                timestamp = 1_719_800_060_000L,
                isOutgoing = true,
            ),
            Message(
                id = "m3",
                conversationId = conversationId,
                senderId = "peer-alya",
                content = "Aku kirim lewat data channel aja ya, lebih aman.",
                timestamp = 1_719_800_120_000L,
                isOutgoing = false,
            ),
            Message(
                id = "m4",
                conversationId = conversationId,
                senderId = "self",
                content = "Oke, tunggu di sini.",
                timestamp = 1_719_800_180_000L,
                isOutgoing = true,
            ),
        )
    }

    // Auto-scroll to the newest message whenever the list grows.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    var draft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali",
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = peerDisplayName)
                        Spacer(Modifier.size(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = "Terenkripsi end-to-end",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            MessageInputBar(
                text = draft,
                onTextChange = { draft = it },
                onAttachClick = { /* TODO: open image picker / file chooser */ },
                onSendClick = {
                    if (draft.isNotBlank()) {
                        sendMessage(draft, conversationId, messages)
                        draft = ""
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // E2E encryption notice banner.
            EncryptionBanner(modifier = Modifier.fillMaxWidth())

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = messages,
                    key = { it.id },
                ) { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}

/** Banner asserting E2E encryption for this conversation. */
@Composable
private fun EncryptionBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "\uD83D\uDD10 Terenkripsi end-to-end",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
    }
}

/** A single message bubble, right-aligned if outgoing, left-aligned if incoming. */
@Composable
private fun MessageBubble(message: Message) {
    val isOutgoing = message.isOutgoing
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onBubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val timeText = formatTimestamp(message.timestamp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = onBubbleColor,
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = onBubbleColor.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .align(if (isOutgoing) Alignment.End else Alignment.Start),
                    )
                }
            }
        }
    }
}

/** Bottom input bar: attach + text field + send. */
@Composable
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onSendClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onAttachClick) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Lampirkan gambar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = "Pesan...") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
            )
            IconButton(
                onClick = onSendClick,
                enabled = text.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Kirim",
                    tint = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Appends a new outgoing message to the thread.
 *
 * TODO: real impl delegates to ChatViewModel.send(content), which encrypts the
 * payload with the active Signal session and dispatches it over the peer's
 * WebRTC data channel. The returned local message id tracks SENT/DELIVERED state.
 */
private fun sendMessage(
    content: String,
    conversationId: String,
    messages: androidx.compose.runtime.snapshots.SnapshotStateList<Message>,
) {
    messages.add(
        Message(
            id = "local-${System.currentTimeMillis()}",
            conversationId = conversationId,
            senderId = "self",
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
        )
    )
}

/** Formats epoch-millis to HH:mm. TODO: shared util. */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
