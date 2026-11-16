// File: app/src/main/java/com/darkroom/raw/ui/components/PrecisionSlider.kt
package com.darkroom.raw.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun PrecisionSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    formattedValue: String,
    hapticBuckets: Int = 40,
    onValueChange: (Float) -> Unit
) {
    val view = LocalView.current
    var lastBucket by remember { mutableIntStateOf(-1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(defaultValue) {
                detectTapGestures(
                    onDoubleTap = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onValueChange(defaultValue)
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                color = Color(0xFFB0B0B0),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formattedValue,
                color = if (value != defaultValue) Color(0xFFFF6F00) else Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Slider(
            value = value,
            onValueChange = { newValue ->
                val norm = (newValue - valueRange.start) / (valueRange.endInclusive - valueRange.start)
                val bucket = (norm * hapticBuckets).roundToInt()
                if (bucket != lastBucket) {
                    lastBucket = bucket
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                onValueChange(newValue)
            },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF6F00),
                activeTrackColor = Color(0xFFD32F2F),
                inactiveTrackColor = Color(0xFF242424)
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}
