package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Path ids and query values read as text after the gate: what is a 400 bad-request and what is a 400 validation. */
class PharmacyParamsTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000a3");

    @Test
    void aPathIdIsAUuidOrBadRequestAndTheValueIsNeverEchoed() {
        assertThat(PharmacyParams.pathId(ID.toString())).isEqualTo(ID);
        for (String bad : new String[] {null, "", "not-a-uuid", "12345", " " + ID}) {
            assertThatThrownBy(() -> PharmacyParams.pathId(bad)).isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                assertThat(ex.status()).isEqualTo(400);
                assertThat(ex.slug()).isEqualTo("bad-request");
                assertThat(ex.getMessage()).doesNotContain("not-a-uuid").doesNotContain("12345");
            });
        }
    }

    @Test
    void aBooleanIsTrueFalseOrTheDefaultAndAnythingElseIsAFieldError() {
        assertThat(PharmacyParams.bool("includeInactive", "true", false)).isTrue();
        assertThat(PharmacyParams.bool("includeInactive", "false", true)).isFalse();
        assertThat(PharmacyParams.bool("includeInactive", null, true)).isTrue();
        assertThat(PharmacyParams.bool("includeInactive", "", false)).isFalse();
        assertThatThrownBy(() -> PharmacyParams.bool("includeInactive", "perhaps", false))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> {
                    assertThat(ex.errors()).extracting(e -> e.field() + ": " + e.message())
                            .containsExactly("includeInactive: Choose a valid option");
                });
    }

    @Test
    void anOptionalStoreQueryValueIsAUuidNullWhenAbsentOrEmptyOrAFieldError() {
        assertThat(PharmacyParams.optionalUuid("storeId", ID.toString(), "Choose a store")).isEqualTo(ID);
        assertThat(PharmacyParams.optionalUuid("storeId", null, "Choose a store")).isNull();
        assertThat(PharmacyParams.optionalUuid("storeId", "", "Choose a store")).isNull();
        for (String bad : new String[] {"  ", "nope", "12345"}) {
            assertThatThrownBy(() -> PharmacyParams.optionalUuid("storeId", bad, "Choose a store"))
                    .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                            .extracting(e -> e.field() + ": " + e.message()).containsExactly("storeId: Choose a store"));
        }
    }

    @Test
    void anOptionalStoreRoleIsARoleNullOrAFieldError() {
        assertThat(PharmacyParams.optionalStoreRole("storeRole", "APPROVER")).isEqualTo(StoreRole.APPROVER);
        assertThat(PharmacyParams.optionalStoreRole("storeRole", null)).isNull();
        assertThat(PharmacyParams.optionalStoreRole("storeRole", "")).isNull();
        assertThatThrownBy(() -> PharmacyParams.optionalStoreRole("storeRole", "PHARMACIST"))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field() + ": " + e.message()).containsExactly("storeRole: Choose a valid option"));
    }
}
