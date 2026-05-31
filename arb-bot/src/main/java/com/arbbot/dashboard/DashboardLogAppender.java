package com.arbbot.dashboard;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.arbbot.dashboard.LogBuffer.LogEntry;

public class DashboardLogAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        String shortLogger = shortName(event.getLoggerName());
        LogBuffer.getInstance().add(new LogEntry(
            event.getTimeStamp(),
            event.getLevel().toString(),
            shortLogger,
            event.getFormattedMessage()));
    }

    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
