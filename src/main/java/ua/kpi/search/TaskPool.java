package ua.kpi.search;

import java.io.IOException;

/** A bounded FIFO. Only the queue monitor owns head, size, accepting and failure. */
final class TaskPool implements AutoCloseable {
    interface Task {
        void run() throws Exception;
    }

    private final Thread[] workers;
    private final Task[] queue;
    private int head;
    private int size;
    private boolean accepting = true;
    private Throwable failure;

    TaskPool(int threads) {
        if (threads < 1 || threads > 64) {
            throw new IllegalArgumentException("threads must be between 1 and 64");
        }
        queue = new Task[threads * 4];
        workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = Thread.ofPlatform().name("index-worker-" + i).start(this::work);
        }
    }

    synchronized void submit(Task task) throws InterruptedException, IOException {
        while (accepting && failure == null && size == queue.length) {
            wait();
        }
        if (!accepting) {
            throw new IllegalStateException("pool is closed");
        }
        checkFailure();
        queue[(head + size) % queue.length] = task;
        size++;
        notifyAll();
    }

    private synchronized Task take() throws InterruptedException {
        while (accepting && size == 0) {
            wait();
        }
        if (size == 0) {
            return null;
        }
        Task task = queue[head];
        queue[head] = null;
        head = (head + 1) % queue.length;
        size--;
        notifyAll();
        return task;
    }

    private void work() {
        while (true) {
            try {
                Task task = take();
                if (task == null) {
                    return;
                }
                task.run();
            } catch (Throwable error) {
                synchronized (this) {
                    if (failure == null) {
                        failure = error;
                    }
                    notifyAll();
                }
            }
        }
    }

    private synchronized void checkFailure() throws IOException {
        if (failure != null) {
            throw new IOException("index task failed", failure);
        }
    }

    /** Stop admission, drain accepted tasks, then join every worker even on interruption. */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            accepting = false;
            notifyAll();
        }
        boolean interrupted = false;
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while joining index workers");
        }
        checkFailure();
    }
}
