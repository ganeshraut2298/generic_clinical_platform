package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Strict reading of the two bodies (contract {@code additionalProperties: false}); nothing is echoed. */
class BootstrapBodiesTest {

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final String TICKET = "A".repeat(43);

    @Test
    void aPlainUnlockBodyIsWellFormed() {
        BootstrapBodies.UnlockBody body = BootstrapBodies.readUnlock(json("{\"pin\":\"not-a-real-pin\"}"));

        assertThat(body.wellFormed()).isTrue();
        assertThat(body.pin()).isEqualTo("not-a-real-pin");
    }

    @Test
    void thePinIsKeptExactlyAsSent() {
        assertThat(BootstrapBodies.readUnlock(json("{\"pin\":\" 12 34 \"}")).pin()).isEqualTo(" 12 34 ");
        assertThat(BootstrapBodies.readUnlock(json("{\"pin\":\"\\u00e9\\n\"}")).pin()).isEqualTo("é\n");
    }

    @Test
    void aBlankPinIsStillWellFormedJsonSoTheServiceCanCallItAFormError() {
        BootstrapBodies.UnlockBody body = BootstrapBodies.readUnlock(json("{\"pin\":\"   \"}"));

        assertThat(body.wellFormed()).isTrue();
        assertThat(body.pin()).isEqualTo("   ");
    }

    @Test
    void aMissingOrJsonNullPinIsReadableWithNoValueSoTheServiceCanCallItAFormError() {
        for (String text : new String[] {"{}", "{\"pin\":null}"}) {
            BootstrapBodies.UnlockBody body = BootstrapBodies.readUnlock(json(text));

            assertThat(body.wellFormed()).as(text).isTrue();
            assertThat(body.pin()).as(text).isNull();
        }
    }

    @Test
    void anUnlockBodyThatIsNotAnObjectOfOnlyTheListedStringOrNullFieldIsUnreadable() {
        String[] bodies = {"", "   ", "null", "[]", "[\"pin\"]", "\"pin\"", "123",
                "{\"pin\":123456}", "{\"pin\":true}", "{\"pin\":[\"1\"]}", "{\"pin\":{\"a\":1}}",
                "{\"pin\":\"1\",\"extra\":\"x\"}", "{\"extra\":\"x\"}", "{\"PIN\":\"1\"}", "{\"pin\":\"1\",\"pin\":\"2\"}",
                "{\"pin\":\"1\"} {\"pin\":\"2\"}", "{\"pin\":\"1\"} x", "{\"pin\":", "not json", "{'pin':'1'}",
                "{\"pin\":\"1\",}"};
        for (String body : bodies) {
            assertThat(BootstrapBodies.readUnlock(json(body)).wellFormed()).as(body).isFalse();
        }
    }

    @Test
    void aCompleteRegisterBodyIsWellFormed() {
        BootstrapBodies.RegisterBody body = BootstrapBodies.readRegister(json("{\"ticket\":\"" + TICKET
                + "\",\"fullName\":\"First admin\",\"email\":\"first.admin@example.test\","
                + "\"password\":\"example-password-only\"}"));

        assertThat(body.wellFormed()).isTrue();
        assertThat(body.ticket()).isEqualTo(TICKET);
        assertThat(body.fullName()).isEqualTo("First admin");
        assertThat(body.email()).isEqualTo("first.admin@example.test");
        assertThat(body.password()).isEqualTo("example-password-only");
    }

    @Test
    void anUnknownFieldMakesTheRegisterBodyNotWellFormedButTheTicketIsStillRead() {
        BootstrapBodies.RegisterBody body = BootstrapBodies.readRegister(json("{\"ticket\":\"" + TICKET
                + "\",\"fullName\":\"A\",\"email\":\"a@b.co\",\"password\":\"p\",\"role\":\"PLATFORM_ADMIN\"}"));

        assertThat(body.wellFormed()).isFalse();
        assertThat(body.ticket()).isEqualTo(TICKET);
    }

    @Test
    void aMissingOrJsonNullRegisterFieldIsReadableWithNoValue() {
        BootstrapBodies.RegisterBody empty = BootstrapBodies.readRegister(json("{}"));
        BootstrapBodies.RegisterBody ticketOnly = BootstrapBodies.readRegister(json("{\"ticket\":\"" + TICKET + "\"}"));
        BootstrapBodies.RegisterBody withNull = BootstrapBodies.readRegister(json("{\"ticket\":\"" + TICKET
                + "\",\"fullName\":\"A\",\"email\":null,\"password\":\"p\"}"));

        assertThat(empty.wellFormed()).isTrue();
        assertThat(empty.ticket()).isNull();
        assertThat(ticketOnly.wellFormed()).isTrue();
        assertThat(ticketOnly.ticket()).isEqualTo(TICKET);
        assertThat(ticketOnly.fullName()).isNull();
        assertThat(withNull.wellFormed()).isTrue();
        assertThat(withNull.email()).isNull();
        assertThat(withNull.fullName()).isEqualTo("A");
    }

    @Test
    void aNonStringRegisterFieldOrABrokenBodyIsUnreadable() {
        String[] bodies = {
                "{\"ticket\":\"" + TICKET + "\",\"fullName\":1,\"email\":\"a@b.co\",\"password\":\"p\"}",
                "{\"ticket\":\"" + TICKET + "\",\"fullName\":true,\"email\":\"a@b.co\",\"password\":\"p\"}",
                "{\"ticket\":\"" + TICKET + "\",\"fullName\":[\"A\"],\"email\":\"a@b.co\",\"password\":\"p\"}",
                "{\"ticket\":\"" + TICKET + "\",\"fullName\":\"A\",\"email\":\"a@b.co\",\"password\":\"p\",\"ticket\":\"x\"}",
                "{\"ticket\":\"" + TICKET + "\",\"fullName\":\"A\",\"email\":\"a@b.co\",\"password\":\"p\"} x",
                "[]", "null", "", "garbage"};
        for (String body : bodies) {
            assertThat(BootstrapBodies.readRegister(json(body)).wellFormed()).as(body).isFalse();
        }
    }

    @Test
    void aTicketStringIsStillHandedOnWhenAnotherFieldIsNotAString() {
        BootstrapBodies.RegisterBody body = BootstrapBodies.readRegister(json("{\"ticket\":\"" + TICKET
                + "\",\"fullName\":7,\"email\":\"a@b.co\",\"password\":\"p\"}"));

        assertThat(body.wellFormed()).isFalse();
        assertThat(body.ticket()).isEqualTo(TICKET);
    }

    @Test
    void aBodyThatCannotBeParsedGivesNoValueAtAll() {
        BootstrapBodies.RegisterBody body = BootstrapBodies.readRegister(json("{\"ticket\":\"" + TICKET + "\","));

        assertThat(body.wellFormed()).isFalse();
        assertThat(body.ticket()).isNull();
        assertThat(body.password()).isNull();
    }

    @Test
    void neitherRecordPrintsAnyValue() {
        BootstrapBodies.UnlockBody unlock = BootstrapBodies.readUnlock(json("{\"pin\":\"secret-pin-value\"}"));
        BootstrapBodies.RegisterBody register = BootstrapBodies.readRegister(json("{\"ticket\":\"" + TICKET
                + "\",\"fullName\":\"Some Name\",\"email\":\"some@example.test\",\"password\":\"secret-password\"}"));

        assertThat(unlock.toString()).isEqualTo("UnlockBody[]");
        assertThat(register.toString()).isEqualTo("RegisterBody[]");
    }

    @Test
    void theResponseRecordPrintsNoValue() {
        assertThat(new BootstrapUnlocked(TICKET, 300).toString()).isEqualTo("BootstrapUnlocked[]");
    }
}
