package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.ipd.application.CardSetupViews.CardSetupWrite;
import com.sahaj.ipd.domain.card.CardSetup;
import com.sahaj.ipd.domain.card.CardSetupDefaults;
import com.sahaj.platform.api.InvalidFieldsException;
import org.junit.jupiter.api.Test;

/** SAH-37: the stored JSON (lenient) and the request body (strict: nothing unknown, nothing missing, right types). */
class CardSetupJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode body() {
        return JSON.valueToTree(new CardSetupWrite(1, null, CardSetupDefaults.standard()));
    }

    private static void assertRefused(JsonNode body, String field) {
        assertThatThrownBy(() -> CardSetupJson.readRequest(body)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> {
                    assertThat(e.errors()).hasSize(1);
                    assertThat(e.errors().get(0).field()).isEqualTo(field);
                    assertThat(e.firstMessageAsDetail()).isTrue();
                });
    }

    @Test
    void theStandardSetupSurvivesAWriteAndARead() {
        String json = CardSetupJson.write(CardSetupDefaults.standard());

        assertThat(CardSetupJson.read(json)).contains(CardSetupDefaults.standard());
    }

    @Test
    void aStoredRowWithAnExtraKeyStillReadsAndGarbageReadsAsEmpty() {
        String json = CardSetupJson.write(CardSetupDefaults.standard()).replace("{\"layout\"", "{\"future\":1,\"layout\"");

        assertThat(CardSetupJson.read(json)).contains(CardSetupDefaults.standard());
        assertThat(CardSetupJson.read("not json")).isEmpty();
        assertThat(CardSetupJson.read("[]")).isEmpty();
        assertThat(CardSetupJson.read("{\"sections\":\"x\"}")).isEmpty();
    }

    @Test
    void aValidBodyIsRead() {
        CardSetupWrite write = CardSetupJson.readRequest(body());

        assertThat(write.baseVersion()).isEqualTo(1);
        assertThat(write.draftRevision()).isNull();
        assertThat(write.setup()).isEqualTo(CardSetupDefaults.standard());
    }

    @Test
    void anExtraPropertyIsRefusedAtItsParentPathWithoutEchoingItsName() {
        ObjectNode root = body();
        root.put("tenantId", "x");
        assertRefused(root, "setup");

        ObjectNode nested = body();
        ((ObjectNode) nested.get("setup").get("sections").get(2)).put("color", "red");
        assertRefused(nested, "setup.sections[2]");

        ObjectNode field = body();
        ((ObjectNode) field.get("setup").get("sections").get(2).get("fields").get(1)).put("hidden", true);
        assertRefused(field, "setup.sections[2].fields[1]");
    }

    @Test
    void aMissingPropertyIsRefusedWithItsPath() {
        ObjectNode noSetup = body();
        noSetup.remove("setup");
        assertRefused(noSetup, "setup");

        ObjectNode noRevision = body();
        noRevision.remove("draftRevision");
        assertRefused(noRevision, "draftRevision");

        ObjectNode noOn = body();
        ((ObjectNode) noOn.get("setup").get("sections").get(2)).remove("on");
        assertRefused(noOn, "setup.sections[2].on");

        ObjectNode noLabel = body();
        ((ObjectNode) noLabel.get("setup").get("sections").get(0).get("fields").get(0)).remove("label");
        assertRefused(noLabel, "setup.sections[0].fields[0].label");
    }

    @Test
    void aValueOfTheWrongTypeIsRefusedWithItsPath() {
        ObjectNode text = body();
        ((ObjectNode) text.get("setup")).put("cardsPerRow", "three");
        assertRefused(text, "setup.cardsPerRow");

        ObjectNode nullBoolean = body();
        ((ObjectNode) nullBoolean.get("setup").get("sections").get(1)).putNull("on");
        assertRefused(nullBoolean, "setup.sections[1].on");

        ObjectNode notArray = body();
        ((ObjectNode) notArray.get("setup")).put("sections", "none");
        assertRefused(notArray, "setup.sections");

        ObjectNode version = body();
        version.put("baseVersion", "one");
        assertRefused(version, "baseVersion");
    }

    @Test
    void aValueOfAnotherJsonTypeIsNeverCoercedAndIsRefusedWithItsPath() {
        // "on":"true", "on":1, "cardsPerRow":"3", "cardsPerRow":3.0, "baseVersion":"1", "label":5, "draftRevision":"7"
        ObjectNode textBoolean = body();
        ((ObjectNode) textBoolean.get("setup").get("sections").get(1)).put("on", "true");
        assertRefused(textBoolean, "setup.sections[1].on");

        ObjectNode numberBoolean = body();
        ((ObjectNode) numberBoolean.get("setup").get("sections").get(1)).put("on", 1);
        assertRefused(numberBoolean, "setup.sections[1].on");

        ObjectNode fieldNumberBoolean = body();
        ((ObjectNode) fieldNumberBoolean.get("setup").get("sections").get(2).get("fields").get(0)).put("on", 0);
        assertRefused(fieldNumberBoolean, "setup.sections[2].fields[0].on");

        ObjectNode textNumber = body();
        ((ObjectNode) textNumber.get("setup")).put("cardsPerRow", "3");
        assertRefused(textNumber, "setup.cardsPerRow");

        ObjectNode decimalNumber = body();
        ((ObjectNode) decimalNumber.get("setup")).put("cardsPerRow", 3.0);
        assertRefused(decimalNumber, "setup.cardsPerRow");

        ObjectNode textVersion = body();
        textVersion.put("baseVersion", "1");
        assertRefused(textVersion, "baseVersion");

        ObjectNode textRevision = body();
        textRevision.put("draftRevision", "7");
        assertRefused(textRevision, "draftRevision");

        ObjectNode numberLabel = body();
        ((ObjectNode) numberLabel.get("setup").get("sections").get(0).get("fields").get(0)).put("label", 5);
        assertRefused(numberLabel, "setup.sections[0].fields[0].label");

        ObjectNode booleanLabel = body();
        ((ObjectNode) booleanLabel.get("setup").get("sections").get(0).get("fields").get(0)).put("label", true);
        assertRefused(booleanLabel, "setup.sections[0].fields[0].label");

        ObjectNode textAction = body();
        ((ObjectNode) textAction.get("setup").get("actions").get(0)).put("on", "false");
        assertRefused(textAction, "setup.actions[0].on");
    }

    @Test
    void theExactTypesOfTheContractAreStillAccepted() {
        ObjectNode root = body();
        root.put("draftRevision", 1_778_000_000_000_000L);
        ((ObjectNode) root.get("setup")).put("cardsPerRow", 4);
        ((ObjectNode) root.get("setup").get("sections").get(0).get("fields").get(0)).put("label", "Name");

        CardSetupWrite write = CardSetupJson.readRequest(root);

        assertThat(write.draftRevision()).isEqualTo(1_778_000_000_000_000L);
        assertThat(write.setup().cardsPerRow()).isEqualTo(4);
        assertThat(write.setup().sections().get(0).fields().get(0).label()).isEqualTo("Name");
    }

    @Test
    void theStoredReadStaysLenientSoADamagedRowStillParses() {
        String json = CardSetupJson.write(CardSetupDefaults.standard()).replace("\"cardsPerRow\":3",
                "\"cardsPerRow\":\"3\"");

        assertThat(CardSetupJson.read(json)).isPresent();
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefused() {
        ArrayNode array = JSON.createArrayNode();
        assertRefused(array, "setup");
        assertThatThrownBy(() -> CardSetupJson.readRequest(null)).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theMessagesNeverEchoWhatWasSent() {
        ObjectNode root = body();
        root.put("secret-label-xyz", "value-xyz");

        assertThatThrownBy(() -> CardSetupJson.readRequest(root)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().toString()).doesNotContain("xyz"));
    }

    @Test
    void aSetupIsAPlainRecordWithTheContractPropertyNames() throws Exception {
        JsonNode json = JSON.readTree(CardSetupJson.write(CardSetupDefaults.standard()));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("layout", "cardsPerRow", "sections",
                "actions");
        assertThat(json.get("sections").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("key", "on",
                "fields");
        assertThat(json.get("sections").get(0).get("fields").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("key", "on", "label");
        CardSetup back = CardSetupJson.read(json.toString()).get();
        assertThat(back).isEqualTo(CardSetupDefaults.standard());
    }
}
