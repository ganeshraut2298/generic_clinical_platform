package com.sahaj.platform.web.admin;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.sahaj.platform.infrastructure.admin.ConditionalOnAdminRealm;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Makes the admin request bodies strict, as the contract says ({@code additionalProperties: false}).
 * Spring Boot turns {@code FAIL_ON_UNKNOWN_PROPERTIES} off for the whole application and
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} cannot switch it back on for one class, so this
 * module refuses an unknown field, but only when the target type is in this package (the admin DTOs).
 * Every other endpoint keeps the application default.
 *
 * <p>The refusal carries no field path, so the answer is the generic {@code malformed-request} problem
 * and the name the caller sent is never echoed back.
 */
@Component
@ConditionalOnAdminRealm
public class AdminStrictJsonModule extends SimpleModule {

    private static final String ADMIN_DTO_PACKAGE = AdminStrictJsonModule.class.getPackageName();

    public AdminStrictJsonModule() {
        super("AdminStrictJson");
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
                if (ADMIN_DTO_PACKAGE.equals(type.getPackageName())) {
                    throw MismatchedInputException.from(parser, type, "Unknown field in an admin request");
                }
                return false;
            }
        });
    }
}
