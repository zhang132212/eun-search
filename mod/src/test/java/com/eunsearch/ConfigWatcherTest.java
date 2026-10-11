package com.eunsearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigWatcherTest {
    @TempDir Path directory;

    @Test
    void delayedTasksCanRunWhileTheWatchLoopIsActive() throws Exception {
        EunSearchMod mod = new EunSearchMod();
        mod.startConfigWatcher(directory);
        try {
            var field = EunSearchMod.class.getDeclaredField("watcherExecutor");
            field.setAccessible(true);
            ScheduledExecutorService executor = (ScheduledExecutorService) field.get(mod);
            CountDownLatch completed = new CountDownLatch(1);
            executor.schedule(completed::countDown, 100, TimeUnit.MILLISECONDS);
            assertTrue(completed.await(5, TimeUnit.SECONDS),
                    "The blocking watcher must not starve scheduled configuration reloads");
        } finally {
            mod.stopConfigWatcher();
        }
    }
}
