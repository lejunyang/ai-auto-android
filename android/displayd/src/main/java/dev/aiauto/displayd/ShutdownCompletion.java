package dev.aiauto.displayd;

/** 功能用途：管理安全拆除成功后的退出资格，BUSY请求即使写响应失败或进入finally也不得退出。 */
final class ShutdownCompletion {
    interface Operation<T> { T run() throws Exception; }
    private boolean ready;
    <T> T run(boolean shutdown, Operation<T> operation) throws Exception {
        T result = operation.run();
        ready = shutdown;
        return result;
    }
    void finish(Runnable exit) { if (ready) exit.run(); }
}
