package com.sahaj.ipd.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.sahaj.ipd.application.ScopeViews;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SAH-34: the three request bodies of the emergency access grant and the ward setup are strict, as the contract says
 * ({@code additionalProperties: false}). A duration, a start or end time, a user or a patient sent next to the real
 * fields is refused with a 400 instead of being ignored, so nothing a client sends can look like it chose them. Spring
 * Boot turns {@code FAIL_ON_UNKNOWN_PROPERTIES} off for the whole application, so this module refuses an unknown field
 * only for these types; every other endpoint keeps the application default. The refusal carries no field path, so the
 * name the caller sent is never echoed back.
 */
@Component
public class ScopeStrictJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;
    private static final Set<Class<?>> STRICT = Set.of(ScopeViews.EmergencyAccessInput.class,
            ScopeViews.WardAssignmentInput.class, ScopeViews.WardScopeInput.class);

    public ScopeStrictJsonModule() {
        super("ScopeStrictJson");
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
                    throw MismatchedInputException.from(parser, type, "Unknown field in a scope request");
                }
                return false;
            }
        });
    }
}
