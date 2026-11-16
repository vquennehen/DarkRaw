// File: app/src/main/cpp/libraw_bridge.cpp
#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <vector>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include "libraw/libraw.h"

#define LOG_TAG "DarkroomNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Fast IEEE-754 float32 to float16 (half-float) conversion for GL_RGBA16F
static inline uint16_t floatToHalf(float val) {
    uint32_t fbits;
    std::memcpy(&fbits, &val, sizeof(float));
    uint32_t sign = (fbits >> 16) & 0x8000;
    int32_t exp = ((fbits >> 23) & 0xFF) - 127 + 15;
    uint32_t mantissa = fbits & 0x007FFFFF;

    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        mantissa = (mantissa | 0x00800000) >> (1 - exp);
        return static_cast<uint16_t>(sign | (mantissa >> 13));
    } else if (exp >= 31) {
        return static_cast<uint16_t>(sign | 0x7BFF); // Clamp to max finite half-float
    }
    return static_cast<uint16_t>(sign | (exp << 10) | (mantissa >> 13));
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_darkroom_raw_data_RawNativeBridge_decodeRawFromFd(
        JNIEnv *env,
        jobject /* this */,
        jint fd,
        jboolean halfSizePreview,
        jintArray outMetadata // [0]=width, [1]=height, [2]=iso, [3]=shutterInv, [4]=apertureX10, [5]=asShotKelvin
) {
    struct stat st{};
    if (fstat(fd, &st) < 0 || st.st_size <= 0) {
        LOGE("Invalid file descriptor or empty RAW file");
        return nullptr;
    }

    size_t fileSize = static_cast<size_t>(st.st_size);
    void *mappedFile = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mappedFile == MAP_FAILED) {
        LOGE("mmap failed for RAW file descriptor");
        return nullptr;
    }

    LibRaw rawProcessor;
    // Configure linear 16-bit demosaicing pipeline for GPU shader grading
    rawProcessor.imgdata.params.output_bps = 16;          // Preserve full 12/14-bit sensor dynamic range in 16-bit container
    rawProcessor.imgdata.params.gamm[0] = 1.0;            // Linear gamma (1.0)
    rawProcessor.imgdata.params.gamm[1] = 1.0;            // Linear slope (1.0)
    rawProcessor.imgdata.params.no_auto_bright = 1;       // Disable automatic histogram stretching
    rawProcessor.imgdata.params.use_camera_wb = 1;        // Start from camera As-Shot WB coefficients
    rawProcessor.imgdata.params.output_color = 1;         // sRGB primaries in linear light
    rawProcessor.imgdata.params.user_qual = 3;            // AHD (Adaptive Homogeneity-Directed) demosaicing
    rawProcessor.imgdata.params.half_size = halfSizePreview ? 1 : 0; // Fast quad-bayer half-size for batch preview or full res

    int ret = rawProcessor.open_buffer(mappedFile, fileSize);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("LibRaw open_buffer error: %s", libraw_strerror(ret));
        munmap(mappedFile, fileSize);
        return nullptr;
    }

    ret = rawProcessor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("LibRaw unpack error: %s", libraw_strerror(ret));
        munmap(mappedFile, fileSize);
        return nullptr;
    }

    ret = rawProcessor.dcraw_process();
    munmap(mappedFile, fileSize);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("LibRaw dcraw_process error: %s", libraw_strerror(ret));
        return nullptr;
    }

    int errCode = 0;
    libraw_processed_image_t *img = rawProcessor.dcraw_make_mem_image(&errCode);
    if (!img || img->type != LIBRAW_IMAGE_BITMAP || img->bits != 16 || img->colors != 3) {
        LOGE("LibRaw dcraw_make_mem_image failed: %d", errCode);
        if (img) LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    const int width = img->width;
    const int height = img->height;
    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t byteCapacity = pixelCount * 4 * sizeof(uint16_t); // RGBA16F (8 bytes/pixel)

    // Allocate direct native ByteBuffer for zero-copy OpenGL ES 3.0 texture upload
    auto *rgba16fBuffer = static_cast<uint16_t *>(malloc(byteCapacity));
    if (!rgba16fBuffer) {
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    const auto *srcRgb16 = reinterpret_cast<const uint16_t *>(img->data);
    const uint16_t oneHalf = floatToHalf(1.0f);

    #pragma omp parallel for schedule(static)
    for (size_t i = 0; i < pixelCount; ++i) {
        float r = static_cast<float>(srcRgb16[i * 3 + 0]) / 65535.0f;
        float g = static_cast<float>(srcRgb16[i * 3 + 1]) / 65535.0f;
        float b = static_cast<float>(srcRgb16[i * 3 + 2]) / 65535.0f;

        rgba16fBuffer[i * 4 + 0] = floatToHalf(r);
        rgba16fBuffer[i * 4 + 1] = floatToHalf(g);
        rgba16fBuffer[i * 4 + 2] = floatToHalf(b);
        rgba16fBuffer[i * 4 + 3] = oneHalf;
    }

    // Populate metadata array for UI HUD & EXIF preservation
    jint meta[6];
    meta[0] = width;
    meta[1] = height;
    meta[2] = static_cast<jint>(rawProcessor.imgdata.other.iso_speed);
    meta[3] = rawProcessor.imgdata.other.shutter > 0.0f
              ? static_cast<jint>(std::round(1.0f / rawProcessor.imgdata.other.shutter))
              : 0;
    meta[4] = static_cast<jint>(std::round(rawProcessor.imgdata.other.aperture * 10.0f));
    meta[5] = 5500; // Neutral daylight baseline corresponding to camera As-Shot WB
    env->SetIntArrayRegion(outMetadata, 0, 6, meta);

    LibRaw::dcraw_clear_mem(img);
    rawProcessor.recycle();

    return env->NewDirectByteBuffer(rgba16fBuffer, static_cast<jlong>(byteCapacity));
}

extern "C" JNIEXPORT void JNICALL
Java_com_darkroom_raw_data_RawNativeBridge_freeNativeBuffer(
        JNIEnv *env,
        jobject /* this */,
        jobject directBuffer
) {
    if (!directBuffer) return;
    void *ptr = env->GetDirectBufferAddress(directBuffer);
    if (ptr) {
        free(ptr);
    }
}

// Writes an uncompressed 16-bit RGB TIFF from an RGBA16UI / RGBA16F buffer for lossless archival export
extern "C" JNIEXPORT jboolean JNICALL
Java_com_darkroom_raw_data_RawNativeBridge_write16BitTiffToFd(
        JNIEnv *env,
        jobject /* this */,
        jint outFd,
        jobject rgb16ShortBuffer,
        jint width,
        jint height
) {
    const auto *srcRgba16 = static_cast<const uint16_t *>(env->GetDirectBufferAddress(rgb16ShortBuffer));
    if (!srcRgba16 || outFd < 0) return JNI_FALSE;

    const uint32_t pixelCount = static_cast<uint32_t>(width) * static_cast<uint32_t>(height);
    const uint32_t stripByteCount = pixelCount * 3 * sizeof(uint16_t);

    // Construct minimal baseline 16-bit RGB Little-Endian TIFF header
    std::vector<uint8_t> header;
    auto push16 = [&](uint16_t v) {
        header.push_back(static_cast<uint8_t>(v & 0xFF));
        header.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    };
    auto push32 = [&](uint32_t v) {
        header.push_back(static_cast<uint8_t>(v & 0xFF));
        header.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
        header.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
        header.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    };
    auto pushTag = [&](uint16_t tag, uint16_t type, uint32_t count, uint32_t valOffset) {
        push16(tag);
        push16(type);
        push32(count);
        push32(valOffset);
    };

    // 8-byte TIFF Header: 'II' (Little-Endian), Version 42, IFD0 offset = 8
    header.push_back('I'); header.push_back('I');
    push16(42);
    push32(8);

    const uint16_t numTags = 9;
    push16(numTags);
    const uint32_t bitsPerSampleOffset = 8 + 2 + (numTags * 12) + 4;
    const uint32_t pixelDataOffset = bitsPerSampleOffset + 6;

    pushTag(256, 4, 1, static_cast<uint32_t>(width));       // ImageWidth
    pushTag(257, 4, 1, static_cast<uint32_t>(height));      // ImageLength
    pushTag(258, 3, 3, bitsPerSampleOffset);                // BitsPerSample (16, 16, 16)
    pushTag(259, 3, 1, 1);                                  // Compression = 1 (Uncompressed)
    pushTag(262, 3, 1, 2);                                  // PhotometricInterpretation = 2 (RGB)
    pushTag(273, 4, 1, pixelDataOffset);                    // StripOffsets
    pushTag(277, 3, 1, 3);                                  // SamplesPerPixel = 3
    pushTag(278, 4, 1, static_cast<uint32_t>(height));      // RowsPerStrip
    pushTag(279, 4, 1, stripByteCount);                     // StripByteCounts
    push32(0);                                              // Next IFD offset = 0

    // BitsPerSample array: 16, 16, 16
    push16(16); push16(16); push16(16);

    write(outFd, header.data(), header.size());

    // Convert RGBA16 to packed 16-bit RGB scanlines (flipped vertically from OpenGL FBO coordinates)
    std::vector<uint16_t> rowBuffer(static_cast<size_t>(width) * 3);
    for (int y = height - 1; y >= 0; --y) {
        const uint16_t *rowPtr = srcRgba16 + (static_cast<size_t>(y) * width * 4);
        for (int x = 0; x < width; ++x) {
            rowBuffer[x * 3 + 0] = rowPtr[x * 4 + 0];
            rowBuffer[x * 3 + 1] = rowPtr[x * 4 + 1];
            rowBuffer[x * 3 + 2] = rowPtr[x * 4 + 2];
        }
        write(outFd, rowBuffer.data(), rowBuffer.size() * sizeof(uint16_t));
    }

    return JNI_TRUE;
}
