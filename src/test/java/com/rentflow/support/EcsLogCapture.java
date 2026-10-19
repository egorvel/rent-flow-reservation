package com.rentflow.support;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class EcsLogCapture implements AutoCloseable {
    private final Logger logger;
    private final Level originalLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    public EcsLogCapture(String name) {
        logger = (Logger) LoggerFactory.getLogger(name);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return appender.list;
    }

    public static JsonNode encode(ILoggingEvent event, Environment environment) {
        LoggerContext context = new LoggerContext();
        try {
            context.putObject(Environment.class.getName(), environment);
            StructuredLogEncoder encoder = new StructuredLogEncoder();
            encoder.setContext(context);
            encoder.setFormat("ecs");
            encoder.start();
            JsonNode json =
                    JsonMapper.builder().build().readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));
            encoder.stop();
            return json;
        } finally {
            context.stop();
        }
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }
}
