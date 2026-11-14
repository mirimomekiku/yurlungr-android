package dev.yurlungr.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Phosphor (MIT) glyphs added after the generated set; same 256-unit grid. */
private fun extra(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 256f, 256f)
        .addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        .build()

val Ph.Microphone: ImageVector by lazy {
    extra("Microphone", "M128,176a48.05,48.05,0,0,0,48-48V64a48,48,0,0,0-96,0v64A48.05,48.05,0,0,0,128,176ZM96,64a32,32,0,0,1,64,0v64a32,32,0,0,1-64,0Zm40,143.6V232a8,8,0,0,1-16,0V207.6A80.11,80.11,0,0,1,48,128a8,8,0,0,1,16,0,64,64,0,0,0,128,0,8,8,0,0,1,16,0A80.11,80.11,0,0,1,136,207.6Z")
}
