package com.hinnka.mycamera.lut

import android.content.Context
import android.util.Base64
import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.raw.ColorSpace
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/** Decodes the original OppoCam OCL1 bundle into the camera app's PLUT 3D LUT format. */
internal object OppoLutPack {
    const val PREFIX = "oppo_"
    private const val HEADER_SIZE = 6
    private const val LUT_SIZE = 9
    private const val LUT_COUNT = 11
    private const val RGB_BYTES = LUT_SIZE * LUT_SIZE * LUT_SIZE * 3
    private const val EXPECTED_SIZE = HEADER_SIZE + LUT_COUNT * RGB_BYTES

    private data class Entry(val id: String, val index: Int)
    private val entries = listOf(
        Entry("oppo_brisa", 0),
        Entry("oppo_somber", 1),
        Entry("oppo_nettai", 2),
        Entry("oppo_romance", 3),
        Entry("oppo_romancesoft", 4),
        Entry("oppo_mono", 5),
        Entry("oppo_dusk", 6),
        Entry("oppo_synch", 7),
        Entry("oppo_teal", 8),
        Entry("oppo_cinema", 9),
        Entry("oppo_wedding", 10)
    )

    @Volatile private var decodedBundle: ByteArray? = null

    fun load(context: Context, id: String): LutConfig? {
        val entry = entries.firstOrNull { it.id == id } ?: return null
        val bundle = loadBundle(context)
        val offset = HEADER_SIZE + entry.index * RGB_BYTES
        require(offset + RGB_BYTES <= bundle.size) { "Truncated Oppo LUT: $id" }
        val plut = ByteBuffer.allocate(24 + RGB_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf('P'.code.toByte(), 'L'.code.toByte(), 'U'.code.toByte(), 'T'.code.toByte()))
            putInt(3)
            putInt(LUT_SIZE)
            putInt(LutConfig.CONFIG_DATA_TYPE_UINT8)
            putInt(TransferCurve.SRGB.storageId)
            putInt(ColorSpace.SRGB.ordinal)
            put(bundle, offset, RGB_BYTES)
        }.array()
        return LutParser.parse(ByteArrayInputStream(plut), id)
    }

    @Synchronized
    private fun loadBundle(context: Context): ByteArray {
        decodedBundle?.let { return it }
        val encoded = buildString {
            for (index in 0 until 6) {
                append(context.assets.open("luts/pack$index.b64").bufferedReader().use { it.readText().trim() })
            }
        }
        val compressed = Base64.decode(encoded, Base64.NO_WRAP)
        val payload = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        require(payload.size == EXPECTED_SIZE) { "Unexpected Oppo LUT bundle size: ${payload.size}" }
        require(
            payload[0] == 'O'.code.toByte() && payload[1] == 'C'.code.toByte() &&
                payload[2] == 'L'.code.toByte() && payload[3] == '1'.code.toByte() &&
                payload[4].toInt() == LUT_SIZE && payload[5].toInt() == LUT_COUNT
        ) { "Invalid Oppo LUT bundle header" }
        decodedBundle = payload
        return payload
    }
}
