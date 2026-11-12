package com.aresstack.spaceharvester.app.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Gibt jede Meldung aus {@code java.util.logging} unter demselben Logger-Namen an SLF4J weiter, mit Text (Parameter
 * {@code {0}} eingesetzt) und Ausnahme; die Stufen wie bei {@code jul-to-slf4j}: SEVERE → ERROR, WARNING → WARN,
 * INFO und CONFIG → INFO, FINE und FINER → DEBUG, FINEST → TRACE. Welche Meldungen überhaupt ankommen, bestimmen
 * weiter die Stufen von {@code java.util.logging} (Standard INFO).
 */
final class JulBridge extends Handler {

    /** Setzt nur die Parameter in den Text ein, ohne Zeitstempel und Zeilenumbruch. */
    private static final Formatter MESSAGES = new SimpleFormatter();

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getLevel() == null) {
            return;
        }
        String name = record.getLoggerName();
        Logger logger = LoggerFactory.getLogger(name == null || name.isEmpty() ? "java.util.logging" : name);
        int level = record.getLevel().intValue();
        Throwable thrown = record.getThrown();
        if (level >= Level.SEVERE.intValue()) {
            if (logger.isErrorEnabled()) {
                logger.error(message(record), thrown);
            }
        } else if (level >= Level.WARNING.intValue()) {
            if (logger.isWarnEnabled()) {
                logger.warn(message(record), thrown);
            }
        } else if (level >= Level.CONFIG.intValue()) {
            if (logger.isInfoEnabled()) {
                logger.info(message(record), thrown);
            }
        } else if (level >= Level.FINER.intValue()) {
            if (logger.isDebugEnabled()) {
                logger.debug(message(record), thrown);
            }
        } else if (logger.isTraceEnabled()) {
            logger.trace(message(record), thrown);
        }
    }

    private static String message(LogRecord record) {
        try {
            return MESSAGES.formatMessage(record);
        } catch (RuntimeException e) {
            return record.getMessage();
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
