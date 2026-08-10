package me.rerere.workspace

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 持久 PRoot Shell 运行器：为每个 workspace 维护一个长驻 proot+bash 进程，
 * 命令通过 stdin 写入、stdout 以随机 sentinel 界定边界。
 *
 * 动机：一次性模式（[ProotShellRunner]）每次调用都启动一个新 proot 进程，
 * Android 设备上进程启动开销约 1~3s；shell 密集型任务（子代理流水线等）
 * 大部分时间花在进程启动而非命令执行上。持久会话只在首次调用付一次启动费。
 *
 * 每条命令的执行协议（写入 shell stdin）：
 * ```
 * cd -- '<cwd>' && (
 * <command>
 * ) 2> /tmp/rh_stderr_capture ; __rh_ec=$? \
 * ; printf '\nRH_DONE_<uuid>:%s\n' "$__rh_ec" \
 * ; printf 'RH_ERR_BEGIN_<uuid>\n' ; printf '%s\n' "$(< /tmp/rh_stderr_capture)" \
 * ; printf '\nRH_ERR_END_<uuid>\n'
 * ```
 * 读侧按 sentinel 分流：DONE 行提取 exit code，BEGIN/END 之间为 stderr，其余为 stdout。
 * uuid 每次随机，命令输出不可能恰好伪造 sentinel；DONE/END 带前导 \n，
 * 保证即使前置输出未以换行结尾，sentinel 也独占一行。
 *
 * 协议尾部零 fork：stderr 捕获用固定文件（同一会话命令串行，每次覆盖写，无累积），
 * 回读用 bash 内建 `$(< file)`——proot 内每次 fork+exec 要几十~几百 ms，
 * 早期版本用 cat/rm 外置命令实测吃掉了持久化省下的进程启动收益。
 * 代价：`$(< )` 命令替换会剥掉 stderr 尾部换行（对工具结果语义无影响）。
 *
 * 可靠性设计：
 * - 同一会话命令串行（每会话一把锁）；不同 workspace 会话相互独立；
 *   多条命令的 cd/export 通过 subshell `( ... )` 隔离，不会残留到后续命令。
 * - 命令的 stderr 走临时文件回传；进程自身的 stderr 流由常驻 drain 线程消费，
 *   防止管道缓冲区撑满阻塞 shell，同时保留尾部诊断信息供故障排查。
 * - 超时 / 调用线程中断（协程取消）→ 看门狗 destroyForcibly 杀整棵进程树，
 *   会话失效；下次调用自动重建。
 * - 会话死亡（EOF / IO 异常）自动重建并重试一次；重建失败、无 rootfs
 *   或调用携带 stdin（持久会话不支持，当前无业务使用方）→ 回退一次性执行。
 */
class PersistentProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    private val logger = Logger.getLogger(TAG)
    private val fallback by lazy { ProotShellRunner(nativeLibraryDir, patcher) }

    /**
     * 一次性执行通道（每调用一个新 PRoot 进程，不占用持久会话的串行锁）。
     * 后台任务的长命令走这里：后台执行对进程启动开销不敏感，
     * 且避免一条长命令把前台 shell 调用全堵在持久会话锁外。
     */
    val oneShotRunner: WorkspaceShellRunner get() = fallback
    private val sessions = HashMap<String, PersistentShellSession>()
    private val sessionsLock = Any()

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        // stdin 持久会话不支持（当前无业务调用方使用），直接走一次性执行
        if (context.stdin != null) return fallback.execute(context)

        val session = synchronized(sessionsLock) {
            sessions[context.root]?.takeIf { it.isAlive }
                ?: createSession(context)?.also { sessions[context.root] = it }
        } ?: return fallback.execute(context)

        val result = try {
            session.run(context)
        } catch (dead: ShellDiedException) {
            logger.log(Level.WARNING, "shell session died (${dead.message}), recreating")
            invalidate(context.root, session)
            retryWithNewSession(context) ?: return fallback.execute(context)
        }
        if (result.timedOut) {
            // 超时后会话不可信（命令可能仍在执行），销毁待下次调用时重建
            invalidate(context.root, session)
        }
        return result
    }

    /** 关闭指定 workspace 的持久会话（workspace 删除时调用，幂等） */
    fun closeSession(root: String) {
        synchronized(sessionsLock) { sessions.remove(root) }?.destroy()
    }

    private fun retryWithNewSession(context: WorkspaceShellContext): WorkspaceCommandResult? {
        val session = synchronized(sessionsLock) {
            createSession(context)?.also { sessions[context.root] = it }
        } ?: return null
        return try {
            session.run(context).also { result ->
                if (result.timedOut) invalidate(context.root, session)
            }
        } catch (dead: ShellDiedException) {
            logger.log(Level.WARNING, "shell session died again (${dead.message}), falling back")
            invalidate(context.root, session)
            null
        }
    }

    private fun invalidate(root: String, session: PersistentShellSession) {
        synchronized(sessionsLock) {
            if (sessions[root] === session) sessions.remove(root)
        }
        session.destroy()
    }

    private fun createSession(context: WorkspaceShellContext): PersistentShellSession? {
        if (!context.linuxDir.hasUsableRootfs()) return null
        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile || !loader.isFile) return null
        return try {
            context.tempDir.mkdirs()
            patcher.patch(context.linuxDir)
            PersistentShellSession.start(
                command = buildProotCommand(context, proot),
                directory = context.filesDir,
                env = mapOf(
                    "PROOT_LOADER" to loader.absolutePath,
                    "PROOT_TMP_DIR" to context.tempDir.absolutePath,
                    "TMPDIR" to context.tempDir.absolutePath,
                ),
                logger = logger,
            )
        } catch (e: Exception) {
            logger.log(Level.WARNING, "failed to start persistent shell", e)
            null
        }
    }

    private fun buildProotCommand(context: WorkspaceShellContext, proot: File): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            WORKSPACE_DIR,
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )
        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }
        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }
        // bash -l 不带 -c：从 stdin 持续读命令（login shell 只付一次 profile 开销）
        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            "-l",
        )
        return command
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val TAG = "PersistentShell"
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}

/** 持久 shell 会话死亡（EOF / IO 异常），触发外层重建或回退 */
internal class ShellDiedException(message: String) : Exception(message)

/**
 * 每条命令执行前 cd 到的 Rootfs 内目录（与 [ProotShellRunner] 一次性模式语义一致：
 * cwd 为相对 workspace files 根的路径，拼到 /workspace 之后）。
 */
internal fun WorkspaceShellContext.prootCwd(): String {
    val normalized = cwd.trim().trim('/')
    return if (normalized.isBlank()) {
        WorkspaceManager.ROOTFS_WORKSPACE_DIR
    } else {
        "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$normalized"
    }
}

/**
 * 长驻 shell 会话：封装进程、stdin 写入、stdout 读取与 sentinel 协议解析。
 * internal 以便单元测试直接用宿主机 bash 构造实例验证协议正确性。
 */
internal class PersistentShellSession private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
    private val reader: BufferedReader,
    private val stderrTail: StderrTail,
) {
    private val lock = ReentrantLock()

    val isAlive: Boolean get() = process.isAlive

    fun destroy() {
        runCatching { process.destroyForcibly() }
        runCatching { writer.close() }
        runCatching { reader.close() }
    }

    fun run(context: WorkspaceShellContext): WorkspaceCommandResult {
        lock.lock()
        try {
            return runLocked(context.timeoutMillis, context.command, context.prootCwd())
        } finally {
            lock.unlock()
        }
    }

    private fun runLocked(
        timeoutMillis: Long,
        command: String,
        prootCwd: String,
    ): WorkspaceCommandResult {
        val uuid = UUID.randomUUID().toString().replace("-", "")
        // 固定捕获文件：同一会话命令串行（锁保证），每次覆盖写无累积，省掉 rm 的 fork
        val errPath = "/tmp/rh_stderr_capture"
        val doneMarker = "RH_DONE_$uuid"
        val errBegin = "RH_ERR_BEGIN_$uuid"
        val errEnd = "RH_ERR_END_$uuid"
        val script = buildString {
            append("cd -- ").append(shellQuote(prootCwd)).append(" && (\n")
            append(command).append('\n')
            append(") 2> ").append(errPath)
            append(" ; __rh_ec=${'$'}?")
            append(" ; printf '\\n").append(doneMarker).append(":%s\\n' \"${'$'}__rh_ec\"")
            append(" ; printf '").append(errBegin).append("\\n'")
            // $(< file) 是 bash 内建读文件（零 fork）；外置 cat 每次调用要在 proot 内 fork+exec
            append(" ; printf '%s\\n' \"${'$'}(< ").append(errPath).append(")\"")
            append(" ; printf '\\n").append(errEnd).append("\\n'")
            append('\n')
        }
        try {
            writer.write(script)
            writer.flush()
        } catch (e: IOException) {
            throw ShellDiedException("write command failed: ${e.message}. ${stderrTail.dump()}")
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var truncated = false
        var exitCode = -1
        var inStderr = false
        val caller = Thread.currentThread()
        val finished = AtomicBoolean(false)
        val killed = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                while (!finished.get()) {
                    if (System.currentTimeMillis() >= deadline || caller.isInterrupted) {
                        killed.set(true)
                        process.destroyForcibly()
                        return@Thread
                    }
                    Thread.sleep(WATCHDOG_POLL_MS)
                }
            } catch (_: InterruptedException) {
                // watchdog 收尾被中断，忽略
            }
        }.apply {
            isDaemon = true
            name = "persistent-shell-watchdog"
            start()
        }

        try {
            while (true) {
                val line = reader.readLine() ?: throw ShellDiedException(
                    "shell stdout reached EOF. ${stderrTail.dump()}"
                )
                when {
                    !inStderr && line.startsWith("$doneMarker:") -> {
                        exitCode = line.removePrefix("$doneMarker:").trim().toIntOrNull() ?: -1
                    }
                    !inStderr && line == errBegin -> inStderr = true
                    line == errEnd -> {
                        return WorkspaceCommandResult(
                            exitCode = exitCode,
                            stdout = stdout.toString(),
                            stderr = stderr.toString(),
                            timedOut = false,
                            truncated = truncated,
                        )
                    }
                    inStderr -> truncated = appendCapped(stderr, line) || truncated
                    else -> truncated = appendCapped(stdout, line) || truncated
                }
            }
        } catch (e: IOException) {
            // 进程被看门狗杀（超时/中断）或 shell 死亡，都会以流关闭形式释放 readLine
            when {
                caller.isInterrupted -> throw InterruptedException("interrupted while reading shell output")
                killed.get() -> return WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = stdout.toString(),
                    stderr = stderr.toString(),
                    timedOut = true,
                    truncated = true,
                )
                else -> throw ShellDiedException("read shell output failed: ${e.message}. ${stderrTail.dump()}")
            }
        } catch (dead: ShellDiedException) {
            // EOF 场景同样要区分：看门狗杀进程导致 EOF 时按超时/中断语义返回
            when {
                caller.isInterrupted -> throw InterruptedException("interrupted while reading shell output")
                killed.get() -> return WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = stdout.toString(),
                    stderr = stderr.toString(),
                    timedOut = true,
                    truncated = true,
                )
                else -> throw dead
            }
        } finally {
            finished.set(true)
        }
    }

    /** 超限后继续读到 EOF 但丢弃，避免管道写满阻塞 shell（同 StreamCollector 语义） */
    private fun appendCapped(builder: StringBuilder, line: String): Boolean {
        if (builder.isNotEmpty() && builder.length < MAX_OUTPUT_CHARS) builder.append('\n')
        val remaining = MAX_OUTPUT_CHARS - builder.length
        if (remaining > 0) {
            builder.append(line, 0, minOf(line.length, remaining))
        }
        return line.length > remaining
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        private const val WATCHDOG_POLL_MS = 25L

        /** 会话创建后就绪探测的超时（proot+bash login 首次启动可能较慢） */
        private const val READY_TIMEOUT_MS = 20_000L

        /**
         * 启动长驻 shell 进程并就绪探测。
         *
         * @param command   完整进程命令行（proot 包装或测试用的宿主机 bash）
         * @param directory 进程工作目录
         * @param env       追加的环境变量
         */
        fun start(
            command: List<String>,
            directory: File,
            env: Map<String, String>,
            logger: Logger? = null,
        ): PersistentShellSession {
            val process = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(false)
                .apply { environment().putAll(env) }
                .start()
            val stderrTail = StderrTail(process).also { it.start() }
            val session = PersistentShellSession(
                process = process,
                writer = process.outputStream.bufferedWriter(Charsets.UTF_8),
                reader = process.inputStream.bufferedReader(Charsets.UTF_8),
                stderrTail = stderrTail,
            )
            val ready = "RH_READY_" + UUID.randomUUID().toString().replace("-", "")
            val finished = AtomicBoolean(false)
            val caller = Thread.currentThread()
            Thread {
                val end = System.currentTimeMillis() + READY_TIMEOUT_MS
                try {
                    while (!finished.get() && System.currentTimeMillis() < end && !caller.isInterrupted) {
                        Thread.sleep(WATCHDOG_POLL_MS)
                    }
                    if (!finished.get()) process.destroyForcibly()
                } catch (_: InterruptedException) {
                    // 忽略
                }
            }.apply {
                isDaemon = true
                name = "persistent-shell-ready-watchdog"
                start()
            }
            try {
                session.writer.write("printf '$ready\\n'\n")
                session.writer.flush()
                while (true) {
                    // 就绪前的输出（login profile 打印等）直接丢弃
                    val line = session.reader.readLine()
                        ?: throw ShellDiedException("EOF during ready probe. ${stderrTail.dump()}")
                    if (line.trim() == ready) {
                        logger?.info("persistent shell ready")
                        return session
                    }
                }
            } catch (e: IOException) {
                process.destroyForcibly()
                throw ShellDiedException("ready probe failed: ${e.message}. ${stderrTail.dump()}")
            } finally {
                finished.set(true)
            }
        }
    }

    /** 常驻 drain 进程 stderr，防管道撑满；保留尾部 2k 字符供诊断 */
    private class StderrTail(
        private val process: Process,
    ) {
        private val tail = StringBuilder()

        fun start() {
            Thread {
                try {
                    val reader = process.errorStream.bufferedReader()
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(tail) {
                            tail.append(buffer, 0, read)
                            if (tail.length > STDERR_TAIL_LIMIT) {
                                tail.delete(0, tail.length - STDERR_TAIL_LIMIT)
                            }
                        }
                    }
                } catch (_: IOException) {
                    // 进程销毁时流关闭，正常结束
                }
            }.apply {
                isDaemon = true
                name = "persistent-shell-stderr-drain"
                start()
            }
        }

        fun dump(): String = synchronized(tail) {
            if (tail.isEmpty()) "" else "shell stderr tail: ${tail.takeLast(500)}"
        }
    }
}

private const val STDERR_TAIL_LIMIT = 2_048
