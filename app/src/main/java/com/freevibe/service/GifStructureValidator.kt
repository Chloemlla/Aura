package com.freevibe.service

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Validates GIF structure and LZW output before Android's native decoders see it.
 *
 * Some platform GIF decoders abort the whole process for malformed image data,
 * which cannot be contained with a Kotlin catch block. This parser does not
 * allocate decoded frames. It only verifies block boundaries, palette indexes,
 * dimensions, and the exact number of pixels produced by each LZW stream.
 */
internal object GifStructureValidator {
    private const val MAX_FRAME_PIXELS = 16_777_216L
    private const val MAX_TOTAL_PIXELS = 268_435_456L
    private const val MAX_FRAMES = 10_000
    private const val MAX_LZW_CODES = 4_096

    fun isValid(file: File): Boolean = try {
        requireValid(file)
        true
    } catch (_: Exception) {
        false
    }

    fun isValid(bytes: ByteArray): Boolean = try {
        ByteArrayInputStream(bytes).use(::validate)
        true
    } catch (_: Exception) {
        false
    }

    @Throws(IOException::class)
    fun requireValid(file: File) {
        file.inputStream().buffered().use(::validate)
    }

    @Throws(IOException::class)
    private fun validate(input: InputStream) {
        val signature = ByteArray(6)
        input.readExact(signature)
        if (!hasValidGifHeader(signature)) invalid()

        val logicalWidth = input.readLittleEndianShort()
        val logicalHeight = input.readLittleEndianShort()
        val logicalPixels = logicalWidth.toLong() * logicalHeight
        if (logicalWidth == 0 || logicalHeight == 0 || logicalPixels > MAX_FRAME_PIXELS) invalid()

        val screenPacked = input.readUnsignedByteOrThrow()
        input.skipExact(2) // background colour and pixel aspect ratio
        val globalPaletteEntries = if (screenPacked and 0x80 != 0) {
            1 shl ((screenPacked and 0x07) + 1)
        } else {
            0
        }
        input.skipExact(globalPaletteEntries * 3)

        var frameCount = 0
        var totalPixels = 0L
        while (true) {
            when (input.readUnsignedByteOrThrow()) {
                0x21 -> {
                    input.readUnsignedByteOrThrow() // extension label
                    input.skipSubBlocks()
                }
                0x2C -> {
                    val left = input.readLittleEndianShort()
                    val top = input.readLittleEndianShort()
                    val width = input.readLittleEndianShort()
                    val height = input.readLittleEndianShort()
                    val packed = input.readUnsignedByteOrThrow()
                    val right = left.toLong() + width
                    val bottom = top.toLong() + height
                    val framePixels = width.toLong() * height
                    if (
                        width == 0 || height == 0 ||
                        right > logicalWidth || bottom > logicalHeight ||
                        framePixels > MAX_FRAME_PIXELS
                    ) {
                        invalid()
                    }

                    frameCount += 1
                    totalPixels += framePixels
                    if (frameCount > MAX_FRAMES || totalPixels > MAX_TOTAL_PIXELS) invalid()

                    val localPaletteEntries = if (packed and 0x80 != 0) {
                        1 shl ((packed and 0x07) + 1)
                    } else {
                        0
                    }
                    input.skipExact(localPaletteEntries * 3)
                    val paletteEntries = localPaletteEntries.takeIf { it > 0 } ?: globalPaletteEntries
                    if (paletteEntries == 0) invalid()

                    validateImageData(
                        input = input,
                        expectedPixels = framePixels,
                        paletteEntries = paletteEntries,
                    )
                }
                0x3B -> {
                    if (frameCount == 0 || input.read() != -1) invalid()
                    return
                }
                else -> invalid()
            }
        }
    }

    @Throws(IOException::class)
    private fun validateImageData(
        input: InputStream,
        expectedPixels: Long,
        paletteEntries: Int,
    ) {
        val minimumCodeSize = input.readUnsignedByteOrThrow()
        if (minimumCodeSize !in 2..8) invalid()

        val clearCode = 1 shl minimumCodeSize
        val endCode = clearCode + 1
        var availableCode = endCode + 1
        var codeSize = minimumCodeSize + 1
        var previousCode = -1
        var emittedPixels = 0L
        val lengths = IntArray(MAX_LZW_CODES)
        val validPaletteEntry = BooleanArray(MAX_LZW_CODES)
        for (code in 0 until clearCode) {
            lengths[code] = 1
            validPaletteEntry[code] = code < paletteEntries
        }

        val bits = GifSubBlockBitReader(input)
        while (true) {
            val code = bits.readCode(codeSize) ?: invalid()
            when (code) {
                clearCode -> {
                    availableCode = endCode + 1
                    codeSize = minimumCodeSize + 1
                    previousCode = -1
                }
                endCode -> {
                    if (emittedPixels != expectedPixels) invalid()
                    bits.drainToTerminator()
                    return
                }
                else -> {
                    if (previousCode == -1) {
                        if (code !in 0 until clearCode || !validPaletteEntry[code]) invalid()
                        emittedPixels += 1
                        previousCode = code
                        continue
                    }

                    val entryLength: Int
                    val entryUsesValidPalette: Boolean
                    when {
                        code < availableCode -> {
                            entryLength = lengths[code]
                            entryUsesValidPalette = validPaletteEntry[code]
                        }
                        code == availableCode && availableCode < MAX_LZW_CODES -> {
                            entryLength = lengths[previousCode] + 1
                            entryUsesValidPalette = validPaletteEntry[previousCode]
                        }
                        else -> invalid()
                    }
                    if (entryLength <= 0 || !entryUsesValidPalette) invalid()
                    emittedPixels += entryLength
                    if (emittedPixels > expectedPixels) invalid()

                    if (availableCode < MAX_LZW_CODES) {
                        lengths[availableCode] = lengths[previousCode] + 1
                        validPaletteEntry[availableCode] =
                            validPaletteEntry[previousCode] && entryUsesValidPalette
                        availableCode += 1
                        if (availableCode == 1 shl codeSize && codeSize < 12) codeSize += 1
                    }
                    previousCode = code
                }
            }
        }
    }

    private class GifSubBlockBitReader(private val input: InputStream) {
        private var remainingInBlock = 0
        private var terminated = false
        private var bitBuffer = 0L
        private var bufferedBitCount = 0

        @Throws(IOException::class)
        fun readCode(width: Int): Int? {
            while (bufferedBitCount < width) {
                val next = readDataByte()
                if (next < 0) return null
                bitBuffer = bitBuffer or (next.toLong() shl bufferedBitCount)
                bufferedBitCount += 8
            }
            val code = (bitBuffer and ((1L shl width) - 1L)).toInt()
            bitBuffer = bitBuffer ushr width
            bufferedBitCount -= width
            return code
        }

        @Throws(IOException::class)
        fun drainToTerminator() {
            bitBuffer = 0L
            bufferedBitCount = 0
            if (terminated) return
            input.skipExact(remainingInBlock)
            remainingInBlock = 0
            while (true) {
                val size = input.readUnsignedByteOrThrow()
                if (size == 0) {
                    terminated = true
                    return
                }
                input.skipExact(size)
            }
        }

        @Throws(IOException::class)
        private fun readDataByte(): Int {
            if (terminated) return -1
            if (remainingInBlock == 0) {
                remainingInBlock = input.readUnsignedByteOrThrow()
                if (remainingInBlock == 0) {
                    terminated = true
                    return -1
                }
            }
            remainingInBlock -= 1
            return input.readUnsignedByteOrThrow()
        }
    }

    @Throws(IOException::class)
    private fun InputStream.readLittleEndianShort(): Int {
        val low = readUnsignedByteOrThrow()
        return low or (readUnsignedByteOrThrow() shl 8)
    }

    @Throws(IOException::class)
    private fun InputStream.readUnsignedByteOrThrow(): Int = read().also {
        if (it < 0) invalid()
    }

    @Throws(IOException::class)
    private fun InputStream.readExact(destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val read = read(destination, offset, destination.size - offset)
            if (read <= 0) invalid()
            offset += read
        }
    }

    @Throws(IOException::class)
    private fun InputStream.skipExact(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skip(remaining.toLong()).toInt()
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (read() < 0) invalid()
                remaining -= 1
            }
        }
    }

    @Throws(IOException::class)
    private fun InputStream.skipSubBlocks() {
        while (true) {
            val size = readUnsignedByteOrThrow()
            if (size == 0) return
            skipExact(size)
        }
    }

    @Throws(IOException::class)
    private fun invalid(): Nothing = throw IOException("Invalid GIF structure")
}
