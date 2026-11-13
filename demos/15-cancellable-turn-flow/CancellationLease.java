package com.hohoo.ailab.bounded;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One turn owns one resource. Socket I/O/close must never run under this monitor. */
public final class CancellationLease {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Closeable owned;
    private boolean registered;

    // Package-only bridge to the existing coordinator. Do not reset this flag.
    AtomicBoolean signal() { return cancelled; }
    public boolean isCancelled() { return cancelled.get(); }
    public synchronized boolean hasResource() { return owned != null; }

    public boolean register(Closeable resource) throws IOException {
        Objects.requireNonNull(resource);
        boolean rejected;
        synchronized (this) {
            if (registered) throw new IllegalStateException("one resource per turn; create a fresh lease");
            registered = true;
            rejected = cancelled.get();
            if (!rejected) owned = resource;
        }
        if (rejected) resource.close();
        return !rejected;
    }

    public void cancel() throws IOException {
        // Revoke commit eligibility even if close fails or registration arrives later.
        cancelled.set(true);
        Closeable closing;
        synchronized (this) { closing = owned; owned = null; }
        if (closing != null) closing.close();
    }

    public void release(Closeable resource) throws IOException {
        synchronized (this) { if (owned == resource) owned = null; }
        resource.close();
    }
}
