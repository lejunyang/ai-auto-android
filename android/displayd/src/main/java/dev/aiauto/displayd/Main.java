package dev.aiauto.displayd;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Looper;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 功能用途：提供仅 shell/root 可连接的本地辅助进程入口，限定并发并统一退出时的资源释放。 */
public final class Main {
    private static final String SOCKET_NAME = "aactl-displayd-v1";
    private final AndroidBackend backend;
    private final Set<LocalSocket> clients = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(4);
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
    private LocalServerSocket listener;

    private Main(int association, int user) { backend = new AndroidBackend(association, user); }

    /** 仅接受操作员固定启动入口传入的关联编号；不开放套接字、标志或反射目标配置。 */
    public static void main(String[] args) throws Exception {
        int association = 0;
        int user = -1;
        if (!Policy.acceptsUid(Process.myUid())) throw new IllegalArgumentException("displayd requires shell/root uid");
        if (args.length != 0) {
            if (args.length != 3 || !args[0].equals("--virtual-camera-association") || !args[1].matches("[1-9][0-9]{0,8}") || !args[2].matches("[0-9]{1,9}"))
                throw new IllegalArgumentException("invalid operator startup arguments");
            association = Integer.parseInt(args[1]);
            user = Integer.parseInt(args[2]);
        }
        try { Os.setsid(); }
        catch (ErrnoException error) { if (error.errno != OsConstants.EPERM) throw error; }
        Looper.prepareMainLooper();
        Main daemon = new Main(association, user);
        Runtime.getRuntime().addShutdownHook(new Thread(daemon::close, "displayd-cleanup"));
        try {
            // LocalServerSocket(String) 固定绑定 ABSTRACT 命名空间，不建立 TCP 监听。
            daemon.listener = new LocalServerSocket(SOCKET_NAME);
            daemon.timer.scheduleWithFixedDelay(daemon.backend::keepAwake, 0, 2, TimeUnit.SECONDS);
            new Thread(daemon::accept, "displayd-accept").start();
            System.out.println("displayd ready socket=" + SOCKET_NAME + " pid=" + Process.myPid());
            Looper.loop();
        } finally { daemon.close(); }
    }

    private void accept() {
        try {
            while (true) {
                LocalSocket client = listener.accept();
                try {
                    // 连接后第一步获取内核凭据；任何读取、调度或方法解析之前拒绝非授权 uid。
                    if (!Policy.acceptsUid(client.getPeerCredentials().getUid()) || !slots.tryAcquire()) {
                        closeClient(client); continue;
                    }
                } catch (IOException error) { closeClient(client); continue; }
                clients.add(client);
                workers.execute(() -> serve(client));
            }
        } catch (IOException error) {
            if (closeSafely()) System.exit(0);
        }
    }

    private void serve(LocalSocket client) {
        Integer id = null;
        ShutdownCompletion completion = new ShutdownCompletion();
        // 绝对期限包含读、等待框架及写帧，避免慢连接永久占用工作线程。
        var deadline = timer.schedule(() -> closeClient(client), 10, TimeUnit.SECONDS);
        try (client) {
            client.setSoTimeout(5000);
            byte[] header;
            byte[] png = null;
            try {
                Protocol.Request request = Protocol.read(client.getInputStream());
                id = request.id();
                AndroidBackend.Reply reply = completion.run(request.method().equals("shutdown"), () -> backend.execute(request));
                header = Protocol.line(Protocol.fields("id", id, "ok", true, "result", reply.result()));
                png = reply.png();
            } catch (Protocol.Failure error) {
                header = Protocol.line(Protocol.fields("id", id, "ok", false,
                        "error", Protocol.fields("code", error.code, "message", error.getMessage())));
            } catch (Exception error) {
                error.printStackTrace(System.err);
                header = Protocol.line(Protocol.fields("id", id, "ok", false,
                        "error", Protocol.fields("code", "FRAMEWORK_ERROR", "message", "Android framework operation failed")));
            }
            client.getOutputStream().write(header);
            if (png != null) client.getOutputStream().write(png);
            client.getOutputStream().flush();
        } catch (Exception error) {
            System.err.println("displayd connection closed: " + error.getClass().getSimpleName());
        } finally {
            deadline.cancel(false); clients.remove(client); slots.release();
            completion.finish(() -> { if (closeSafely()) System.exit(0); });
        }
    }

    private static void closeClient(LocalSocket client) {
        Policy.closeRejected(client);
    }

    private void close() {
        closeSafely();
    }

    private boolean closeSafely() {
        // close 可由退出钩子和协议线程同时调用；backend 内部保证释放幂等。
        try { backend.close(); }
        catch (Exception busy) {
            // 外部kill/崩溃不可由shutdown hook取消；正常协议失败必须保留进程/监听与所有权。
            System.err.println("displayd remains running; release failed, do not kill helper: " + busy.getMessage());
            return false;
        }
        if (listener != null) try { listener.close(); } catch (IOException ignored) { /* 套接字已释放。 */ }
        for (LocalSocket client : clients) closeClient(client);
        workers.shutdownNow(); timer.shutdownNow();
        return true;
    }
}
