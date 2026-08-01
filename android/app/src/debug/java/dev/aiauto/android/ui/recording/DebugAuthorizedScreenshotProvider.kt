package dev.aiauto.android.ui.recording

/**
 * 功能用途：为 disposable emulator 的 N41 instrumentation 提供无敏感数据的短期合成截图授权。
 */

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import java.security.MessageDigest

data class DebugAuthorizedScreenshotMetadata(
    val observationId: String,
    val imageSha256: String,
    val width: Int,
    val height: Int,
    val expiresAtMs: Long,
)

class DebugAuthorizedScreenshotAuthorization internal constructor(
    val metadata: DebugAuthorizedScreenshotMetadata,
    val holder: RecordingObservationHolder,
) : AutoCloseable, RecordingEditorObservationProvider {
    override fun acquire(): RecordingObservationHolder? =
        holder.takeIf { it.currentObservationId == metadata.observationId }

    override fun close() {
        holder.close()
    }
}

/**
 * provider 只生成固定棋盘图；每次新授权都会先撤销旧授权，并在释放时清零、回收位图。
 */
class DebugAuthorizedScreenshotProvider(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : AutoCloseable {
    private var sequence = 0
    private var active: DebugAuthorizedScreenshotAuthorization? = null

    init {
        require(ttlMs in 1..MAX_TTL_MS)
    }

    fun authorize(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
    ): DebugAuthorizedScreenshotAuthorization {
        require(width in 2..MAX_DIMENSION && height in 2..MAX_DIMENSION)
        active?.close()

        val bitmap = createSyntheticBitmap(width, height)
        val image = bitmap.asImageBitmap()
        val observationId = "n41-test-observation-${++sequence}"
        val expiresAtMs = clock() + ttlMs
        val metadata = DebugAuthorizedScreenshotMetadata(
            observationId = observationId,
            imageSha256 = bitmapSha256(bitmap),
            width = width,
            height = height,
            expiresAtMs = expiresAtMs,
        )
        val holder = RecordingObservationHolder(clock)
        val lease = AuthorizedObservationLease(
            observationId = observationId,
            imageSha256 = metadata.imageSha256,
            expiresAtMs = expiresAtMs,
            onRelease = {
                if (!bitmap.isRecycled) {
                    bitmap.eraseColor(Color.TRANSPARENT)
                    bitmap.recycle()
                }
                if (active?.metadata?.observationId == observationId) {
                    active = null
                }
            },
        )
        holder.attach(
            AuthorizedObservationView(lease) {
                drawImage(image)
            },
        )
        return DebugAuthorizedScreenshotAuthorization(metadata, holder).also {
            active = it
        }
    }

    override fun close() {
        active?.close()
        active = null
    }

    private fun createSyntheticBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val color = if ((x / 4 + y / 4) % 2 == 0) {
                        Color.rgb(15, 118, 110)
                    } else {
                        Color.rgb(254, 240, 138)
                    }
                    bitmap.setPixel(x, y, color)
                }
            }
        }

    private fun bitmapSha256(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateInt(bitmap.width)
        digest.updateInt(bitmap.height)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                digest.updateInt(bitmap.getPixel(x, y))
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun MessageDigest.updateInt(value: Int) {
        update((value ushr 24).toByte())
        update((value ushr 16).toByte())
        update((value ushr 8).toByte())
        update(value.toByte())
    }

    private companion object {
        const val DEFAULT_TTL_MS = 10_000L
        const val MAX_TTL_MS = 60_000L
        const val DEFAULT_WIDTH = 32
        const val DEFAULT_HEIGHT = 48
        const val MAX_DIMENSION = 512
    }
}
