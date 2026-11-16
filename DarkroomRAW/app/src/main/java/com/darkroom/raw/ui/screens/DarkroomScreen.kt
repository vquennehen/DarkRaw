// File: app/src/main/java/com/darkroom/raw/ui/screens/DarkroomScreen.kt
package com.darkroom.raw.ui.screens

import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.darkroom.raw.data.PresetConverters
import com.darkroom.raw.data.PresetEntity
import com.darkroom.raw.gl.RawGlSurfaceView
import com.darkroom.raw.model.DevelopParams
import com.darkroom.raw.ui.components.PrecisionSlider
import com.darkroom.raw.ui.components.ToneCurveEditor
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.roundToInt

private val OledBlack = Color(0xFF000000)
private val ControlPanelCharcoal = Color(0xFF121212)
private val SafelightRed = Color(0xFFD32F2F)
private val SafelightAmber = Color(0xFFFF6F00)

enum class ExportFormat { JPEG_95, TIFF_16BIT }

@Composable
fun DarkroomScreen(
    rollUris: List<Uri>,
    selectedIndex: Int,
    activeRawBuffer: ByteBuffer?,
    activeRawWidth: Int,
    activeRawHeight: Int,
    metadataSummary: String,
    currentParams: DevelopParams,
    presets: List<PresetEntity>,
    batchProgress: Pair<Int, Int>?, // (completed, total)
    onImportRoll: (List<Uri>) -> Unit,
    onSelectNegative: (Int) -> Unit,
    onParamsChange: (DevelopParams) -> Unit,
    onSaveCustomPreset: (String, DevelopParams) -> Unit,
    onApplyGradeToEntireRoll: (DevelopParams) -> Unit,
    onTriggerBatchPrint: (ExportFormat, Int) -> Unit
) {
    val view = LocalView.current
    val converter = remember { PresetConverters() }
    var activeTab by remember { mutableIntStateOf(0) } // 0=Tone/WB, 1=Curve, 2=Presets & Batch Print
    var exportFormat by remember { mutableStateOf(ExportFormat.JPEG_95) }
    var jpegQuality by remember { mutableIntStateOf(95) }
    var newPresetName by remember { mutableStateOf("") }

    val rollPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportRoll(uris)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .systemBarsPadding()
    ) {
        // Top Safelight Technical HUD Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ControlPanelCharcoal)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DARKROOM // 16F RAW ENGINE",
                    color = SafelightRed,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = metadataSummary.ifEmpty { "NO NEGATIVE LOADED (.CR2 / .DNG / .NEF / .ARW)" },
                    color = Color(0xFF9E9E9E),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            OutlinedButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    rollPicker.launch(arrayOf("*/*"))
                },
                border = BorderStroke(1.dp, SafelightRed),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SafelightAmber),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("1. IMPORT ROLL (${rollUris.size})", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Contact-Sheet Negative Strip
        if (rollUris.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF080808))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(rollUris) { idx, uri ->
                    val isSelected = idx == selectedIndex
                    val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "FRAME_${idx + 1}"
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) Color(0xFF220909) else ControlPanelCharcoal,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) SafelightAmber else Color(0xFF2A2A2A),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onSelectNegative(idx)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "#${idx + 1} $fileName",
                            color = if (isSelected) SafelightAmber else Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // OpenGL ES 3.0 16-Bit Half-Float Live Enlarger Viewport (60fps)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(OledBlack),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx -> RawGlSurfaceView(ctx) },
                update = { glView ->
                    if (activeRawBuffer != null && activeRawWidth > 0 && activeRawHeight > 0) {
                        glView.setRawBuffer(activeRawBuffer, activeRawWidth, activeRawHeight)
                    }
                    glView.updateParams(currentParams)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom Distraction-Free Darkroom Enlarger Drawer (#121212)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .background(ControlPanelCharcoal)
        ) {
            // Mode Switcher Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf("2A. DEVELOP DIALS", "2B. TONE CURVE", "3. PRESETS & PRINT")
                tabs.forEachIndexed { idx, title ->
                    val active = activeTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (active) SafelightRed.copy(alpha = 0.22f) else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (active) SafelightRed else Color(0xFF262626),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                activeTab = idx
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (active) SafelightAmber else Color(0xFF9E9E9E),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Tab Body
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                when (activeTab) {
                    0 -> {
                        PrecisionSlider(
                            label = "Exposure",
                            value = currentParams.exposureEv,
                            valueRange = -4.0f..4.0f,
                            defaultValue = 0.0f,
                            formattedValue = String.format(Locale.US, "%+.2f EV", currentParams.exposureEv),
                            onValueChange = { onParamsChange(currentParams.copy(exposureEv = it)) }
                        )
                        PrecisionSlider(
                            label = "Contrast",
                            value = currentParams.contrast,
                            valueRange = -100f..100f,
                            defaultValue = 0f,
                            formattedValue = String.format(Locale.US, "%+d", currentParams.contrast.roundToInt()),
                            onValueChange = { onParamsChange(currentParams.copy(contrast = it)) }
                        )
                        PrecisionSlider(
                            label = "Highlights",
                            value = currentParams.highlights,
                            valueRange = -100f..100f,
                            defaultValue = 0f,
                            formattedValue = String.format(Locale.US, "%+d", currentParams.highlights.roundToInt()),
                            onValueChange = { onParamsChange(currentParams.copy(highlights = it)) }
                        )
                        PrecisionSlider(
                            label = "Shadows",
                            value = currentParams.shadows,
                            valueRange = -100f..100f,
                            defaultValue = 0f,
                            formattedValue = String.format(Locale.US, "%+d", currentParams.shadows.roundToInt()),
                            onValueChange = { onParamsChange(currentParams.copy(shadows = it)) }
                        )
                        PrecisionSlider(
                            label = "White Balance (Kelvin)",
                            value = currentParams.temperatureKelvin,
                            valueRange = 2000f..10000f,
                            defaultValue = 5500f,
                            formattedValue = "${currentParams.temperatureKelvin.roundToInt()} K",
                            onValueChange = { onParamsChange(currentParams.copy(temperatureKelvin = it)) }
                        )
                        PrecisionSlider(
                            label = "Tint (G / M)",
                            value = currentParams.tint,
                            valueRange = -100f..100f,
                            defaultValue = 0f,
                            formattedValue = String.format(Locale.US, "%+d", currentParams.tint.roundToInt()),
                            onValueChange = { onParamsChange(currentParams.copy(tint = it)) }
                        )
                        PrecisionSlider(
                            label = "Vibrance (Skin-Safe)",
                            value = currentParams.vibrance,
                            valueRange = -100f..100f,
                            defaultValue = 0f,
                            formattedValue = String.format(Locale.US, "%+d%%", currentParams.vibrance.roundToInt()),
                            onValueChange = { onParamsChange(currentParams.copy(vibrance = it)) }
                        )
                        PrecisionSlider(
                            label = "Saturation",
                            value = currentParams.saturation,
                            valueRange = -100f..100f,
                            defaultValue = 0f,
                            formattedValue = String.format(Locale.US, "%+d%%", currentParams.saturation.roundToInt()),
                            onValueChange = { onParamsChange(currentParams.copy(saturation = it)) }
                        )
                        PrecisionSlider(
                            label = "Luminance Sharpening",
                            value = currentParams.sharpening,
                            valueRange = 0f..100f,
                            defaultValue = 15f,
                            formattedValue = "${currentParams.sharpening.roundToInt()}%",
                            onValueChange = { onParamsChange(currentParams.copy(sharpening = it)) }
                        )
                    }

                    1 -> {
                        ToneCurveEditor(
                            points = currentParams.toneCurve,
                            onCurveChanged = { updatedCurve ->
                                onParamsChange(currentParams.copy(toneCurve = updatedCurve))
                            }
                        )
                    }

                    2 -> {
                        Text(
                            text = "FILM STOCK PRESETS",
                            color = SafelightAmber,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(presets) { _, preset ->
                                OutlinedButton(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        onParamsChange(converter.toParams(preset.paramsJson))
                                    },
                                    border = BorderStroke(1.dp, SafelightRed),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Text(preset.name.uppercase(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Save Custom Preset Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newPresetName,
                                onValueChange = { newPresetName = it },
                                placeholder = {
                                    Text("Custom Preset Name...", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newPresetName.isNotBlank()) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        onSaveCustomPreset(newPresetName.trim(), currentParams)
                                        newPresetName = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SafelightRed)
                            ) {
                                Text("SAVE PRESET", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Batch Sync & Export Format Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onApplyGradeToEntireRoll(currentParams)
                                },
                                border = BorderStroke(1.dp, SafelightAmber),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    "SYNC HERO TO ROLL (${rollUris.size})",
                                    color = SafelightAmber,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    exportFormat = if (exportFormat == ExportFormat.JPEG_95) {
                                        ExportFormat.TIFF_16BIT
                                    } else {
                                        ExportFormat.JPEG_95
                                    }
                                },
                                border = BorderStroke(1.dp, Color(0xFF555555)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (exportFormat == ExportFormat.JPEG_95) {
                                        "FMT: JPEG ($jpegQuality%)"
                                    } else {
                                        "FMT: 16-BIT TIFF"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        if (exportFormat == ExportFormat.JPEG_95) {
                            PrecisionSlider(
                                label = "JPEG Export Quality",
                                value = jpegQuality.toFloat(),
                                valueRange = 90f..100f,
                                defaultValue = 95f,
                                formattedValue = "$jpegQuality%",
                                hapticBuckets = 10,
                                onValueChange = { jpegQuality = it.roundToInt() }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                onTriggerBatchPrint(exportFormat, jpegQuality)
                            },
                            enabled = rollUris.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = SafelightRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val statusLabel = batchProgress?.let { (done, total) ->
                                "DEVELOPING ROLL: $done / $total FRAMES..."
                            } ?: "BATCH PRINT ROLL (${rollUris.size} NEGATIVES)"
                            Text(
                                text = statusLabel,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
