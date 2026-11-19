package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Reads a body field that the contract requires but allows to be null as a {@link Sent}: a field the request left out
 * becomes {@code Sent.absent()}, an explicit {@code null} becomes {@code Sent.of(null)}, and any other value is read as the
 * field's own type. The service then answers 400 for the absent field, after the access checks, instead of treating it as
 * null (which would clear a saved value).
 */
@Component
public class PharmacySentJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public PharmacySentJsonModule() {
        super("PharmacySentJson");
        addDeserializer((Class) Sent.class, (JsonDeserializer) new SentDeserializer(null));
    }

    /** One deserializer per field type: it asks Jackson for the deserializer of the type inside {@code Sent<T>}. */
    static final class SentDeserializer extends JsonDeserializer<Sent<?>> implements ContextualDeserializer {

        private final JsonDeserializer<?> inner;

        SentDeserializer(JsonDeserializer<?> inner) {
            this.inner = inner;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property)
                throws JsonMappingException {
            JavaType wrapper = ctxt.getContextualType() != null ? ctxt.getContextualType()
                    : property == null ? null : property.getType();
            JavaType content = wrapper != null && wrapper.containedTypeCount() > 0 ? wrapper.containedType(0)
                    : ctxt.constructType(Object.class);
            return new SentDeserializer(ctxt.findContextualValueDeserializer(content, property));
        }

        @Override
        public Sent<?> deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
            return Sent.of(inner.deserialize(parser, ctxt));
        }

        @Override
        public Sent<?> getNullValue(DeserializationContext ctxt) {
            return Sent.of(null);
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return Sent.absent();
        }
    }
}
