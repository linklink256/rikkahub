package me.rerere.rikkahub.data.ai.tasks

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * 后台任务管理器：工具 / 子代理的异步执行注册表与调度。
 *
 * 模型：异步工具调用**立即同步返回** taskId（LLM 工具协议不变，工具结果即"已启动"），
 * 实际执行在应用级 scope（[CoroutineScope]，生产环境为 AppScope）的后台协程中进行；完成后通过 [onTaskCompleted]
 * 回调（由 ChatService 注册）把结果注入归属对话并触发主代理汇报。
 *
 * 生命周期：任务记录保存在内存（进程被杀即丢），但完成回调会以普通消息形式
 * 写入对话历史（持久），重启后对话里仍能看到已完成任务的结果。
 */
class BackgroundTaskManager(
    private val appScope: CoroutineScope,
) {
    companion object {
        private const val TAG = "BackgroundTaskManager"

        /** 单任务结果文本上限（字符）：防止超大输出撑爆内存与回调消息 */
        const val RESULT_LIMIT = 48_000
    }

    private val tasks = ConcurrentHashMap<String, BackgroundTask>()

    private val _tasksFlow = MutableStateFlow<List<BackgroundTask>>(emptyList())

    /** 任务列表流（UI 观察用；按创建时间倒序） */
    val tasksFlow: StateFlow<List<BackgroundTask>> = _tasksFlow.asStateFlow()

    /**
     * 任务完成回调（成功 / 失败 / 取消都会触发，RUNNING 不会）。
     * 由 ChatService 注册，负责把结果注入对话并触发主代理汇报。
     */
    @Volatile
    var onTaskCompleted: ((BackgroundTask) -> Unit)? = null

    /**
     * 启动后台任务，立即返回 taskId。
     *
     * @param block 实际执行体，返回最终结果文本（成功）；
     *              抛 CancellationException 视为取消，抛其他异常视为失败。
     */
    fun launch(
        kind: BackgroundTaskKind,
        conversationId: Uuid,
        title: String,
        block: suspend () -> String,
    ): String {
        val id = "bg-" + Uuid.random().toString().replace("-", "").take(8)
        val job = appScope.launch {
            Log.i(TAG, "task $id started ($kind: $title)")
            try {
                val result = block().take(RESULT_LIMIT)
                finish(id, BackgroundTaskStatus.SUCCESS, result)
            } catch (e: CancellationException) {
                finish(id, BackgroundTaskStatus.CANCELLED, "Task was cancelled")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "task $id failed", e)
                finish(id, BackgroundTaskStatus.FAILED, "[${e.javaClass.simpleName}] ${e.message}")
            }
        }
        tasks[id] = BackgroundTask(
            id = id,
            kind = kind,
            conversationId = conversationId,
            title = title,
            status = BackgroundTaskStatus.RUNNING,
            createdAt = System.currentTimeMillis(),
            job = job,
        )
        refreshFlow()
        return id
    }

    /** 取消任务（协程取消；底层执行如 shell 进程依赖 runInterruptible 响应中断） */
    fun cancel(taskId: String): Boolean {
        val task = tasks[taskId] ?: return false
        if (task.status != BackgroundTaskStatus.RUNNING) return false
        task.job?.cancel()
        return true
    }

    fun get(taskId: String): BackgroundTask? = tasks[taskId]

    fun list(status: BackgroundTaskStatus? = null): List<BackgroundTask> =
        tasks.values
            .filter { status == null || it.status == status }
            .sortedByDescending { it.createdAt }

    /** 清理已结束的历史任务（保留最近的 [keep] 条，防内存膨胀） */
    fun pruneFinished(keep: Int = 50) {
        val finished = tasks.values
            .filter { it.status != BackgroundTaskStatus.RUNNING }
            .sortedByDescending { it.createdAt }
        finished.drop(keep).forEach { tasks.remove(it.id) }
        refreshFlow()
    }

    private fun finish(id: String, status: BackgroundTaskStatus, result: String) {
        val finalTask = tasks.computeIfPresent(id) { _, task ->
            // 已被 cancel() 标记的任务不允许被迟到的成功覆盖（cancel 由 job 取消触发 finish(CANCELLED)）
            if (task.status == BackgroundTaskStatus.RUNNING) {
                task.copy(status = status, result = result, finishedAt = System.currentTimeMillis())
            } else {
                task
            }
        } ?: return
        refreshFlow()
        Log.i(TAG, "task $id finished: $status")
        if (finalTask.status != BackgroundTaskStatus.RUNNING) {
            runCatching { onTaskCompleted?.invoke(finalTask) }
                .onFailure { Log.w(TAG, "onTaskCompleted callback failed for $id", it) }
        }
    }

    private fun refreshFlow() {
        _tasksFlow.value = tasks.values.sortedByDescending { it.createdAt }
    }
}

enum class BackgroundTaskKind {
    /** 子代理委派（subagent 工具 background=true） */
    SUBAGENT,

    /** shell 命令（workspace_shell background=true） */
    SHELL,
}

enum class BackgroundTaskStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
}

/**
 * 后台任务记录。
 *
 * @param result 成功时为最终结果文本；失败/取消时为错误说明
 */
data class BackgroundTask(
    val id: String,
    val kind: BackgroundTaskKind,
    val conversationId: Uuid,
    val title: String,
    val status: BackgroundTaskStatus,
    val result: String = "",
    val createdAt: Long,
    val finishedAt: Long? = null,
    @Transient val job: Job? = null,
) {
    val isRunning: Boolean get() = status == BackgroundTaskStatus.RUNNING
}
