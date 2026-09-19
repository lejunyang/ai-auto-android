package dev.aiauto.android.automation.session.service

/**
 * 功能用途：前台服务，作为 AI 自动化会话的 runtime owner。
 *
 * 职责：
 * - 持有 AutomationSessionEngine 的生命周期，Activity 仅为 UI 展示层。
 * - 在会话运行期间显示前台通知，提供暂停/停止入口，用户随时可收回控制权。
 * - 监听屏幕锁定（ACTION_SCREEN_OFF），锁屏时强制暂停会话（安全边界，不可配置关闭）。
 * - 单会话约束：已有会话运行时，禁止启动第二会话。
 * - 切到目标 App 导致控制 App 后台时，会话继续运行（前台服务保活），不暂停。
 *
 * 不承诺永久后台或锁屏自动执行：锁屏时暂停，服务被系统杀死时会话结束。
 */

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

import dev.aiauto.android.MainActivity
import dev.aiauto.android.R
import dev.aiauto.android.automation.session.AutomationSessionState
import dev.aiauto.android.automation.session.SessionPhase
import dev.aiauto.android.automation.session.coordinator.SessionRuntime
import dev.aiauto.android.ui.overlay.EdgeLightController
import dev.aiauto.android.ui.overlay.EdgeLightMode
import dev.aiauto.android.ui.overlay.OverlayActions
import dev.aiauto.android.ui.overlay.OverlayController
import dev.aiauto.android.ui.overlay.OverlaySessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutomationSessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollectionJob: Job? = null
    private var overlayController: OverlayController? = null
    private var edgeLightController: EdgeLightController? = null
    private var screenOffReceiver: ScreenOffReceiver? = null
    private var currentTask: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Service 创建时初始化 SessionRuntime（Context 安全）。
     * 这里注入 engineFactory 和 historyStore，确保 coordinator 能创建 engine 并写历史。
     * Activity 不负责初始化，避免依赖 Activity 生命周期。
     */
    override fun onCreate() {
        super.onCreate()
        // SessionRuntime 由 AiAutoApplication.onCreate 初始化（applicationContext 安全），
        // Service 不负责初始化，避免 ACTION_START 时 coordinator 未就绪。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> SessionRuntime.getCoordinator()?.pause()
            ACTION_RESUME -> SessionRuntime.getCoordinator()?.resume()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    /**
     * 处理停止请求：命令 coordinator.stop() 后订阅直到终态或超时，再 stopSelf。
     * 不能立即 stopSelf：coordinator.stop() 是异步的，Service 销毁后通知/overlay 消失
     * 但 coordinator 可能仍 Active。由 coordinator 负责 forced state（5秒超时），
     * Service 等待终态后再停止自身。
     */
    private fun handleStop() {
        val coordinator = SessionRuntime.getCoordinator() ?: run {
            stopSelf()
            return
        }
        coordinator.stop()
        // 订阅 coordinator 状态，收到终态后 stopSelf。
        // 最长等待 stopTimeoutMs + 缓冲，超时后强制 stopSelf（coordinator 内部已 forced cleanup）。
        serviceScope.launch {
            var waited = 0L
            val pollInterval = 200L
            val maxWait = 7_000L // coordinator stopTimeout 5秒 + 2秒缓冲
            while (coordinator.isActive && waited < maxWait) {
                kotlinx.coroutines.delay(pollInterval)
                waited += pollInterval
            }
            // 无论是否收到终态，停止 Service。coordinator 内部已处理 forced cleanup。
            stopSelf()
        }
    }

    /** 处理启动会话请求：先立即 foreground，再验证 request/coordinator，失败 stopForeground。 */
    private fun handleStart(intent: Intent) {
        val task = intent.getStringExtra(EXTRA_TASK) ?: ""
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""
        val screenshotsAllowed = intent.getBooleanExtra(EXTRA_SCREENSHOTS_ALLOWED, false)
        currentTask = task

        // 先立即启动 foreground 通知，避免超过 5 秒系统限制导致 ANR/异常。
        startForeground(NOTIFICATION_ID, buildRunningNotification())

        // 验证 coordinator 已初始化。
        val coordinator = SessionRuntime.getCoordinator()
        if (coordinator == null) {
            // coordinator 未初始化：无法启动会话，停止 foreground 并结束服务。
            android.util.Log.w("SessionService", "handleStart: coordinator is null, stopSelf")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // 验证 request 完整性。
        if (task.isBlank() || targetPackage.isBlank()) {
            android.util.Log.w("SessionService", "handleStart: task/targetPackage blank, stopSelf")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // 构建完整 SessionRequest（含 screenshotsAllowed），交给 coordinator 作为唯一 owner 启动。
        val request = dev.aiauto.android.automation.session.SessionRequest(
            task = task,
            targetPackage = targetPackage,
            screenshotsAllowed = screenshotsAllowed,
        )

        // coordinator.start 非阻塞，立即返回是否被接受。
        val accepted = coordinator.start(request)
        android.util.Log.i("SessionService", "coordinator.start(request) accepted=$accepted")
        if (!accepted) {
            // 单会话约束：已有会话运行时，不启动第二会话。
            android.util.Log.w("SessionService", "coordinator.start rejected, stopSelf")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // 启动受控悬浮窗：需 SYSTEM_ALERT_WINDOW 权限，无权限时安全返回不崩溃。
        android.util.Log.i("SessionService", "creating OverlayController and show()")
        overlayController = OverlayController(this).apply {
            setActions(buildOverlayActions(coordinator))
            updateState(OverlaySessionState.Running(currentTask))
            show()
        }
        // 同步开启边缘呼吸光效（执行态蓝色）。
        edgeLightController = EdgeLightController(this).apply {
            setMode(EdgeLightMode.ACTIVE)
        }

        // 订阅 coordinator.sessionState，更新通知与悬浮窗。
        stateCollectionJob?.cancel()
        stateCollectionJob = serviceScope.launch {
            coordinator.sessionState.collectLatest { sessionState: AutomationSessionState ->
                updateNotification(sessionState.phase, currentTask, sessionState.failureMessage)
                updateOverlay(sessionState.phase, currentTask, sessionState.failureMessage)
                if (sessionState.phase in TERMINAL_PHASES) {
                    // 会话终态后，延迟停止服务（让用户看到结果通知与悬浮窗）。
                    serviceScope.launch {
                        kotlinx.coroutines.delay(NOTIFICATION_FINISHED_DURATION_MS)
                        stopSelf()
                    }
                }
            }
        }

        // 注册锁屏监听：锁屏时强制暂停。
        registerScreenOffReceiver()
    }

    /** 更新通知内容。 */
    private fun updateNotification(phase: SessionPhase, task: String, failureMessage: String?) {
        // 通知使用安全短标题，不展示原 task 或模型 failureMessage（可能含敏感内容）。
        val notification = when (phase) {
            SessionPhase.Completed -> buildFinishedNotification("已完成", false)
            SessionPhase.Failed -> buildFinishedNotification("执行失败", true)
            SessionPhase.Stopped -> buildFinishedNotification("已停止", false)
            SessionPhase.Paused -> buildPausedNotification()
            else -> buildRunningNotification()
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    /** 更新悬浮窗与边缘光效，与会话状态同步。 */
    private fun updateOverlay(phase: SessionPhase, task: String, failureMessage: String?) {
        overlayController?.updateState(
            when (phase) {
                SessionPhase.Completed -> OverlaySessionState.Completed(task, "任务已完成")
                SessionPhase.Failed -> OverlaySessionState.Failed(task, failureMessage ?: "执行失败")
                SessionPhase.Stopped -> OverlaySessionState.Stopped(task)
                SessionPhase.Paused -> OverlaySessionState.Paused(task)
                else -> OverlaySessionState.Running(task)
            },
        )
        edgeLightController?.setMode(edgeLightModeFor(phase))
    }

    /** 会话阶段映射为边缘光效：工作中蓝呼吸、待确认金色、暂停蓝静态、终态/空闲关闭。 */
    private fun edgeLightModeFor(phase: SessionPhase): EdgeLightMode = when (phase) {
        SessionPhase.AwaitingConfirmation -> EdgeLightMode.WAITING
        SessionPhase.Paused -> EdgeLightMode.PAUSED
        SessionPhase.Observing,
        SessionPhase.Planning,
        SessionPhase.Executing,
        SessionPhase.Verifying,
        -> EdgeLightMode.ACTIVE
        SessionPhase.Idle,
        SessionPhase.Completed,
        SessionPhase.Failed,
        SessionPhase.Stopped,
        -> EdgeLightMode.OFF
    }

    /** 构建悬浮窗操作回调：暂停/继续/停止直接操作同一个 engine runtime。 */
    private fun buildOverlayActions(
        coordinator: dev.aiauto.android.automation.session.coordinator.SessionCoordinator,
    ): OverlayActions =
        object : OverlayActions {
            override fun onPause() { coordinator.pause() }
            override fun onResume() { coordinator.resume() }
            override fun onStop() { coordinator.stop() }
            override fun onExpand() { /* 悬浮窗内部处理展开态 */ }
            override fun onCollapse() { /* 悬浮窗内部处理收起态 */ }
        }

    /** 构建运行中通知，带暂停/停止 Action。 */
    private fun buildRunningNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 自动化运行中")
            .setContentText("正在执行任务")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(buildContentIntent())
            .addAction(
                android.R.drawable.ic_media_pause,
                "暂停",
                buildServicePendingIntent(ACTION_PAUSE),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                buildServicePendingIntent(ACTION_STOP),
            )
            .build()
    }

    /** 构建已暂停通知，带继续/停止 Action。 */
    private fun buildPausedNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 自动化已暂停")
            .setContentText("任务已暂停")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setOngoing(true)
            .setContentIntent(buildContentIntent())
            .addAction(
                android.R.drawable.ic_media_play,
                "继续",
                buildServicePendingIntent(ACTION_RESUME),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                buildServicePendingIntent(ACTION_STOP),
            )
            .build()
    }

    /** 构建终态通知（已完成/失败/已停止），使用安全短标题不展示原 task。 */
    private fun buildFinishedNotification(message: String, isError: Boolean): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isError) "AI 自动化失败" else "AI 自动化完成")
            .setContentText(message)
            .setSmallIcon(
                if (isError) android.R.drawable.ic_dialog_alert
                else android.R.drawable.ic_dialog_info
            )
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent())
            .build()
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AutomationSessionService::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI 自动化运行状态",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示 AI 自动化任务的运行、暂停和完成状态"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /** 注册锁屏广播接收器：锁屏时强制暂停会话。 */
    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        screenOffReceiver = ScreenOffReceiver().also { receiver ->
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }
    }

    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            runCatching { unregisterReceiver(it) }
            screenOffReceiver = null
        }
    }

    /** 锁屏广播接收器：收到 SCREEN_OFF 时暂停当前会话。 */
    private inner class ScreenOffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // 锁屏时强制暂停：安全边界，不可配置关闭。
                SessionRuntime.getCoordinator()?.pause()
            }
        }
    }

    /**
     * Service 销毁时清理资源，但不停止 coordinator 会话。
     * 系统杀服务时会话由 coordinator 内部状态判断；此处只清理 Service 级资源。
     * 注意：正常终态由 state collector 延迟 stopSelf，onDestroy 是资源清理点。
     */
    /**
     * Service 销毁时：如果 coordinator 仍 Active（意外销毁/系统杀服务），
     * 调用 forceStop 强制写 Stopped 历史并清理，避免会话永久 Active。
     * 正常终态延迟 stopSelf 时 coordinator 已 Idle，forceStop 无副作用。
     * 前台服务消失后继续运行将违反平台后台约束，应停止。
     */
    override fun onDestroy() {
        SessionRuntime.getCoordinator()?.forceStop("service_destroyed")
        stateCollectionJob?.cancel()
        unregisterScreenOffReceiver()
        overlayController?.destroy()
        overlayController = null
        edgeLightController?.destroy()
        edgeLightController = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "automation_session"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_FINISHED_DURATION_MS = 5000L

        const val ACTION_START = "dev.aiauto.android.action.START_SESSION"
        const val ACTION_PAUSE = "dev.aiauto.android.action.PAUSE_SESSION"
        const val ACTION_RESUME = "dev.aiauto.android.action.RESUME_SESSION"
        const val ACTION_STOP = "dev.aiauto.android.action.STOP_SESSION"

        const val EXTRA_TASK = "extra_task"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_SCREENSHOTS_ALLOWED = "extra_screenshots_allowed"

        /**
         * 启动会话服务，传递完整 SessionRequest（含 screenshotsAllowed）。
         * Service 作为薄壳调用 SessionCoordinator.start()，coordinator 是唯一 runtime owner。
         */
        fun start(
            context: Context,
            task: String,
            targetPackage: String = "",
            screenshotsAllowed: Boolean = false,
        ) {
            val intent = Intent(context, AutomationSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                putExtra(EXTRA_SCREENSHOTS_ALLOWED, screenshotsAllowed)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止会话服务：发送 ACTION_STOP，Service 路由到 coordinator.stop()。 */
        fun stop(context: Context) {
            val intent = Intent(context, AutomationSessionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private val TERMINAL_PHASES = setOf(
            SessionPhase.Completed,
            SessionPhase.Failed,
            SessionPhase.Stopped,
        )
    }
}
