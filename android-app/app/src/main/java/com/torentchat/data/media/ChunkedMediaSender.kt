package com.torentchat.data.media

import com.torentchat.crypto.Envelope
import com.torentchat.crypto.SignalSessionManager
import com.torentchat.webrtc.DataChannelTransport
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends images as E2E-encrypted chunks over the P2P data channel.
 * ─────────────────────────────────────────────────────────────────────────────
 * Images are split into [CHUNK_SIZE]-byte pieces. Each piece is individually
 * encrypted via Signal Protocol and sent as an [Envelope] with
 * [Envelope.CONTENT_IMAGE]. The recipient reassembles chunks by [transferId].
 *
 * A "manifest" envelope (also encrypted) is sent first with metadata:
 *   • total size, chunk count, MIME type, original filename.
 *
 * This approach means:
 *   • No unencrypted image data ever touches the network or relay.
 *   • If the connection drops mid-transfer, only the missing chunks need resend.
 *   • Large images don't block the data channel (chunks interleave with text).
 *
 * Fallback: if the peer is offline, chunks go through the KV pending store
 * instead of the data channel (still E2E-encrypted).
 */
@Singleton
class ChunkedMediaSender @Inject constructor(
    private val crypto: SignalSessionManager,
) {
    /** The result of chunking + encrypting an image. */
    data class ChunkedTransfer(
        val transferId: String,
        val manifest: Envelope,
        val chunks: List<Envelope>,
    )

    /**
     * Prepare an image for encrypted transfer.
     *
     * @param imageData raw image bytes (already compressed/resized by caller)
     * @param mimeType  e.g. "image/jpeg"
     * @param recipientId the peer who will receive it
     * @param senderId   our peer ID
     */
    fun prepare(
        imageData: ByteArray,
        mimeType: String,
        recipientId: String,
        senderId: String,
    ): ChunkedTransfer {
        val transferId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Encrypt & send manifest first (metadata about the transfer)
        val manifestJson = """{"transferId":"$transferId","size":${imageData.size},"mimeType":"$mimeType","chunks":${(imageData.size + CHUNK_SIZE - 1) / CHUNK_SIZE}}"""
        val manifestEnvelope = crypto.encrypt(
            recipientId,
            manifestJson.toByteArray(),
            Envelope.CONTENT_IMAGE,
        ).copy(messageId = "$transferId-manifest", timestamp = timestamp)

        // Split + encrypt each chunk
        val chunks = imageData.toList().chunked(CHUNK_SIZE).mapIndexed { index, chunkBytes ->
            val chunkData = chunkBytes.toByteArray()
            // Prefix each chunk with a 4-byte index for reassembly ordering
            val prefixed = ByteArray(4 + chunkData.size)
            prefixed[0] = (index shr 24).toByte()
            prefixed[1] = (index shr 16).toByte()
            prefixed[2] = (index shr 8).toByte()
            prefixed[3] = index.toByte()
            System.arraycopy(chunkData, 0, prefixed, 4, chunkData.size)

            crypto.encrypt(recipientId, prefixed, Envelope.CONTENT_IMAGE)
                .copy(messageId = "$transferId-$index", timestamp = timestamp)
        }

        return ChunkedTransfer(transferId, manifestEnvelope, chunks)
    }

    /**
     * Send a prepared transfer over the P2P data channel.
     * Returns the number of chunks successfully sent.
     */
    suspend fun send(transfer: ChunkedTransfer, transport: DataChannelTransport): Int {
        if (!transport.send(transfer.manifest)) return 0
        var sent = 0
        for (chunk in transfer.chunks) {
            if (transport.send(chunk)) sent++
            else break
        }
        return sent
    }

    companion object {
        /** 16 KB per chunk — balances overhead vs. latency on mobile networks. */
        const val CHUNK_SIZE = 16 * 1024
    }
}
