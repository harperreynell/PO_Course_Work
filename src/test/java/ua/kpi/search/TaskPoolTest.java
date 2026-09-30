package ua.kpi.search;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class TaskPoolTest {
    @Test void workersOverlapAndCloseDrainsTheQueue() throws Exception {
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        TaskPool pool = new TaskPool(3);
        try (pool) {
            for (int i = 0; i < 3; i++) {
                pool.submit(() -> {
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (int i = 0; i < 100; i++) {
                pool.submit(count::incrementAndGet);
            }
        } finally {
            release.countDown();
        }
        assertEquals(100, count.get());
        assertThrows(IllegalStateException.class, () -> pool.submit(() -> {}));
    }

    @Test void workerFailureReachesTheBuilder() throws Exception {
        TaskPool pool = new TaskPool(1);
        pool.submit(() -> { throw new IOException("fixture"); });
        assertEquals("fixture", assertThrows(IOException.class, pool::close).getCause().getMessage());
        assertThrows(IllegalArgumentException.class, () -> new TaskPool(0));
    }
}
