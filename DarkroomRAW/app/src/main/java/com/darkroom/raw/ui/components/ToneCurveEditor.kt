// File: app/src/main/java/com/darkroom/raw/ui/components/ToneCurveEditor.kt
package com.darkroom.raw.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkroom.raw.model.CurvePoint
import com.darkroom.raw.model.DevelopParams
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SafelightRed = Color(0xFFD32F2F)
private val SafelightAmber = Color(0xFFFF6F00)
private val GridLineColor = Color(0xFF262626)

@Composable
fun ToneCurveEditor(
    points: List<CurvePoint>,
    onCurveChanged: (List<CurvePoint>) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var activeIndex by remember { mutableIntStateOf(-1) }
    var lastHapticBucket by remember { mutableIntStateOf(-1) }
    val currentPoints by rememberUpdatedState(points)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LUMINANCE TONE CURVE (SHDW / MID / HL)",
                color = Color(0xFFCCCCCC),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            val readout = if (activeIndex in currentPoints.indices) {
                val pt = currentPoints[activeIndex]
                "IN ${(pt.x * 100).roundToInt()}% -> OUT ${(pt.y * 100).roundToInt()}%"
            } else {
                "DBL-TAP TO RESET"
            }
            Text(
                text = readout,
                color = SafelightAmber,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(Color.Black)
                .border(1.dp, GridLineColor)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onCurveChanged(DevelopParams.DEFAULT_CURVE)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            var closestIdx = -1
                            var minDist = 48.dp.toPx()
                            currentPoints.forEachIndexed { idx, pt ->
                                val px = pt.x * w
                                val py = (1f - pt.y) * h
                                val d = hypot(offset.x - px, offset.y - py)
                                if (d < minDist) {
                                    minDist = d
                                    closestIdx = idx
                                }
                            }
                            activeIndex = closestIdx
                            if (activeIndex != -1) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        },
                        onDragEnd = { activeIndex = -1 },
                        onDragCancel = { activeIndex = -1 },
                        onDrag = { change, _ ->
                            val idx = activeIndex
                            if (idx in currentPoints.indices) {
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)

                                val minX = if (idx == 0) 0f else currentPoints[idx - 1].x + 0.04f
                                val maxX = if (idx == currentPoints.lastIndex) 1f else currentPoints[idx + 1].x - 0.04f

                                val newX = if (idx == 0 || idx == currentPoints.lastIndex) {
                                    currentPoints[idx].x
                                } else {
                                    (change.position.x / w).coerceIn(minX, maxX)
                                }
                                val newY = (1f - (change.position.y / h)).coerceIn(0f, 1f)

                                val bucket = (newY * 25).roundToInt()
                                if (bucket != lastHapticBucket) {
                                    lastHapticBucket = bucket
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }

                                val updated = currentPoints.toMutableList()
                                updated[idx] = CurvePoint(newX, newY)
                                onCurveChanged(updated)
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Draw rule-of-quarters darkroom enlarger grid & reference diagonal
                for (i in 1..3) {
                    val x = w * (i / 4f)
                    val y = h * (i / 4f)
                    drawLine(GridLineColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
                    drawLine(GridLineColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                }
                drawLine(Color(0xFF333333), Offset(0f, h), Offset(w, 0f), strokeWidth = 1.dp.toPx())

                // Render Monotone Cubic Hermite Spline Path
                val lut = DevelopParams.buildMonotoneCubicLut(currentPoints, 128)
                val curvePath = Path()
                lut.forEachIndexed { i, yVal ->
                    val px = (i.toFloat() / (lut.size - 1)) * w
                    val py = (1f - yVal) * h
                    if (i == 0) curvePath.moveTo(px, py) else curvePath.lineTo(px, py)
                }
                drawPath(
                    path = curvePath,
                    color = SafelightRed,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // Draw Anchor Control Nodes (Black, Shadow, Midtone, Highlight, White)
                currentPoints.forEachIndexed { idx, pt ->
                    val center = Offset(pt.x * w, (1f - pt.y) * h)
                    val isSelected = idx == activeIndex
                    drawCircle(
                        color = if (isSelected) SafelightAmber else SafelightRed,
                        radius = if (isSelected) 7.dp.toPx() else 5.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = 2.5.dp.toPx(),
                        center = center
                    )
                }
            }
        }
    }
}
