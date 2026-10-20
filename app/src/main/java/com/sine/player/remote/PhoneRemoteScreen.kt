package com.sine.player.remote

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sine.player.SineApp
import com.sine.player.ui.Page
import com.sine.player.ui.SettingRow
import com.sine.player.ui.theme.SineColors

/** Turn the phone remote on and show its link as a QR code. */
@Composable
fun PhoneRemoteScreen() {
    val remote = SineApp.instance.phoneRemote
    val url = if (remote.enabled) remote.url() else null
    Page("Phone remote", "Type into text boxes and control Sine from your phone's browser, on the same network") {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingRow("Phone remote", "Off when you don't use it: anyone with the link on your network can control Sine", if (remote.enabled) "On" else "Off", {
                    remote.setEnabled(!remote.enabled)
                })
                if (remote.enabled) {
                    SettingRow("New link", "Stops old links (and phones that bookmarked them) from working", "", { remote.newLink() })
                }
                Text(
                    when {
                        !remote.enabled -> "Turn it on to get a link."
                        url == null -> "Sine couldn't find this TV's network address. Is it connected?"
                        else -> "On your phone, scan the code or open:\n$url"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = SineColors.text,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    "Select a text box on the TV, type on the phone and press Send. The page also has a D-pad, media keys, a seek bar, and can play a link on the TV.",
                    color = SineColors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (url != null) {
                val qr = remember(url) { qrBitmap(url, 512) }
                Image(
                    qr.asImageBitmap(),
                    contentDescription = "QR code for $url",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(280.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp),
                )
            }
        }
    }
}

/** Black-on-white QR code; nearest-neighbour scaling keeps it sharp. */
fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
