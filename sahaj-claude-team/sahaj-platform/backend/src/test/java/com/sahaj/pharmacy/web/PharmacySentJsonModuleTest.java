package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyInputs.Delegate;
import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.domain.IntegrationMode;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A body field the contract requires but allows to be null is read with its presence: left out, sent as null, or sent with
 * a value. This is what lets a partial PUT be refused instead of clearing saved values.
 */
class PharmacySentJsonModuleTest {

    private static final UUID MEMBER = UUID.fromString("00000000-0000-4000-8000-000000000021");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new PharmacySentJsonModule()).registerModule(new PharmacyStrictJsonModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PharmacyInputs.UnitSettings unit(String json) throws Exception {
        return mapper.readValue(json, PharmacyInputs.UnitSettings.class);
    }

    private PharmacyInputs.StoreUpdate store(String json) throws Exception {
        return mapper.readValue(json, PharmacyInputs.StoreUpdate.class);
    }

    @Test
    void aFieldLeftOutIsAbsentAnExplicitNullIsPresentWithNoValueAndAValueIsPresent() throws Exception {
        PharmacyInputs.UnitSettings left = unit("{\"version\":1,\"integrationMode\":\"STANDALONE\"}");
        assertThat(Sent.missing(left.gstin())).isTrue();
        assertThat(Sent.missing(left.drugLicenceNo())).isTrue();
        assertThat(Sent.missing(left.drugLicenceValidTill())).isTrue();
        assertThat(left.version()).isEqualTo(1L);
        assertThat(left.integrationMode()).isEqualTo(IntegrationMode.STANDALONE);

        PharmacyInputs.UnitSettings nulls = unit("{\"version\":1,\"gstin\":null,\"drugLicenceNo\":null,"
                + "\"drugLicenceValidTill\":null,\"integrationMode\":\"INTEGRATED\"}");
        assertThat(Sent.missing(nulls.gstin())).isFalse();
        assertThat(nulls.gstin().value()).isNull();
        assertThat(Sent.missing(nulls.drugLicenceValidTill())).isFalse();
        assertThat(nulls.drugLicenceValidTill().value()).isNull();

        PharmacyInputs.UnitSettings values = unit("{\"version\":1,\"gstin\":\"27AAAAA0000A1Z5\","
                + "\"drugLicenceNo\":\"TEST-DL-0001\",\"drugLicenceValidTill\":\"2027-03-31\","
                + "\"integrationMode\":\"INTEGRATED\"}");
        assertThat(values.gstin().value()).isEqualTo("27AAAAA0000A1Z5");
        assertThat(values.drugLicenceNo().value()).isEqualTo("TEST-DL-0001");
        assertThat(values.drugLicenceValidTill().value()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void theStoreChangeReadsFlagsHodAndDelegateWithTheirPresence() throws Exception {
        PharmacyInputs.StoreUpdate partial = store("{\"version\":2,\"name\":\"n\",\"code\":\"C\",\"type\":\"WARD\"}");
        assertThat(Sent.missing(partial.saleAllowed())).isTrue();
        assertThat(Sent.missing(partial.consumptionAllowed())).isTrue();
        assertThat(Sent.missing(partial.hodMembershipId())).isTrue();
        assertThat(Sent.missing(partial.delegate())).isTrue();

        PharmacyInputs.StoreUpdate full = store("{\"version\":2,\"name\":\"n\",\"code\":\"C\",\"type\":\"WARD\","
                + "\"saleAllowed\":false,\"consumptionAllowed\":true,\"hodMembershipId\":\"" + MEMBER + "\","
                + "\"delegate\":{\"membershipId\":\"" + MEMBER + "\",\"from\":\"2026-11-17\",\"to\":\"2026-11-20\"}}");
        assertThat(full.saleAllowed()).isEqualTo(Sent.of(false));
        assertThat(full.consumptionAllowed()).isEqualTo(Sent.of(true));
        assertThat(full.hodMembershipId()).isEqualTo(Sent.of(MEMBER));
        assertThat(full.delegate().value()).isEqualTo(
                new Delegate(MEMBER, LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 20)));

        PharmacyInputs.StoreUpdate nulls = store("{\"version\":2,\"name\":\"n\",\"code\":\"C\",\"type\":\"WARD\","
                + "\"saleAllowed\":null,\"consumptionAllowed\":true,\"hodMembershipId\":null,\"delegate\":null}");
        assertThat(nulls.saleAllowed()).isEqualTo(Sent.of(null));
        assertThat(Sent.missing(nulls.hodMembershipId())).isFalse();
        assertThat(Sent.valueOf(nulls.hodMembershipId())).isNull();
        assertThat(Sent.valueOf(nulls.delegate())).isNull();
    }

    @Test
    void aValueOfTheWrongTypeStillFailsWithTheFieldNameInThePath() {
        assertThatThrownBy(() -> unit("{\"version\":1,\"drugLicenceValidTill\":\"not a date\",\"integrationMode\":\"INTEGRATED\"}"))
                .isInstanceOfSatisfying(JsonMappingException.class, ex -> assertThat(ex.getPath())
                        .extracting(JsonMappingException.Reference::getFieldName).contains("drugLicenceValidTill"));
    }

    @Test
    void anUnknownFieldNextToTheRealOnesIsStillRefused() {
        assertThatThrownBy(() -> unit("{\"version\":1,\"integrationMode\":\"INTEGRATED\",\"fefo\":false}"))
                .matches(ex -> {
                    for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
                        if (t instanceof PharmacyStrictJsonModule.UnknownFieldException) {
                            return true;
                        }
                    }
                    return false;
                });
    }
}
