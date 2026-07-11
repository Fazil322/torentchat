package com.torentchat.ui.screens.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * QR scanning screen used to add a new peer.
 *
 * The peer's QR encodes their peerId + Signal identity key. After scanning (or
 * manual entry), a session is established via the signaling server, the X3DH
 * key agreement runs, and a WebRTC data channel is opened — at which point the
 * conversation is ready and [onPeerConnected] is called.
 *
 * TODO: integrate ZXing camera scan (zxing-android-embedded) and wire to a
 * ScanViewModel (Hilt) that performs peer lookup + session bootstrap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onPeerConnected: () -> Unit,
    onBack: () -> Unit,
) {
    // TODO: val viewModel: ScanViewModel = hiltViewModel()
    //  viewModel.scannedPayload — Flow<String?>
    //  viewModel.connectToPeer(peerPayload) -> triggers X3DH + WebRTC + conversation creation

    var manualPeerId by remember { mutableStateOf("") }

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
                title = { Text(text = "Hubungkan Teman") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // Camera preview placeholder (dashed square).
            CameraPreviewPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp)
                    .aspectRatio(1f),
                onScanDetected = onPeerConnected, // TODO: real scan callback
            )

            Text(
                text = "Arahkan kamera ke QR code teman",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))

            // Manual entry fallback.
            Text(
                text = "Atau masukkan ID manual",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = manualPeerId,
                onValueChange = { manualPeerId = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = "Contoh: 7f3a...peer-id") },
                singleLine = true,
            )

            Button(
                onClick = {
                    // TODO: viewModel.connectToPeer(manualPeerId)
                    if (manualPeerId.isNotBlank()) onPeerConnected()
                },
                enabled = manualPeerId.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text(text = "Hubungkan")
            }

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = {
                    // TODO: show own QR (reuse ProfileScreen QR) — for now just a hint.
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(text = "Tampilkan QR code saya")
            }
        }
    }
}

/**
 * Dashed-border square standing in for the live camera preview. TODO: replace
 * with a ZXing DecoratedBarcodeView once camera permissions are granted.
 */
@Composable
private fun CameraPreviewPlaceholder(
    modifier: Modifier = Modifier,
    onScanDetected: () -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .drawDashedBorder(borderColor)
            .drawBehind { drawRect(backgroundColor.copy(alpha = 0.3f)) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.QrCode2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            )
            Text(
                text = "Pratinjau kamera",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Hidden affordance to simulate a successful scan while no camera is wired.
            OutlinedButton(onClick = onScanDetected) {
                Text(text = "Simulasi scan")
            }
        }
    }
}

/** Draws a rounded dashed border around the composable. */
private fun Modifier.drawDashedBorder(color: Color): Modifier =
    this.then(
        Modifier.drawBehind {
            val thickness = 3.dp.toPx()
            val dash = 16.dp.toPx()
            val gap = 12.dp.toPx()
            val effect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f)
            drawRoundRect(
                color = color,
                size = size,
                style = Stroke(
                    width = thickness,
                    pathEffect = effect,
                ),
                topLeft = Offset(0f, 0f),
            )
        }
    )
