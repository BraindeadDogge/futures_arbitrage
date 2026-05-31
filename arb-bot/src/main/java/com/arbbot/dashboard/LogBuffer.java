package com.arbbot.dashboard;

import java.util.ArrayDeque;
import java.util.List;

public class LogBuffer {

    private static final LogBuffer INSTANCE = new LogBuffer(200);

    public static LogBuffer getInstance() { return INSTANCE; }

    public record LogEntry(long timestampMs, String level, String logger, String message) {}

    private final int capacity;
    private final ArrayDeque<LogEntry> buffer;

    private LogBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    public synchronized void add(LogEntry entry) {
        if (buffer.size() >= capacity) buffer.pollFirst();
        buffer.addLast(entry);
    }

    public synchronized List<LogEntry> snapshot() {
        return List.copyOf(buffer);
    }
}
