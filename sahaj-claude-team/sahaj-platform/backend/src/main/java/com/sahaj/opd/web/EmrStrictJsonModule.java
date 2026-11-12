package com.sahaj.opd.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SAH-44 (ADR-019 D5, D11): the three EMR request bodies are closed, as the contract says
 * ({@code additionalProperties: false}). A label, a part, a default part or any other field sent next to the real
 * ones is refused (400 validation) instead of being ignored. Spring Boot turns {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * off for the whole application, so this refuses an unknown field only for these three types; every other endpoint
 * keeps the application default. The refusal does not carry the name that was sent.
 */
@Component
public class EmrStrictJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    private static final Set<Class<?>> STRICT = Set.of(EmrRequests.SectionOverride.class,
            EmrRequests.TemplateReset.class, EmrRequests.Settings.class);

    public EmrStrictJsonModule() {
        super("EmrStrictJson");
    }

    /** Marks the refusal so {@link EmrProblemHandler} can answer it as a validation problem. */
    public static final class UnknownFieldException extends MismatchedInputException {

        private static final long serialVersionUID = 1L;

        UnknownFieldException(JsonParser parser) {
            super(parser, "Unknown field in an EMR request");
        }
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addDeserializationProblemHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser parser,
                                                 JsonDeserializer<?> deserializer, Object beanOrClass,
                                                 String propertyName) throws IOException {
                Class<?> type = beanOrClass instanceof Class<?> c ? c : beanOrClass.getClass();
                if (STRICT.contains(type)) {
                    throw new UnknownFieldException(parser);
                }
                return false;
            }
        });
    }
}
