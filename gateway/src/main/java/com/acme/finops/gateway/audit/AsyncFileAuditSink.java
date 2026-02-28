package com.acme.finops.gateway.audit;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Async audit sink backed by append-only JSONL WAL files.
 */
public final class AsyncFileAuditSink implements AuditSink, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AsyncFileAuditSink.class.getName());
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String WAL_SCHEMA = "gateway.audit.wal.v1";
    private static final int WAL_VERSION = 1;
    private static final long RETENTION_SWEEP_INTERVAL_MS = 60_000L;

    private final Path dir;
    private final BlockingQueue<AuditEvent> queue;
    private final long flushIntervalMs;
    private final long fsyncIntervalMs;
    private final long maxFileBytes;
    private final long maxFileAgeMs;
    private final int retentionDays;
    private final boolean dropOldestWhenFull;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong droppedAuditEvents = new AtomicLong();
    private final AtomicLong writeErrors = new AtomicLong();
    private final AtomicLong flushErrors = new AtomicLong();
    private final AtomicLong fsyncErrors = new AtomicLong();
    private final Thread writerThread;

    private volatile BufferedWriter writer;
    private volatile FileOutputStream outputStream;
    private volatile Path currentFile;
    private volatile long currentFileBytes;
    private volatile long currentFileOpenedEpochMs;
    private volatile long lastFsyncEpochMs;

    public AsyncFileAuditSink(Path dir,
                              int queueCapacity,
                              long flushIntervalMs,
                              long maxFileBytes,
                              int retentionDays,
                              boolean dropOldestWhenFull) {
        this(
            dir,
            queueCapacity,
            flushIntervalMs,
            1_000L,
            maxFileBytes,
            15 * 60_000L,
            retentionDays,
            dropOldestWhenFull
        );
    }

    public AsyncFileAuditSink(Path dir,
                              int queueCapacity,
                              long flushIntervalMs,
                              long fsyncIntervalMs,
                              long maxFileBytes,
                              long maxFileAgeMs,
                              int retentionDays,
                              boolean dropOldestWhenFull) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.flushIntervalMs = Math.max(50L, flushIntervalMs);
        this.fsyncIntervalMs = Math.max(0L, fsyncIntervalMs);
        this.maxFileBytes = Math.max(1024L, maxFileBytes);
        this.maxFileAgeMs = Math.max(1_000L, maxFileAgeMs);
        this.retentionDays = Math.max(1, retentionDays);
        this.dropOldestWhenFull = dropOldestWhenFull;
        this.writerThread = new Thread(this::writerLoop, "audit-wal-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    @Override
    public void append(AuditEvent event) {
        if (event == null || !running.get()) {
            return;
        }
        if (queue.offer(event)) {
            return;
        }
        droppedAuditEvents.incrementAndGet();
        if (dropOldestWhenFull) {
            queue.poll();
            if (!queue.offer(event)) {
                droppedAuditEvents.incrementAndGet();
            }
        }
    }

    public long droppedEvents() {
        return droppedAuditEvents.get();
    }

    public long writeErrorCount() {
        return writeErrors.get();
    }

    public long flushErrorCount() {
        return flushErrors.get();
    }

    public long fsyncErrorCount() {
        return fsyncErrors.get();
    }

    private void writerLoop() {
        long nextRetentionSweepEpochMs = 0L;
        try {
            Files.createDirectories(dir);
            rotateFile(true, System.currentTimeMillis());
            while (running.get() || !queue.isEmpty()) {
                long now = System.currentTimeMillis();
                AuditEvent event = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (event != null) {
                    try {
                        writeEvent(event, now);
                    } catch (IOException e) {
                        writeErrors.incrementAndGet();
                        LOG.warning("Audit WAL write failed: " + e.getClass().getSimpleName());
                    }
                }
                rotateFileIfNeeded(now);
                flushQuietly();
                fsyncIfDue(now);
                if (now >= nextRetentionSweepEpochMs) {
                    cleanupRetentionQuietly(now);
                    nextRetentionSweepEpochMs = now + RETENTION_SWEEP_INTERVAL_MS;
                }
            }
        } catch (Throwable t) {
            LOG.warning("Audit writer stopped: " + t.getClass().getSimpleName());
        } finally {
            closeWriterQuietly();
        }
    }

    private void writeEvent(AuditEvent event, long nowEpochMs) throws IOException {
        rotateFileIfNeeded(nowEpochMs);
        String line = toJsonLine(event);
        writer.write(line);
        writer.newLine();
        currentFileBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    private void rotateFileIfNeeded(long nowEpochMs) throws IOException {
        if (writer == null) {
            rotateFile(true, nowEpochMs);
            return;
        }
        boolean rotateBySize = currentFileBytes >= maxFileBytes;
        boolean rotateByAge = nowEpochMs - currentFileOpenedEpochMs >= maxFileAgeMs;
        if (rotateBySize || rotateByAge) {
            rotateFile(true, nowEpochMs);
        }
    }

    private void rotateFile(boolean force, long nowEpochMs) throws IOException {
        if (!force && writer != null) {
            return;
        }
        closeWriterQuietly();
        String ts = TS_FMT.format(Instant.ofEpochMilli(nowEpochMs));
        currentFile = dir.resolve("audit-" + ts + "-" + System.nanoTime() + ".jsonl");
        outputStream = new FileOutputStream(currentFile.toFile(), false);
        writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        currentFileBytes = 0L;
        currentFileOpenedEpochMs = nowEpochMs;
        lastFsyncEpochMs = nowEpochMs;
    }

    private void cleanupRetentionQuietly(long nowEpochMs) {
        long cutoff = nowEpochMs - TimeUnit.DAYS.toMillis(retentionDays);
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("audit-") && p.getFileName().toString().endsWith(".jsonl"))
                .forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toMillis() < cutoff
                            && (currentFile == null || !currentFile.equals(p))) {
                            Files.deleteIfExists(p);
                        }
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException ignored) {
        }
    }

    private void flushQuietly() {
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (IOException e) {
            flushErrors.incrementAndGet();
        }
    }

    private void fsyncIfDue(long nowEpochMs) {
        if (fsyncIntervalMs <= 0L) {
            return;
        }
        if (nowEpochMs - lastFsyncEpochMs < fsyncIntervalMs) {
            return;
        }
        FileOutputStream stream = outputStream;
        if (stream == null) {
            return;
        }
        try {
            stream.getFD().sync();
            lastFsyncEpochMs = nowEpochMs;
        } catch (IOException e) {
            fsyncErrors.incrementAndGet();
        }
    }

    private void closeWriterQuietly() {
        BufferedWriter w = writer;
        writer = null;
        FileOutputStream out = outputStream;
        outputStream = null;
        if (w != null) {
            try {
                w.flush();
            } catch (IOException ignored) {
            }
            try {
                w.close();
            } catch (IOException ignored) {
            }
        }
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String toJsonLine(AuditEvent event) {
        StringBuilder sb = new StringBuilder(384);
        sb.append('{');
        appendJsonField(sb, "walSchema", WAL_SCHEMA).append(',');
        appendJsonField(sb, "walVersion", Integer.toString(WAL_VERSION)).append(',');
        appendJsonField(sb, "eventId", event.eventId()).append(',');
        appendJsonField(sb, "tsEpochMs", Long.toString(event.tsEpochMs())).append(',');
        appendJsonField(sb, "eventType", event.eventType()).append(',');
        appendJsonField(sb, "actor", event.actor()).append(',');
        appendJsonField(sb, "tenantId", event.tenantId()).append(',');
        appendJsonField(sb, "requestId", Long.toString(event.requestId())).append(',');
        appendJsonField(sb, "policyBundleId", event.policyBundleId()).append(',');
        appendJsonField(sb, "policyVersion", event.policyVersion()).append(',');
        appendJsonField(sb, "outcome", event.outcome()).append(',');
        sb.append("\"attrs\":{");
        boolean first = true;
        Map<String, String> attrs = event.attrs() == null ? Map.of() : event.attrs();
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendJsonString(sb, e.getKey()).append(':');
            appendJsonString(sb, e.getValue());
        }
        sb.append("}}");
        return sb.toString();
    }

    private static StringBuilder appendJsonField(StringBuilder sb, String key, String value) {
        appendJsonString(sb, key).append(':');
        appendJsonString(sb, value == null ? "" : value);
        return sb;
    }

    private static StringBuilder appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            writerThread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeWriterQuietly();
    }
}

