package com.sahaj.billing.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.sahaj.billing.application.CourseAccountViews.CoursePaymentInput;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SAH-58 (docs/api/billing.yaml 0.2.0): the course payment body is closed ({@code additionalProperties: false}). A field
 * sent next to the real ones is refused (400 validation) instead of being ignored. The one exception: {@code tenantId} and
 * {@code clinicId} are accepted and ignored, as the contract says (the clinic is always the signed-in user's). Spring Boot
 * turns {@code FAIL_ON_UNKNOWN_PROPERTIES} off for the whole application, so this refuses an unknown field only for this
 * type; the SAH-10 billing bodies keep the application default. The refusal does not carry the name that was sent.
 */
@Component
public class CourseStrictJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    private static final Set<Class<?>> STRICT = Set.of(CoursePaymentInput.class);
    private static final Set<String> CLINIC_FIELDS = Set.of("tenantId", "clinicId");

    public CourseStrictJsonModule() {
        super("CourseStrictJson");
    }

    /** Marks the refusal so {@link CourseProblemHandler} can answer it as a validation problem. */
    public static final class UnknownFieldException extends MismatchedInputException {

        private static final long serialVersionUID = 1L;

        UnknownFieldException(JsonParser parser) {
            super(parser, "Unknown field in a course payment request");
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
                if (!STRICT.contains(type)) {
                    return false;
                }
                if (CLINIC_FIELDS.contains(propertyName)) {
                    parser.skipChildren();
                    return true;
                }
                throw new UnknownFieldException(parser);
            }
        });
    }
}
