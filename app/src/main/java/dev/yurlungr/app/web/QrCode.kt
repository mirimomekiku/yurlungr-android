package dev.yurlungr.app.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** QR code modules for a link: [size]×[size], true = dark. No quiet zone; the drawing adds it. */
internal class QrModules(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int) = dark[y * size + x]

    companion object {
        fun encode(text: String): QrModules {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val size = matrix.width
            return QrModules(size, BooleanArray(size * size) { matrix.get(it % size, it / size) })
        }
    }
}

/** A scannable QR code for [text]: dark on white whatever the theme, since scanners expect that. */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules by produceState<QrModules?>(null, text) { value = withContext(Dispatchers.Default) { runCatching { QrModules.encode(text) }.getOrNull() } }
    Box(
        modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)
            .semantics { contentDescription = "QR code for $text" },
    ) {
        val qr = modules ?: return@Box
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cell = size.width / qr.size
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                // Slightly oversized cells, so no hairline gaps show between neighbours.
                if (qr[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
