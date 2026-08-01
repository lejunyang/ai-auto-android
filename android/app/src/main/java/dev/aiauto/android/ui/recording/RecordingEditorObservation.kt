package dev.aiauto.android.ui.recording

/**
 * 功能用途：管理调用方授权的短生命周期 observation 点选租约，并确保离开或过期时释放。
 */

import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.aiauto.android.automation.recording.NormalizedBounds
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class AuthorizedObservationLease(
    val observationId: String,
    val imageSha256: String,
    val expiresAtMs: Long,
    private val onRelease: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    init {
        require(observationId.isNotBlank() && observationId.length <= 128)
        require(imageSha256.matches(Regex("^[0-9a-f]{64}$")))
        require(expiresAtMs > 0)
    }

    val isReleased: Boolean
        get() = released.get()

    override fun close() {
        if (released.compareAndSet(false, true)) {
            onRelease()
        }
    }
}

/**
 * observation view 只暴露绘制回调，图片所有权和字节始终留在调用方租约内部。
 */
class AuthorizedObservationView(
    val lease: AuthorizedObservationLease,
    val draw: DrawScope.() -> Unit,
)

/** 调用方只交付当前授权 holder，不向 Host 暴露截图字节或持久化入口。 */
fun interface RecordingEditorObservationProvider {
    fun acquire(): RecordingObservationHolder?
}

sealed interface ObservationSelection {
    data object Unavailable : ObservationSelection

    data class Selected(
        val x: Double,
        val y: Double,
        val observationId: String,
        val imageSha256: String,
    ) : ObservationSelection

    data class BoundsSelected(
        val bounds: NormalizedBounds,
        val observationId: String,
        val imageSha256: String,
    ) : ObservationSelection
}

class RecordingObservationHolder(
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private var current: AuthorizedObservationView? = null

    val currentObservationId: String?
        get() = activeView()?.lease?.observationId

    fun attach(view: AuthorizedObservationView) {
        current?.lease?.close()
        current = view
    }

    fun selectNormalized(x: Double, y: Double): ObservationSelection {
        val view = activeView() ?: return ObservationSelection.Unavailable
        if (!x.isNormalized() || !y.isNormalized()) {
            detach()
            return ObservationSelection.Unavailable
        }
        return ObservationSelection.Selected(
            x = x.canonicalNormalized(),
            y = y.canonicalNormalized(),
            observationId = view.lease.observationId,
            imageSha256 = view.lease.imageSha256,
        )
    }

    fun selectNormalizedBounds(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
    ): ObservationSelection {
        val view = activeView() ?: return ObservationSelection.Unavailable
        if (
            !startX.isNormalized() ||
            !startY.isNormalized() ||
            !endX.isNormalized() ||
            !endY.isNormalized()
        ) {
            detach()
            return ObservationSelection.Unavailable
        }
        val bounds = NormalizedBounds(
            left = min(startX, endX).canonicalNormalized(),
            top = min(startY, endY).canonicalNormalized(),
            right = max(startX, endX).canonicalNormalized(),
            bottom = max(startY, endY).canonicalNormalized(),
        )
        if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
            return ObservationSelection.Unavailable
        }
        return ObservationSelection.BoundsSelected(
            bounds = bounds,
            observationId = view.lease.observationId,
            imageSha256 = view.lease.imageSha256,
        )
    }

    fun drawAuthorizedObservation(scope: DrawScope): Boolean {
        val view = current ?: return false
        if (clock() > view.lease.expiresAtMs || view.lease.isReleased) {
            detach()
            return false
        }
        view.draw(scope)
        return true
    }

    fun detach() {
        current?.lease?.close()
        current = null
    }

    override fun close() = detach()

    private fun activeView(): AuthorizedObservationView? {
        val view = current ?: return null
        if (view.lease.isReleased || clock() > view.lease.expiresAtMs) {
            detach()
            return null
        }
        return view
    }
}

private fun Double.isNormalized(): Boolean = isFinite() && this in 0.0..1.0

private fun Double.canonicalNormalized(): Double =
    (this * NORMALIZED_PRECISION).roundToLong() / NORMALIZED_PRECISION

private const val NORMALIZED_PRECISION = 1_000_000.0
