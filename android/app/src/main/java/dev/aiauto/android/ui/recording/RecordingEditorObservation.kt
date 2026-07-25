package dev.aiauto.android.ui.recording

/**
 * 功能用途：管理调用方授权的短生命周期 observation 点选租约，并确保离开或过期时释放。
 */

import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.graphics.drawscope.DrawScope

class AuthorizedObservationLease(
    val observationId: String,
    val expiresAtMs: Long,
    private val onRelease: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

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

sealed interface ObservationSelection {
    data object Unavailable : ObservationSelection

    data class Selected(
        val x: Double,
        val y: Double,
    ) : ObservationSelection
}

class RecordingObservationHolder(
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private var current: AuthorizedObservationView? = null

    val currentObservationId: String?
        get() = current
            ?.takeUnless { it.lease.isReleased || clock() > it.lease.expiresAtMs }
            ?.lease
            ?.observationId

    fun attach(view: AuthorizedObservationView) {
        current?.lease?.close()
        current = view
    }

    fun selectNormalized(x: Double, y: Double): ObservationSelection {
        val view = current ?: return ObservationSelection.Unavailable
        if (
            view.lease.isReleased ||
            clock() > view.lease.expiresAtMs ||
            !x.isFinite() ||
            !y.isFinite() ||
            x !in 0.0..1.0 ||
            y !in 0.0..1.0
        ) {
            view.lease.close()
            current = null
            return ObservationSelection.Unavailable
        }
        return ObservationSelection.Selected(x, y)
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
}
