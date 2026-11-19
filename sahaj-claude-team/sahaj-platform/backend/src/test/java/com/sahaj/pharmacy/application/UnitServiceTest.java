package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyViews.UnitSettings;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleOffException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-78-2: unit settings. Defaults until the first save, FEFO and negative stock fixed on, optimistic locking, audit. */
class UnitServiceTest {

    private static final String GSTIN = "27AAAAA0000A1Z5";

    private final PharmacyFixture f = new PharmacyFixture();

    private UnitSettings get(PharmacyFixture.Person p) {
        return f.unitService.get(PharmacyFixture.TENANT, p.userId());
    }

    private UnitSettings save(PharmacyInputs.UnitSettings input) {
        return f.unitService.save(PharmacyFixture.TENANT, f.owner.userId(), input);
    }

    private static PharmacyInputs.UnitSettings input(long version, String gstin, String licence, LocalDate till,
                                                     IntegrationMode mode) {
        return PharmacyInputs.UnitSettings.of(version, gstin, licence, till, mode);
    }

    // ---- defaults ----

    @Test
    void beforeTheFirstSaveTheDefaultsAreServedAndNothingIsWritten() {
        UnitSettings s = get(f.owner);

        assertThat(s.saved()).isFalse();
        assertThat(s.version()).isZero();
        assertThat(s.updatedAt()).isNull();
        assertThat(s.gstin()).isNull();
        assertThat(s.drugLicenceNo()).isNull();
        assertThat(s.drugLicenceValidTill()).isNull();
        assertThat(s.drugLicenceExpired()).isFalse();
        assertThat(s.integrationMode()).isEqualTo(IntegrationMode.INTEGRATED);
        assertThat(s.fefo()).isTrue();
        assertThat(s.negativeStockBlocked()).isTrue();
        assertThat(s.unitName()).isEqualTo("Test Clinic Baner");
        assertThat(s.unitCode()).isEqualTo("SUN");
        assertThat(s.ipdAvailable()).isTrue();
        assertThat(f.writes).isEmpty();
    }

    @Test
    void ipdAvailableIsFalseWhenTheClinicsIpdModuleIsOff() {
        f.ipdState = "off";
        assertThat(get(f.owner).ipdAvailable()).isFalse();
        f.ipdState = "readonly";
        assertThat(get(f.owner).ipdAvailable()).isTrue();
    }

    @Test
    void theUnitNameFallsBackWhenTheProfileHasNone() {
        f.clinicName = " ";
        assertThat(get(f.owner).unitName()).isEqualTo("Your clinic");
    }

    @Test
    void aPharmacistReadsButOnlyTheOwnerSaves() {
        assertThat(get(f.pharmacist).unitCode()).isEqualTo("SUN");
        assertThatThrownBy(() -> f.unitService.save(PharmacyFixture.TENANT, f.pharmacist.userId(),
                input(0, null, null, null, IntegrationMode.INTEGRATED))).isInstanceOf(ForbiddenException.class);
        assertThat(f.writes).isEmpty();
    }

    @Test
    void everyoneElseGetsForbiddenOnReadAndModuleOffComesFirst() {
        assertThatThrownBy(() -> get(f.nurse)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> get(f.storeStaff)).isInstanceOf(ForbiddenException.class);
        f.pharmacyOn = false;
        assertThatThrownBy(() -> get(f.owner)).isInstanceOf(ModuleOffException.class);
    }

    // ---- saving ----

    @Test
    void theFirstSaveCreatesTheUnitRowWithTheCodeFromTheWebAddressAndAuditsTheChange() {
        UnitSettings saved = save(input(0, " 27aaaaa0000a1z5 ", " TEST-DL-0001 ", LocalDate.of(2028, 3, 31),
                IntegrationMode.INTEGRATED));

        assertThat(saved.saved()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.gstin()).isEqualTo(GSTIN);
        assertThat(saved.drugLicenceNo()).isEqualTo("TEST-DL-0001");
        assertThat(saved.drugLicenceValidTill()).isEqualTo(LocalDate.of(2028, 3, 31));
        assertThat(saved.drugLicenceExpired()).isFalse();
        assertThat(saved.unitCode()).isEqualTo("SUN");
        assertThat(saved.updatedAt()).isEqualTo(PharmacyFixture.NOW);
        assertThat(f.auditActions()).containsExactly("PHARMACY_UNIT_SETTINGS_CHANGED");
        assertThat(f.audit.get(0).after()).containsEntry("gstin", GSTIN).containsEntry("drugLicenceNo", "TEST-DL-0001");
        assertThat(f.audit.get(0).before()).containsEntry("gstin", null);
    }

    @Test
    void aLicenceDateInThePastIsAllowedAndShownExpired() {
        UnitSettings s = save(input(0, null, "TEST-DL-0002", LocalDate.of(2026, 11, 16), IntegrationMode.INTEGRATED));
        assertThat(s.drugLicenceExpired()).isTrue();
        UnitSettings today = save(input(1, null, "TEST-DL-0002", LocalDate.of(2026, 11, 17), IntegrationMode.INTEGRATED));
        assertThat(today.drugLicenceExpired()).isFalse();
    }

    @Test
    void emptyOptionalFieldsAreAllowedAndStoredAsNotSet() {
        UnitSettings s = save(input(0, "  ", "", null, IntegrationMode.STANDALONE));
        assertThat(s.gstin()).isNull();
        assertThat(s.drugLicenceNo()).isNull();
        assertThat(s.integrationMode()).isEqualTo(IntegrationMode.STANDALONE);
    }

    @Test
    void changingTheModeAlsoAuditsTheIntegrationModeChangeWithBeforeAndAfter() {
        save(input(0, null, null, null, IntegrationMode.INTEGRATED));
        f.audit.clear();

        UnitSettings s = save(input(1, null, null, null, IntegrationMode.STANDALONE));

        assertThat(s.integrationMode()).isEqualTo(IntegrationMode.STANDALONE);
        assertThat(f.auditActions()).containsExactly("PHARMACY_UNIT_SETTINGS_CHANGED", "PHARMACY_INTEGRATION_MODE_CHANGED");
        assertThat(f.audit.get(1).before()).containsEntry("integrationMode", "INTEGRATED");
        assertThat(f.audit.get(1).after()).containsEntry("integrationMode", "STANDALONE");
        // every later read sees the new mode
        assertThat(get(f.pharmacist).integrationMode()).isEqualTo(IntegrationMode.STANDALONE);
    }

    @Test
    void aSaveThatChangesNothingWritesNothingAfterTheFirstSave() {
        save(input(0, GSTIN, "TEST-DL-0001", null, IntegrationMode.INTEGRATED));
        f.writes.clear();
        f.audit.clear();

        UnitSettings again = save(input(1, GSTIN, "TEST-DL-0001", null, IntegrationMode.INTEGRATED));

        assertThat(again.version()).isEqualTo(1);
        assertThat(f.writes).isEmpty();
        assertThat(f.audit).isEmpty();
    }

    // ---- 400 validation, all errors together ----

    @Test
    void everyFieldErrorIsReportedTogetherWithFixedTextsAndNothingTypedIsEchoed() {
        assertThatThrownBy(() -> save(PharmacyInputs.UnitSettings.of(null, "SHORT", "x".repeat(41), null, null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field() + ": " + e.message()).containsExactly(
                                "version: Reload the page and try again.",
                                "gstin: Enter a 15-character GSTIN",
                                "drugLicenceNo: Keep the licence number to 40 characters",
                                "integrationMode: Choose an integration mode"));
        assertThat(f.writes).isEmpty();
    }

    @Test
    void aSaveThatLeavesOutARequiredFieldIs400AndClearsNothing() {
        LocalDate till = PharmacyFixture.TODAY.plusYears(1);
        save(input(0, GSTIN, "TEST-DL-0001", till, IntegrationMode.INTEGRATED));
        f.writes.clear();
        f.audit.clear();
        PharmacyInputs.Sent<String> none = PharmacyInputs.Sent.absent();
        PharmacyInputs.Sent<LocalDate> noDate = PharmacyInputs.Sent.absent();
        PharmacyInputs.Sent<String> gstin = PharmacyInputs.Sent.of(GSTIN);
        PharmacyInputs.Sent<String> licence = PharmacyInputs.Sent.of("TEST-DL-0001");
        PharmacyInputs.Sent<LocalDate> date = PharmacyInputs.Sent.of(till);
        Object[][] cases = {
                {new PharmacyInputs.UnitSettings(1L, none, licence, date, IntegrationMode.STANDALONE), "gstin"},
                {new PharmacyInputs.UnitSettings(1L, gstin, none, date, IntegrationMode.STANDALONE), "drugLicenceNo"},
                {new PharmacyInputs.UnitSettings(1L, gstin, licence, noDate, IntegrationMode.STANDALONE),
                        "drugLicenceValidTill"},
                {new PharmacyInputs.UnitSettings(1L, null, null, null, IntegrationMode.STANDALONE), "gstin"}};
        for (Object[] c : cases) {
            assertThatThrownBy(() -> save((PharmacyInputs.UnitSettings) c[0])).isInstanceOfSatisfying(
                    InvalidFieldsException.class, ex -> assertThat(ex.errors()).extracting(e -> e.field())
                            .contains((String) c[1]));
        }
        assertThat(f.writes).isEmpty();
        assertThat(f.audit).isEmpty();
        UnitSettings kept = get(f.owner);
        assertThat(kept.gstin()).isEqualTo(GSTIN);
        assertThat(kept.drugLicenceNo()).isEqualTo("TEST-DL-0001");
        assertThat(kept.drugLicenceValidTill()).isEqualTo(till);
        assertThat(kept.integrationMode()).isEqualTo(IntegrationMode.INTEGRATED);
        assertThat(kept.version()).isEqualTo(1);
    }

    @Test
    void anExplicitNullIsAllowedAndClearsTheValue() {
        save(input(0, GSTIN, "TEST-DL-0001", PharmacyFixture.TODAY.plusYears(1), IntegrationMode.INTEGRATED));

        UnitSettings cleared = save(input(1, null, null, null, IntegrationMode.INTEGRATED));

        assertThat(cleared.gstin()).isNull();
        assertThat(cleared.drugLicenceNo()).isNull();
        assertThat(cleared.drugLicenceValidTill()).isNull();
        assertThat(cleared.version()).isEqualTo(2);
    }

    @Test
    void aGstinMustMatchThePatternAndAFortyCharacterLicenceIsFine() {
        for (String bad : List.of("27AAAAA0000A1Z", "27AAAAA0000A0Z5", "AAAAAAA0000A1Z5", "27AAAAA0000A1X5", "27AAAAA0000A1Z5X")) {
            assertThatThrownBy(() -> save(input(0, bad, null, null, IntegrationMode.INTEGRATED))).as(bad)
                    .isInstanceOf(InvalidFieldsException.class);
        }
        UnitSettings ok = save(input(0, GSTIN, "x".repeat(40), null, IntegrationMode.INTEGRATED));
        assertThat(ok.drugLicenceNo()).hasSize(40);
    }

    // ---- optimistic locking ----

    @Test
    void aStaleVersionAnswersSettingsChangedWithTheCurrentSettingsAndOverwritesNothing() {
        save(input(0, GSTIN, null, null, IntegrationMode.INTEGRATED));

        assertThatThrownBy(() -> save(input(0, null, "TEST-DL-0009", null, IntegrationMode.STANDALONE)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.slug()).isEqualTo("settings-changed");
                    assertThat(ex.extensions()).containsKey("current");
                    assertThat(((UnitSettings) ex.extensions().get("current")).gstin()).isEqualTo(GSTIN);
                });
        assertThat(get(f.owner).gstin()).isEqualTo(GSTIN);
        assertThat(get(f.owner).integrationMode()).isEqualTo(IntegrationMode.INTEGRATED);
    }

    @Test
    void aFirstSaveWithAVersionOtherThanZeroIsAConflict() {
        assertThatThrownBy(() -> save(input(3, GSTIN, null, null, IntegrationMode.INTEGRATED)))
                .isInstanceOf(PharmacyProblemException.class);
    }
}
