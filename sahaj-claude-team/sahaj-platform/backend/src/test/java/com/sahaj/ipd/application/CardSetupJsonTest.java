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
