package com.jtalk.livecommunication.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Counts the SQL statements Micronaut Data runs, from its query log: how a test proves a request did (or did not) touch PostgreSQL. */
public final class SqlStatementCounter {

    private static final AtomicInteger STATEMENTS = new AtomicInteger();

    static {
        Logger queries = (Logger) LoggerFactory.getLogger("io.micronaut.data.query");
        queries.setLevel(Level.DEBUG);
        queries.setAdditive(false);   // counted, not printed
        AppenderBase<ILoggingEvent> counter = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                STATEMENTS.incrementAndGet();
            }
        };
        counter.setContext(queries.getLoggerContext());
        counter.start();
        queries.addAppender(counter);
    }

    private SqlStatementCounter() {
    }

    /** How many statements ran while {@code request} did. */
    public static int during(Supplier<?> request) {
        STATEMENTS.set(0);
        request.get();
        return STATEMENTS.get();
    }
}
