package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * The request bodies of docs/api/pharmacy.yaml are closed ({@code additionalProperties: false}): a field sent next to the
 * real ones, for example {@code fefo}, {@code unitCode} or a client-sent {@code tenantId}, is refused with 400 validation
 * instead of being ignored. Spring Boot turns {@code FAIL_ON_UNKNOWN_PROPERTIES} off for the whole application, so this
 * refuses an unknown field only for the types of the pharmacy module (the body records in
 * {@code com.sahaj.pharmacy.application}); every other endpoint keeps the application default. The refusal does not carry
 * the name that was sent.
 */
@Component
public class PharmacyStrictJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;
    private static final String PHARMACY_PACKAGE = "com.sahaj.pharmacy.";

    public PharmacyStrictJsonModule() {
        super("PharmacyStrictJson");
    }

    /** Marks the refusal so {@link PharmacyProblemHandler} can answer it as a validation problem. */
    public static final class UnknownFieldException extends MismatchedInputException {

        private static final long serialVersionUID = 1L;

        UnknownFieldException(JsonParser parser) {
            super(parser, "Unknown field in a Pharmacy and Inventory request");
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
                if (type.getName().startsWith(PHARMACY_PACKAGE)) {
                    throw new UnknownFieldException(parser);
                }
                return false;
            }
        });
    }
}
