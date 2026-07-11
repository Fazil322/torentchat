package com.torentchat.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The user's own profile / settings screen.
 *
 * Shows the local anonymous identity (random peer id + Signal identity key),
 * a QR code that peers scan to connect, an editable display name, security
 * toggles, and an "about" section with the app version.
 *
 * TODO: wire to a ProfileViewModel (Hilt) that:
 *  - loads the local Peer from IdentityRepository
 *  - generates the QR payload (peerId + identity key fingerprint)
 *  - persists displayName + toggle preferences to SettingsRepository
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
) {
    // TODO: val viewModel: ProfileViewModel = hiltViewModel()
    //  val peer by viewModel.peer.collectAsStateWithLifecycle()
    //  val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Mock local identity. peerId is the fingerprint of the Curve25519 public key.
    val peerId = remember { "7f3a9c2e-b4d1-4a8f-9e0c-1b2d3f4a5b6c" }

    var displayName by remember { mutableStateOf("") } // empty => use peer id
    var ephemeralMessages by remember { mutableStateOf(true) }
    var hideOnlineStatus by remember { mutableStateOf(false) }

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
                title = { Text(text = "Profil") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── QR code + identity ────────────────────────────────────────────
            QrCodeCard(
                peerId = peerId,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Display name ──────────────────────────────────────────────────
            DisplayNameEditor(
                displayName = displayName,
                onDisplayNameChange = { displayName = it },
                onSave = {
                    // TODO: viewModel.updateDisplayName(displayName)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Keamanan section ──────────────────────────────────────────────
            SettingsSection(title = "Keamanan") {
                ToggleRow(
                    label = "Pesan sementara (auto-hapus)",
                    checked = ephemeralMessages,
                    onCheckedChange = {
                        ephemeralMessages = it
                        // TODO: viewModel.setEphemeralMessages(it)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                ToggleRow(
                    label = "Sembunyikan status online",
                    checked = hideOnlineStatus,
                    onCheckedChange = {
                        hideOnlineStatus = it
                        // TODO: viewModel.setHideOnlineStatus(it)
                    },
                )
            }

            // ── Tentang section ───────────────────────────────────────────────
            SettingsSection(title = "Tentang") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Versi aplikasi",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "TorentChat v0.1.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                // TODO: link to security whitepaper (open external URL).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Whitepaper keamanan",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "TODO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** QR code placeholder card with the user's peer id underneath. */
@Composable
private fun QrCodeCard(
    peerId: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // QR placeholder — real impl renders a ZXing BarcodeEncoder bitmap here.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(180.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.QrCode2,
                        contentDescription = "QR Code Anda",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(96.dp),
                    )
                }
            }
            Text(
                text = "QR Code Anda",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "ID: $peerId",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 280.dp),
            )
        }
    }
}

/** Editable display name field with a save button. */
@Composable
private fun DisplayNameEditor(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "Nama tampilan") },
            placeholder = { Text(text = "(opsional)") },
            singleLine = true,
        )
        Button(
            onClick = onSave,
            enabled = displayName.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Save,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(text = "Simpan nama")
        }
    }
}

/** A titled settings container with a card surface. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                content()
            }
        }
    }
}

/** A label + Switch row used inside [SettingsSection]. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
