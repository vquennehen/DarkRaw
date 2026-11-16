// File: app/src/main/java/com/darkroom/raw/MainActivity.kt
package com.darkroom.raw

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.darkroom.raw.data.PresetConverters
import com.darkroom.raw.data.PresetDatabase
import com.darkroom.raw.data.PresetEntity
import com.darkroom.raw.data.RawNativeBridge
import com.darkroom.raw.model.DevelopParams
import com.darkroom.raw.ui.screens.DarkroomScreen
import com.darkroom.raw.ui.screens.ExportFormat
import com.darkroom.raw.worker.BatchPrintWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var activeNativeBuffer: ByteBuffer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = PresetDatabase.getInstance(this)
        val converter = PresetConverters()
        val workManager = WorkManager.getInstance(this)

        setContent {
            val presets by db.presetDao().observeAllPresets().collectAsState(initial = emptyList())
            var rollUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
            var selectedIdx by remember { mutableIntStateOf(0) }
            val rollGrades = remember { mutableStateMapOf<Uri, DevelopParams>() }

            var rawBuffer by remember { mutableStateOf<ByteBuffer?>(null) }
            var rawWidth by remember { mutableIntStateOf(0) }
            var rawHeight by remember { mutableIntStateOf(0) }
            var metaText by remember { mutableStateOf("") }
            var activeWorkId by remember { mutableStateOf<UUID?>(null) }
            var batchProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

            // Observe background batch print progress
            LaunchedEffect(activeWorkId) {
                val id = activeWorkId ?: return@LaunchedEffect
                workManager.getWorkInfoByIdFlow(id).collect { info ->
                    if (info != null) {
                        val done = info.progress.getInt(BatchPrintWorker.KEY_PROGRESS_DONE, 0)
                        val total = info.progress.getInt(BatchPrintWorker.KEY_PROGRESS_TOTAL, 0)
                        batchProgress = if (info.state == WorkInfo.State.RUNNING && total > 0) {
                            done to total
                        } else {
                            null
                        }
                    }
                }
            }

            fun loadNegativeAtIndex(index: Int, uris: List<Uri>) {
                if (index !in uris.indices) return
                selectedIdx = index
                val targetUri = uris[index]
                lifecycleScope.launch(Dispatchers.IO) {
                    val meta = IntArray(6)
                    val decoded = contentResolver.openFileDescriptor(targetUri, "r")?.use { pfd ->
                        RawNativeBridge.decodeRawFromFd(pfd.fd, false, meta)
                    }
                    withContext(Dispatchers.Main) {
                        activeNativeBuffer?.let { RawNativeBridge.freeNativeBuffer(it) }
                        activeNativeBuffer = decoded
                        rawBuffer = decoded
                        rawWidth = meta[0]
                        rawHeight = meta[1]
                        metaText = "${meta[0]}x${meta[1]} | ISO ${meta[2]} | 1/${meta[3]}s | f/${meta[4] / 10f}"
                    }
                }
            }

            val currentUri = rollUris.getOrNull(selectedIdx)
            val currentParams = currentUri?.let { rollGrades[it] } ?: DevelopParams()

            DarkroomScreen(
                rollUris = rollUris,
                selectedIndex = selectedIdx,
                activeRawBuffer = rawBuffer,
                activeRawWidth = rawWidth,
                activeRawHeight = rawHeight,
                metadataSummary = metaText,
                currentParams = currentParams,
                presets = presets,
                batchProgress = batchProgress,
                onImportRoll = { uris ->
                    rollUris = uris
                    uris.forEach { uri -> rollGrades.putIfAbsent(uri, DevelopParams()) }
                    loadNegativeAtIndex(0, uris)
                },
                onSelectNegative = { idx -> loadNegativeAtIndex(idx, rollUris) },
                onParamsChange = { updated ->
                    currentUri?.let { rollGrades[it] = updated }
                },
                onSaveCustomPreset = { name, params ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.presetDao().insertPreset(
                            PresetEntity(
                                name = name,
                                isBuiltIn = false,
                                paramsJson = converter.fromParams(params)
                            )
                        )
                    }
                },
                onApplyGradeToEntireRoll = { heroParams ->
                    rollUris.forEach { uri -> rollGrades[uri] = heroParams }
                },
                onTriggerBatchPrint = { format, jpegQuality ->
                    val uriArray = rollUris.map { it.toString() }.toTypedArray()
                    val paramsArray = rollUris.map { uri ->
                        converter.fromParams(rollGrades[uri] ?: DevelopParams())
                    }.toTypedArray()

                    val request = OneTimeWorkRequestBuilder<BatchPrintWorker>()
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setInputData(
                            workDataOf(
                                BatchPrintWorker.KEY_URI_ARRAY to uriArray,
                                BatchPrintWorker.KEY_PARAMS_JSON_ARRAY to paramsArray,
                                BatchPrintWorker.KEY_EXPORT_TIFF16 to (format == ExportFormat.TIFF_16BIT),
                                BatchPrintWorker.KEY_JPEG_QUALITY to jpegQuality
                            )
                        )
                        .build()

                    activeWorkId = request.id
                    workManager.enqueueUniqueWork(
                        "darkroom_batch_print",
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeNativeBuffer?.let {
            RawNativeBridge.freeNativeBuffer(it)
            activeNativeBuffer = null
        }
    }
}
