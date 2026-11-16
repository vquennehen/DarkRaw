// File: app/src/main/java/com/darkroom/raw/data/RawNativeBridge.kt
package com.darkroom.raw.data

import java.nio.ByteBuffer

object RawNativeBridge {
    init {
        System.loadLibrary("darkroom_raw")
    }

    external fun decodeRawFromFd(
        fd: Int,
        halfSizePreview: Boolean,
        outMetadata: IntArray
    ): ByteBuffer?

    external fun freeNativeBuffer(directBuffer: ByteBuffer)

    external fun write16BitTiffToFd(
        outFd: Int,
        rgb16ShortBuffer: ByteBuffer,
        width: Int,
        height: Int
    ): Boolean
}
