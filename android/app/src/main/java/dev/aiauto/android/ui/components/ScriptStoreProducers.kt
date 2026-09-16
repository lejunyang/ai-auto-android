package dev.aiauto.android.ui.components

/**
 * 功能用途：提供 RecordingScriptStore 的生命周期感知异步加载组合函数，
 * 在 IO 调度器读取文件，支持错误态与页面返回栈恢复时自动刷新，避免主线程阻塞与数据陈旧。
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordingScriptStore

/** 脚本列表加载状态，区分加载中、成功与错误，禁止用空列表冒充成功。 */
sealed interface ScriptLoadState {
    data object Loading : ScriptLoadState
    data class Success(val scripts: List<AutomationScriptSummary>) : ScriptLoadState
    data class Error(val message: String) : ScriptLoadState
}

/**
 * 生命周期感知的脚本列表加载。
 *
 * - 在 Dispatchers.IO 中调用 store.list()，不阻塞 Compose 主线程。
 * - 每次 ON_RESUME（包括从子页面返回栈恢复）递增 refreshKey 触发重新加载，
 *   确保录制保存/删除后列表及时更新。
 * - 读取失败时返回 Error 状态，调用方应展示错误提示而非空态。
 */
@Composable
fun produceScriptSummaries(
    store: RecordingScriptStore,
): State<ScriptLoadState> {
    // refreshKey 用 rememberSaveable 保存，配置变更（旋转）后不丢失；
    // 它仅作为 produceState 的依赖 key，值本身无业务含义。
    var refreshKey by rememberSaveable { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val state = produceState<ScriptLoadState>(
        initialValue = ScriptLoadState.Loading,
        store,
        refreshKey,
    ) {
        value = ScriptLoadState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                ScriptLoadState.Success(store.list())
            } catch (error: Exception) {
                ScriptLoadState.Error(
                    error.message ?: "Failed to load recording scripts",
                )
            }
        }
    }

    // 页面从返回栈恢复或配置变更后 ON_RESUME 时刷新，避免保存/删除后列表陈旧。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return state
}

/** 历史记录加载状态，区分加载中、成功、部分损坏、错误。 */
sealed interface HistoryLoadState {
    data object Loading : HistoryLoadState
    data class Success(val records: List<dev.aiauto.android.automation.session.history.SessionHistoryRecord>) : HistoryLoadState
    data class Partial(
        val records: List<dev.aiauto.android.automation.session.history.SessionHistoryRecord>,
        val corruptedCount: Int,
    ) : HistoryLoadState
    data class Error(val message: String) : HistoryLoadState
}

/**
 * 生命周期感知的会话历史加载。在 IO 调度器读取，ON_RESUME 刷新，
 * 损坏文件通过 Partial 状态明确报告，不静默忽略。
 */
@Composable
fun produceHistoryRecords(
    store: dev.aiauto.android.automation.session.history.SessionHistoryStore,
): State<HistoryLoadState> {
    var refreshKey by rememberSaveable { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val state = produceState<HistoryLoadState>(
        initialValue = HistoryLoadState.Loading,
        store,
        refreshKey,
    ) {
        value = HistoryLoadState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                when (val result = store.list()) {
                    is dev.aiauto.android.automation.session.history.HistoryListResult.Ok ->
                        HistoryLoadState.Success(result.records)
                    is dev.aiauto.android.automation.session.history.HistoryListResult.Partial ->
                        HistoryLoadState.Partial(result.records, result.corruptedCount)
                }
            } catch (error: Exception) {
                HistoryLoadState.Error(
                    error.message ?: "Failed to load session history",
                )
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return state
}
