package com.torentchat.identity

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates QR codes for invite links.
 * ─────────────────────────────────────────────────────────────────────────────
 * The QR encodes the [InvitePayload.toUri] string. Scanning it with the in-app
 * scanner (or any QR reader) produces a `torentchat://invite?...` deep link
 * that the app handles via its intent filter.
 */
object QrCodeGenerator {

    /** @return a QR code bitmap for the given [payload], or null on failure. */
    fun generate(payload: InvitePayload, size: Int = 512): Bitmap? {
        return try {
            val uri = payload.toUri()
            val bitMatrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
