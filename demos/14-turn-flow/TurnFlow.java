package com.hohoo.ailab.bounded;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** A single-owner, synchronous turn. This is not a production chat service. */
public final class TurnFlow {
    public interface ReplySource {
        String generateOnce(byte[] request, int remainingMs) throws IOException;
    }
    public static final class Result {
        public final String state, answer;
        public final int requestBytes, kept, dropped;
        Result(String state, String answer, int bytes, int kept, int dropped) {
            this.state = state; this.answer = answer;
            this.requestBytes = bytes; this.kept = kept; this.dropped = dropped;
        }
    }
    private final TurnJournal journal;
    private final String system;
    private final int maxRequestBytes, maxAnswerBytes;
    private final ReplySource source;
    private final LongSupplier clock;
    private final Thread owner = Thread.currentThread();
    private boolean unusable;

    public TurnFlow(TurnJournal journal, String system, int maxRequestBytes,
                    int maxAnswerBytes, ReplySource source) {
        this(journal, system, maxRequestBytes, maxAnswerBytes, source, System::nanoTime);
    }
    // A controlled clock supports deterministic deadline checks, not latency claims.
    TurnFlow(TurnJournal journal, String system, int maxRequestBytes,
             int maxAnswerBytes, ReplySource source, LongSupplier clock) {
        if (maxRequestBytes < 1 || maxAnswerBytes < 1)
            throw new IllegalArgumentException("positive byte limits required");
        this.journal = Objects.requireNonNull(journal);
        this.system = Objects.requireNonNull(system);
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        this.maxRequestBytes = maxRequestBytes;
        this.maxAnswerBytes = maxAnswerBytes;
    }
    private int remaining(long deadline, AtomicBoolean cancelled) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("cancelled before commit");
        long nanos = deadline - clock.getAsLong();
        if (nanos <= 0) throw new InterruptedIOException("deadline before commit");
        return (int)Math.max(1L, Math.min(Integer.MAX_VALUE, nanos / 1_000_000L));
    }
    public Result ask(String id, String question, int totalMs,
                      AtomicBoolean cancelled) throws IOException {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("use the journal's owner thread");
        if (unusable) throw new IOException("reopen the journal after a commit failure");
        if (id == null || id.trim().isEmpty() || question == null || question.trim().isEmpty()
                || totalMs < 1 || totalMs > 60_000 || cancelled == null)
            throw new IllegalArgumentException("id, question and bounded deadline required");
        long deadline = clock.getAsLong() + totalMs * 1_000_000L;
        remaining(deadline, cancelled);
        ContextBudget.Turn old = journal.find(id);
        if (old != null) {
            if (!old.question.equals(question)) throw new IOException("id reused for another question");
            return new Result("replayed", old.answer, 0, 0, 0);
        }
        ContextBudget.Selection selected = ContextBudget.select(
                system, journal.history(), question, maxRequestBytes);
        String answer = source.generateOnce(selected.payload, remaining(deadline, cancelled));
        remaining(deadline, cancelled);
        if (answer == null || answer.trim().isEmpty()
                || answer.getBytes(StandardCharsets.UTF_8).length > maxAnswerBytes)
            throw new IOException("answer rejected by the plain-text contract");
        remaining(deadline, cancelled);
        try {
            answer = journal.commit(id, question, answer);
        } catch (IOException failedCommit) {
            // A partial append is possible. Do not append using this instance again.
            unusable = true;
            throw failedCommit;
        }
        return new Result("committed", answer, selected.payload.length,
                          selected.kept, selected.dropped);
    }
}
