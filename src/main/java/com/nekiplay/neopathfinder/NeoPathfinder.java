package com.nekiplay.neopathfinder;

import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static utility class for NeoPathfinder (pathfinder-only build).
 * Entry point is NeoPathfinderClient.
 */
public final class NeoPathfinder {

    public static final String MOD_ID = "neopathfinder";
    public static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int EXECUTOR_MAX_THREADS = Math.min(
            8,
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );
    private static final int EXECUTOR_CORE_THREADS = Math.min(4, EXECUTOR_MAX_THREADS);
    private static final int EXECUTOR_QUEUE_CAPACITY = 512;
    private static final ThreadPoolExecutor executor = createExecutor();

    private NeoPathfinder() {
    }

    public static Executor executor() {
        return executor;
    }

    private static ThreadPoolExecutor createExecutor() {
        AtomicInteger threadId = new AtomicInteger(1);

        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                EXECUTOR_CORE_THREADS,
                EXECUTOR_MAX_THREADS,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("neopathfinder-worker-" + threadId.getAndIncrement());
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((t, e) ->
                            LOGGER.error("Uncaught exception in {}", t.getName(), e));
                    return thread;
                },
                (runnable, exec) -> {
                    if (exec.isShutdown()) {
                        return;
                    }
                    LOGGER.warn("NeoPathfinder executor saturated; running task on calling thread");
                    runnable.run();
                }
        );
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        return threadPoolExecutor;
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }
}