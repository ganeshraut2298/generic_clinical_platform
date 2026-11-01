package com.sahaj.platform.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures everything logged (at DEBUG and above, the strictest level) so tests can assert that a
 * mobile number or a message never reached a log. Close it to restore logging.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Level previousLevel = root.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture() {
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
    }

    /** Every captured message with its formatted arguments, plus the text of any logged throwable. */
    public String allText() {
        StringBuilder text = new StringBuilder();
        List<ILoggingEvent> events = List.copyOf(appender.list);
        for (ILoggingEvent event : events) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
            text.append(event.getMDCPropertyMap()).append('\n');
        }
        return text.toString();
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(previousLevel);
        appender.stop();
    }
}
