package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.VendorCreate;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.VendorUpdate;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-78-8: the vendor master. Today in the fixture is 17 Nov 2026 (IST). Blocked is computed from the licence date, valid
 * through the date and blocked from the next day, never stored.
 */
class VendorServiceTest {

    private static final UUID TENANT = PharmacyFixture.TENANT;
    private static final LocalDate TODAY = PharmacyFixture.TODAY;
    private static final String GSTIN = "27AAAAA0000A1Z5";

    private final MasterFixture m = new MasterFixture();
    private final PharmacyFixture f = m.f;
    private final PharmacyViews.Store central = f.createCentral();

    private VendorCreate create(String name, LocalDate till) {
        return new VendorCreate(UUID.randomUUID(), name, Sent.of(GSTIN), true, Sent.of("TEST-DL-0002"), Sent.of(till),
                Sent.of("Test Contact"), Sent.of("9000000001"), BigDecimal.valueOf(30), Sent.of(BigDecimal.valueOf(90)),
                Sent.of("100"), Sent.absent(), Sent.absent(), List.of());
    }

    private PharmacyMasterViews.Vendor add(String name, LocalDate till) {
        return m.vendorService.create(TENANT, f.owner.userId(), create(name, till)).view();
    }

    private VendorUpdate update(long version, PharmacyMasterViews.Vendor v, LocalDate till) {
        return new VendorUpdate(version, v.name(), Sent.of(v.gstin()), v.licenceRequired(), Sent.of(v.licenceNo()),
                Sent.of(till), Sent.of(v.contactName()), Sent.of(v.contactPhone()), BigDecimal.valueOf(v.creditDays()),
                Sent.of(BigDecimal.valueOf(90)), Sent.of(v.returnCreditPercent()), Sent.absent(), Sent.absent(),
                v.suppliesTo().stream().map(PharmacyMasterViews.StoreName::storeId).toList());
    }

    private List<FieldMessage> errorsOf(Runnable call) {
        try {
            call.run();
        } catch (InvalidFieldsException e) {
            return e.errors();
        }
        throw new AssertionError("expected a validation error");
    }

    // ---- the view by who reads (DEC-082, security M3) ----

    @Test
    void anOwnerAndAPharmacistGetTheFullRecordOnTheListAndOnOneVendor() {
        PharmacyMasterViews.Vendor v = add("Test Distributors Pvt. Ltd. (fake)", LocalDate.of(2028, 3, 31));

        for (PharmacyFixture.Person reader : List.of(f.owner, f.pharmacist)) {
            PharmacyMasterViews.Vendor one = m.vendorService.get(TENANT, reader.userId(), v.id().toString());
            assertThat(one.gstin()).isEqualTo(GSTIN);
            assertThat(one.licenceNo()).isEqualTo("TEST-DL-0002");
            assertThat(one.contactName()).isEqualTo("Test Contact");
            assertThat(one.contactPhone()).isEqualTo("9000000001");
            assertThat(one.creditDays()).isEqualTo(30);
            assertThat(one.suppliesTo()).isNotEmpty();
            assertThat(one.productsCount()).isNotNull();
            PharmacyMasterViews.Vendor listed = m.vendorService.list(TENANT, reader.userId(), null, null, null, null).items().get(0);
            assertThat(listed.gstin()).isEqualTo(GSTIN);
            assertThat(listed.creditDays()).isEqualTo(30);
        }
    }

    @Test
    void aCallerWhoReadsOnlyThroughACentralStoreUserMappingGetsIdNameStatusAndBlockedAndNothingElse() {
        PharmacyMasterViews.Vendor v = add("Test Distributors Pvt. Ltd. (fake)", LocalDate.of(2016, 1, 1));
        f.map(central, f.storeStaff, com.sahaj.pharmacy.domain.StoreRole.CENTRAL_STORE_USER);

        PharmacyMasterViews.Vendor one = m.vendorService.get(TENANT, f.storeStaff.userId(), v.id().toString());
        PharmacyMasterViews.Vendor listed = m.vendorService.list(TENANT, f.storeStaff.userId(), null, null, null, null).items().get(0);

        for (PharmacyMasterViews.Vendor shown : List.of(one, listed)) {
            assertThat(shown.id()).isEqualTo(v.id());
            assertThat(shown.name()).isEqualTo("Test Distributors Pvt. Ltd. (fake)");
            assertThat(shown.status()).isEqualTo("BLOCKED");
            assertThat(shown.blocked()).isTrue();
            assertThat(shown.version()).isEqualTo(v.version());
            assertThat(shown.gstin()).isNull();
            assertThat(shown.licenceRequired()).isNull();
            assertThat(shown.licenceNo()).isNull();
            assertThat(shown.licenceValidTill()).isNull();
            assertThat(shown.licenceState()).isNull();
            assertThat(shown.contactName()).isNull();
            assertThat(shown.contactPhone()).isNull();
            assertThat(shown.creditDays()).isNull();
            assertThat(shown.returnDaysBeforeExpiry()).isNull();
            assertThat(shown.returnCreditPercent()).isNull();
            assertThat(shown.damagedCreditPercent()).isNull();
            assertThat(shown.expiredCreditPercent()).isNull();
            assertThat(shown.suppliesTo()).isNull();
            assertThat(shown.productsCount()).isNull();
        }
    }

    // ---- create ----

    @Test
    void anOwnerAddsAVendorThatSuppliesTheCentralStoreByDefault() {
        PharmacyViews.Outcome<PharmacyMasterViews.Vendor> out = m.vendorService.create(TENANT, f.owner.userId(),
                create("Test Distributors Pvt. Ltd. (fake)", LocalDate.of(2028, 3, 31)));

        assertThat(out.created()).isTrue();
        PharmacyMasterViews.Vendor v = out.view();
        assertThat(v.status()).isEqualTo("ACTIVE");
        assertThat(v.blocked()).isFalse();
        assertThat(v.licenceState()).isEqualTo("VALID");
        assertThat(v.gstin()).isEqualTo(GSTIN);
        assertThat(v.returnCreditPercent()).isEqualTo("100");
        assertThat(v.suppliesTo()).extracting(PharmacyMasterViews.StoreName::storeId).containsExactly(central.id());
        assertThat(v.productsCount()).isZero();
        assertThat(v.version()).isZero();
        assertThat(f.auditActions()).contains("PHARMACY_VENDOR_CREATED");
    }

    @Test
    void theSameClientKeyFromTheSameCallerAnswersTheVendorAgainAndWritesNothing() {
        VendorCreate in = create("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));
        PharmacyMasterViews.Vendor first = m.vendorService.create(TENANT, f.owner.userId(), in).view();
        m.writes.clear();

        PharmacyViews.Outcome<PharmacyMasterViews.Vendor> again = m.vendorService.create(TENANT, f.owner.userId(), in);

        assertThat(again.created()).isFalse();
        assertThat(again.view().id()).isEqualTo(first.id());
        assertThat(m.vendorRows).hasSize(1);
        assertThat(m.writes).isEmpty();
    }

    @Test
    void theSameClientKeyFromAnotherCallerIsRefused() {
        VendorCreate in = create("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));
        m.vendorService.create(TENANT, f.owner.userId(), in);
        PharmacyFixture.Person second = f.person("OWNER", "Test Owner Two", true);

        assertThatThrownBy(() -> m.vendorService.create(TENANT, second.userId(), in))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("client-key-reused"));
    }

    @Test
    void aNameInUseIsRefusedIgnoringCaseAndWhateverTheStatus() {
        PharmacyMasterViews.Vendor v = add("Test City Medicals (fake)", LocalDate.of(2028, 3, 31));
        m.vendorService.switchOff(TENANT, f.owner.userId(), v.id().toString());

        assertThatThrownBy(() -> add("TEST CITY MEDICALS (FAKE)", LocalDate.of(2028, 3, 31)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("vendor-name-in-use");
                    assertThat(e.getMessage()).isEqualTo("That vendor is already in the list");
                });
    }

    @Test
    void everyFieldErrorIsReportedTogetherWithTheContractText() {
        UUID inactiveStore = UUID.randomUUID();
        VendorCreate bad = new VendorCreate(UUID.randomUUID(), "  ", Sent.of("BADGSTIN"), true, Sent.of(" "),
                Sent.of(null), Sent.of("x".repeat(81)), Sent.of("12345"), BigDecimal.valueOf(366),
                Sent.of(BigDecimal.valueOf(400)), Sent.of("100.5"), Sent.absent(), Sent.absent(), List.of(inactiveStore));

        List<FieldMessage> errors = errorsOf(() -> m.vendorService.create(TENANT, f.owner.userId(), bad));

        assertThat(errors).contains(new FieldMessage("name", "Enter the vendor name"),
                new FieldMessage("gstin", "Enter a 15-character GSTIN"),
                new FieldMessage("licenceNo", "Enter the drug licence number"),
                new FieldMessage("licenceValidTill", "Enter the licence valid-till date"),
                new FieldMessage("contactName", "Keep the contact name to 80 characters"),
                new FieldMessage("contactPhone", "Enter a 10-digit phone number"),
                new FieldMessage("creditDays", "Enter 0 to 365 days"),
                new FieldMessage("returnDaysBeforeExpiry", "Enter 0 to 365 days"),
                new FieldMessage("returnCreditPercent", "Enter a % from 0 to 100"),
                new FieldMessage("suppliesToStoreIds", "Choose active stores"));
        assertThat(m.vendorRows).isEmpty();
    }

    @Test
    void aFieldTheRequestLeftOutIsAnErrorNotNull() {
        VendorCreate partial = new VendorCreate(UUID.randomUUID(), "Test Vendor One (fake)", Sent.absent(), false,
                Sent.absent(), Sent.absent(), Sent.absent(), Sent.absent(), BigDecimal.ZERO, Sent.absent(), Sent.absent(),
                Sent.absent(), Sent.absent(), List.of());

        assertThat(errorsOf(() -> m.vendorService.create(TENANT, f.owner.userId(), partial))).extracting(FieldMessage::field)
                .contains("gstin", "licenceNo", "licenceValidTill", "contactName", "contactPhone",
                        "returnDaysBeforeExpiry", "returnCreditPercent");
    }

    @Test
    void aWholeNumberFieldTakesNoDecimals() {
        VendorCreate in = new VendorCreate(UUID.randomUUID(), "Test Vendor One (fake)", Sent.of(null), false, Sent.of(null),
                Sent.of(null), Sent.of(null), Sent.of(null), new BigDecimal("2.5"), Sent.of(null), Sent.of(null), Sent.absent(), Sent.absent(), List.of());

        assertThat(errorsOf(() -> m.vendorService.create(TENANT, f.owner.userId(), in)))
                .containsExactly(new FieldMessage("creditDays", "Enter 0 to 365 days"));
    }

    @Test
    void aVendorThatNeedsNoLicenceStoresNoLicenceValues() {
        VendorCreate in = new VendorCreate(UUID.randomUUID(), "Test Consumables (fake)", Sent.of(null), false,
                Sent.of("IGNORED-1"), Sent.of(LocalDate.of(2020, 1, 1)), Sent.of(null), Sent.of(null),
                BigDecimal.ZERO, Sent.of(null), Sent.of(null), Sent.absent(), Sent.absent(), List.of(central.id()));

        PharmacyMasterViews.Vendor v = m.vendorService.create(TENANT, f.owner.userId(), in).view();

        assertThat(v.licenceNo()).isNull();
        assertThat(v.licenceValidTill()).isNull();
        assertThat(v.licenceState()).isEqualTo("NOT_REQUIRED");
        assertThat(v.status()).isEqualTo("ACTIVE");
        assertThat(v.blocked()).isFalse();
    }

    @Test
    void aSwitchedOffStoreCannotBeChosen() {
        PharmacyViews.Store ward = f.createStore("Test Ward Store", "WS1", StoreType.WARD);
        f.storeService.switchOff(TENANT, f.owner.userId(), ward.id().toString());
        VendorCreate in = new VendorCreate(UUID.randomUUID(), "Test Vendor One (fake)", Sent.of(null), false,
                Sent.of(null), Sent.of(null), Sent.of(null), Sent.of(null), BigDecimal.ZERO, Sent.of(null), Sent.of(null),
                Sent.absent(), Sent.absent(), List.of(ward.id()));

        assertThat(errorsOf(() -> m.vendorService.create(TENANT, f.owner.userId(), in)))
                .containsExactly(new FieldMessage("suppliesToStoreIds", "Choose active stores"));
    }

    // ---- blocking ----

    @Test
    void aVendorIsActiveThroughItsLicenceDateAndBlockedFromTheNextDay() {
        PharmacyMasterViews.Vendor onTheDate = add("Test Vendor Today (fake)", TODAY);
        PharmacyMasterViews.Vendor dayBefore = add("Test Vendor Expired (fake)", TODAY.minusDays(1));

        assertThat(onTheDate.status()).isEqualTo("ACTIVE");
        assertThat(onTheDate.blocked()).isFalse();
        assertThat(dayBefore.status()).isEqualTo("BLOCKED");
        assertThat(dayBefore.blocked()).isTrue();
        assertThat(dayBefore.licenceState()).isEqualTo("EXPIRED");
    }

    @Test
    void movingTheLicenceDateToTodayOrLaterMakesTheVendorActiveAtOnce() {
        PharmacyMasterViews.Vendor expired = add("Test Vendor Expired (fake)", TODAY.minusDays(1));

        PharmacyMasterViews.Vendor renewed = m.vendorService.update(TENANT, f.owner.userId(), expired.id().toString(),
                update(expired.version(), expired, TODAY));

        assertThat(renewed.status()).isEqualTo("ACTIVE");
        assertThat(renewed.blocked()).isFalse();
        assertThat(renewed.version()).isEqualTo(1);
        assertThat(f.auditActions()).contains("PHARMACY_VENDOR_CHANGED");
    }

    @Test
    void anInactiveVendorIsInactiveNotBlockedAndStaysInactiveWhenEdited() {
        PharmacyMasterViews.Vendor expired = add("Test Vendor Expired (fake)", TODAY.minusDays(1));
        PharmacyMasterViews.Vendor off = m.vendorService.switchOff(TENANT, f.owner.userId(), expired.id().toString());
        assertThat(off.status()).isEqualTo("INACTIVE");
        assertThat(off.blocked()).isFalse();

        PharmacyMasterViews.Vendor edited = m.vendorService.update(TENANT, f.owner.userId(), expired.id().toString(),
                update(off.version(), off, TODAY.plusDays(10)));

        assertThat(edited.status()).isEqualTo("INACTIVE");
    }

    // ---- list ----

    @Test
    void theDefaultListShowsActiveAndBlockedByNameAndTheFilterNarrowsIt() {
        PharmacyMasterViews.Vendor b = add("Bravo Medicals (fake)", LocalDate.of(2028, 3, 31));
        PharmacyMasterViews.Vendor a = add("alpha Distributors (fake)", TODAY.minusDays(1));
        PharmacyMasterViews.Vendor c = add("Charlie Pharma (fake)", LocalDate.of(2028, 3, 31));
        m.vendorService.switchOff(TENANT, f.owner.userId(), c.id().toString());
        m.productCounts.put(b.id(), 12);

        PharmacyMasterViews.VendorPage page = m.vendorService.list(TENANT, f.pharmacist.userId(), null, null, null, null);

        assertThat(page.items()).extracting(PharmacyMasterViews.Vendor::id).containsExactly(a.id(), b.id());
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(25);
        assertThat(page.items().get(1).productsCount()).isEqualTo(12);
        assertThat(m.vendorService.list(TENANT, f.owner.userId(), null, "INACTIVE", null, null).items())
                .extracting(PharmacyMasterViews.Vendor::id).containsExactly(c.id());
        assertThat(m.vendorService.list(TENANT, f.owner.userId(), null, "BLOCKED", null, null).items())
                .extracting(PharmacyMasterViews.Vendor::id).containsExactly(a.id());
        assertThat(m.vendorService.list(TENANT, f.owner.userId(), null, "ALL", null, null).total()).isEqualTo(3);
        assertThat(m.vendorService.list(TENANT, f.owner.userId(), " BRAVO ", null, null, null).items()).hasSize(1);
    }

    @Test
    void aBadQueryValueIsAValidationErrorAndDefaultIsNotAChoice() {
        assertThat(errorsOf(() -> m.vendorService.list(TENANT, f.owner.userId(), null, "DEFAULT", null, null)))
                .extracting(FieldMessage::field).containsExactly("status");
        assertThat(errorsOf(() -> m.vendorService.list(TENANT, f.owner.userId(), "x".repeat(61), null, null, null)))
                .extracting(FieldMessage::field).containsExactly("q");
        assertThat(errorsOf(() -> m.vendorService.list(TENANT, f.owner.userId(), null, null, "0", "101")))
                .extracting(FieldMessage::field).containsExactly("size");
    }

    // ---- update, version, switch off ----

    @Test
    void aStaleVersionIsSettingsChangedWithTheCurrentVendor() {
        PharmacyMasterViews.Vendor v = add("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));
        m.vendorService.update(TENANT, f.owner.userId(), v.id().toString(), update(0, v, TODAY.plusDays(5)));

        assertThatThrownBy(() -> m.vendorService.update(TENANT, f.owner.userId(), v.id().toString(),
                update(0, v, TODAY.plusDays(9))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("settings-changed");
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.extensions().get("current")).isInstanceOf(PharmacyMasterViews.Vendor.class);
                });
    }

    @Test
    void aSaveThatChangesNothingWritesNothing() {
        PharmacyMasterViews.Vendor v = add("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));
        m.writes.clear();
        f.audit.clear();

        PharmacyMasterViews.Vendor same = m.vendorService.update(TENANT, f.owner.userId(), v.id().toString(),
                update(0, v, LocalDate.of(2028, 3, 31)));

        assertThat(same.version()).isZero();
        assertThat(m.writes).isEmpty();
        assertThat(f.audit).isEmpty();
    }

    @Test
    void renamingToAnotherVendorsNameIsRefusedButKeepingYourOwnIsFine() {
        add("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));
        PharmacyMasterViews.Vendor two = add("Test Vendor Two (fake)", LocalDate.of(2028, 3, 31));
        VendorUpdate rename = new VendorUpdate(0L, "test vendor one (FAKE)", Sent.of(GSTIN), true, Sent.of("TEST-DL-0002"),
                Sent.of(LocalDate.of(2028, 3, 31)), Sent.of(null), Sent.of(null), BigDecimal.valueOf(30), Sent.of(null),
                Sent.of(null), Sent.absent(), Sent.absent(), List.of(central.id()));

        assertThatThrownBy(() -> m.vendorService.update(TENANT, f.owner.userId(), two.id().toString(), rename))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("vendor-name-in-use"));
    }

    @Test
    void switchOffIsOneWayAndRepeatingItWritesNothing() {
        PharmacyMasterViews.Vendor v = add("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));

        PharmacyMasterViews.Vendor off = m.vendorService.switchOff(TENANT, f.owner.userId(), v.id().toString());
        long audits = f.auditActions().stream().filter("PHARMACY_VENDOR_SWITCHED_OFF"::equals).count();
        m.vendorService.switchOff(TENANT, f.owner.userId(), v.id().toString());

        assertThat(off.status()).isEqualTo("INACTIVE");
        assertThat(audits).isEqualTo(1);
        assertThat(f.auditActions().stream().filter("PHARMACY_VENDOR_SWITCHED_OFF"::equals).count()).isEqualTo(1);
        assertThat(m.vendorRows).hasSize(1);
    }

    @Test
    void switchOnMakesAnInactiveVendorActiveAndIsIdempotent() {
        PharmacyMasterViews.Vendor v = add("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));
        m.vendorService.switchOff(TENANT, f.owner.userId(), v.id().toString());
        m.writes.clear();

        PharmacyMasterViews.Vendor on = m.vendorService.switchOn(TENANT, f.owner.userId(), v.id().toString());

        assertThat(on.status()).isEqualTo("ACTIVE");
        assertThat(on.version()).isEqualTo(v.version() + 2);
        assertThat(m.writes).containsExactly("vendor.switchOn");
        assertThat(f.auditActions().stream().filter("PHARMACY_VENDOR_SWITCHED_ON"::equals).count()).isEqualTo(1);

        // again: 200, nothing written, no second audit
        m.writes.clear();
        PharmacyMasterViews.Vendor again = m.vendorService.switchOn(TENANT, f.owner.userId(), v.id().toString());
        assertThat(again.version()).isEqualTo(on.version());
        assertThat(m.writes).isEmpty();
        assertThat(f.auditActions().stream().filter("PHARMACY_VENDOR_SWITCHED_ON"::equals).count()).isEqualTo(1);
    }

    @Test
    void switchOnOfAVendorWhoseLicenceHasPassedAnswersBlockedAndWritesTheSwitchedOnAudit() {
        PharmacyMasterViews.Vendor v = add("Test Vendor One (fake)", TODAY.minusDays(1));
        m.vendorService.switchOff(TENANT, f.owner.userId(), v.id().toString());

        PharmacyMasterViews.Vendor on = m.vendorService.switchOn(TENANT, f.owner.userId(), v.id().toString());

        assertThat(on.status()).isEqualTo("BLOCKED");
        assertThat(on.blocked()).isTrue();
        assertThat(f.auditActions()).contains("PHARMACY_VENDOR_SWITCHED_ON");
    }

    @Test
    void switchOnIsOwnerOnlyAndAnUnknownOrBadIdIs404Or400() {
        PharmacyMasterViews.Vendor v = add("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));

        assertThatThrownBy(() -> m.vendorService.switchOn(TENANT, f.pharmacist.userId(), v.id().toString()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> m.vendorService.switchOn(TENANT, f.owner.userId(), UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> m.vendorService.switchOn(TENANT, f.owner.userId(), "nope"))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("bad-request"));
    }

    @Test
    void theTwoCreditLevelsAreOptionalNullableAndCheckedLikeTheStandardLevel() {
        // absent and null are both fine and give null
        VendorCreate none = new VendorCreate(UUID.randomUUID(), "Test Vendor One (fake)", Sent.of(null), false,
                Sent.of(null), Sent.of(null), Sent.of(null), Sent.of(null), BigDecimal.ZERO, Sent.of(null), Sent.of(null),
                Sent.of(null), Sent.absent(), List.of());
        PharmacyMasterViews.Vendor noneView = m.vendorService.create(TENANT, f.owner.userId(), none).view();
        assertThat(noneView.damagedCreditPercent()).isNull();
        assertThat(noneView.expiredCreditPercent()).isNull();

        // 0, 100 and two decimals are accepted and stored
        VendorCreate ok = new VendorCreate(UUID.randomUUID(), "Test Vendor Two (fake)", Sent.of(null), false,
                Sent.of(null), Sent.of(null), Sent.of(null), Sent.of(null), BigDecimal.ZERO, Sent.of(null), Sent.of("100"),
                Sent.of("0"), Sent.of("99.25"), List.of());
        PharmacyViews.Outcome<PharmacyMasterViews.Vendor> created = m.vendorService.create(TENANT, f.owner.userId(), ok);
        assertThat(created.created()).isTrue();
        assertThat(created.view().damagedCreditPercent()).isEqualTo("0");
        assertThat(created.view().expiredCreditPercent()).isEqualTo("99.25");
        assertThat(m.vendorService.get(TENANT, f.owner.userId(), created.view().id().toString()).expiredCreditPercent())
                .isEqualTo("99.25");
        assertThat(f.audit.stream().filter(a -> a.action().equals("PHARMACY_VENDOR_CREATED")).reduce((a, b) -> b)
                .orElseThrow().after()).containsEntry("damagedCreditPercent", "0").containsEntry("expiredCreditPercent", "99.25");

        // over 100, a third decimal, text and a negative number are refused with the contract text, per field
        for (String bad : new String[] {"100.01", "101", "5.123", "abc", "-1"}) {
            VendorCreate in = new VendorCreate(UUID.randomUUID(), "Test Vendor Three (fake)", Sent.of(null), false,
                    Sent.of(null), Sent.of(null), Sent.of(null), Sent.of(null), BigDecimal.ZERO, Sent.of(null),
                    Sent.of(null), Sent.of(bad), Sent.of(bad), List.of());
            assertThat(errorsOf(() -> m.vendorService.create(TENANT, f.owner.userId(), in))).as(bad).containsExactly(
                    new FieldMessage("damagedCreditPercent", "Enter a % from 0 to 100"),
                    new FieldMessage("expiredCreditPercent", "Enter a % from 0 to 100"));
        }
        // the same check on an edit
        PharmacyMasterViews.Vendor v = add("Test Vendor Four (fake)", LocalDate.of(2028, 3, 31));
        VendorUpdate base = update(1L, v, LocalDate.of(2028, 3, 31));
        VendorUpdate badEdit = new VendorUpdate(base.version(), base.name(), base.gstin(), base.licenceRequired(),
                base.licenceNo(), base.licenceValidTill(), base.contactName(), base.contactPhone(), base.creditDays(),
                base.returnDaysBeforeExpiry(), base.returnCreditPercent(), Sent.of("150"), Sent.absent(),
                base.suppliesToStoreIds());
        assertThat(errorsOf(() -> m.vendorService.update(TENANT, f.owner.userId(), v.id().toString(), badEdit)))
                .containsExactly(new FieldMessage("damagedCreditPercent", "Enter a % from 0 to 100"));
    }

    @Test
    void anEditKeepsACreditLevelItLeavesOutClearsOneSentAsNullAndChangesOneSentWithAValue() {
        VendorCreate in = new VendorCreate(UUID.randomUUID(), "Test Vendor One (fake)", Sent.of(null), false, Sent.of(null),
                Sent.of(null), Sent.of(null), Sent.of(null), BigDecimal.ZERO, Sent.of(null), Sent.of("90"), Sent.of("50"),
                Sent.of("20.5"), List.of());
        PharmacyMasterViews.Vendor v = m.vendorService.create(TENANT, f.owner.userId(), in).view();
        VendorUpdate base = update(v.version(), v, null);

        // both left out: nothing changes, nothing is written
        m.writes.clear();
        PharmacyMasterViews.Vendor same = m.vendorService.update(TENANT, f.owner.userId(), v.id().toString(), new VendorUpdate(
                base.version(), v.name(), Sent.of(null), false, Sent.of(null), Sent.of(null), Sent.of(null), Sent.of(null),
                BigDecimal.ZERO, Sent.of(null), Sent.of("90"), Sent.absent(), Sent.absent(), base.suppliesToStoreIds()));
        assertThat(same.damagedCreditPercent()).isEqualTo("50");
        assertThat(same.expiredCreditPercent()).isEqualTo("20.5");
        assertThat(m.writes).isEmpty();

        // damaged sent as null is cleared, expired left out is kept
        PharmacyMasterViews.Vendor cleared = m.vendorService.update(TENANT, f.owner.userId(), v.id().toString(),
                new VendorUpdate(same.version(), v.name(), Sent.of(null), false, Sent.of(null), Sent.of(null),
                        Sent.of(null), Sent.of(null), BigDecimal.ZERO, Sent.of(null), Sent.of("90"), Sent.of(null),
                        Sent.absent(), base.suppliesToStoreIds()));
        assertThat(cleared.damagedCreditPercent()).isNull();
        assertThat(cleared.expiredCreditPercent()).isEqualTo("20.5");
        assertThat(cleared.version()).isEqualTo(same.version() + 1);
        PharmacyFixture.Audit audit = f.audit.get(f.audit.size() - 1);
        assertThat(audit.action()).isEqualTo("PHARMACY_VENDOR_CHANGED");
        assertThat(audit.before()).containsEntry("damagedCreditPercent", "50");
        assertThat(audit.after()).containsEntry("damagedCreditPercent", null);

        // a new value on the other level
        PharmacyMasterViews.Vendor changed = m.vendorService.update(TENANT, f.owner.userId(), v.id().toString(),
                new VendorUpdate(cleared.version(), v.name(), Sent.of(null), false, Sent.of(null), Sent.of(null),
                        Sent.of(null), Sent.of(null), BigDecimal.ZERO, Sent.of(null), Sent.of("90"), Sent.absent(),
                        Sent.of("100"), base.suppliesToStoreIds()));
        assertThat(changed.expiredCreditPercent()).isEqualTo("100");
        assertThat(changed.damagedCreditPercent()).isNull();
    }

    @Test
    void anUnknownVendorIs404AndAPathThatIsNotAnIdIs400() {
        assertThatThrownBy(() -> m.vendorService.get(TENANT, f.owner.userId(), UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> m.vendorService.switchOff(TENANT, f.owner.userId(), UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> m.vendorService.get(TENANT, f.owner.userId(), "nope"))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("bad-request"));
    }

    // ---- who may do what, and the order of answers ----

    @Test
    void aPharmacistReadsButCannotWriteAndTheBodyIsNeverReadForThem() {
        PharmacyMasterViews.Vendor v = add("Test Vendor One (fake)", LocalDate.of(2028, 3, 31));
        PharmacyInputs.Body<VendorCreate> unreadable = () -> {
            throw new IllegalStateException("the body must not be read before the gate");
        };

        assertThat(m.vendorService.get(TENANT, f.pharmacist.userId(), v.id().toString()).name())
                .isEqualTo("Test Vendor One (fake)");
        assertThatThrownBy(() -> m.vendorService.create(TENANT, f.pharmacist.userId(), unreadable))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> m.vendorService.switchOff(TENANT, f.pharmacist.userId(), v.id().toString()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> m.vendorService.get(TENANT, f.nurse.userId(), v.id().toString()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void whenThePharmacyModuleIsOffEverythingIsModuleOff() {
        f.pharmacyOn = false;

        assertThatThrownBy(() -> m.vendorService.list(TENANT, f.owner.userId(), null, null, null, null))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> m.vendorService.create(TENANT, f.owner.userId(),
                create("Test Vendor One (fake)", TODAY))).isInstanceOf(ModuleOffException.class);
    }

    // ---- 0.3.3: suppliesStoreId ----

    @Test
    void suppliesStoreIdKeepsTheVendorsThatSupplyThatStoreAndAnUnknownStoreIsAnEmptyPage() {
        UUID store = UUID.randomUUID();
        PharmacyMasterViews.Vendor a = add("Alpha Distributors (fake)", LocalDate.of(2028, 3, 31));
        add("Bravo Medicals (fake)", LocalDate.of(2028, 3, 31));
        m.vendorStores.put(a.id(), java.util.Set.of(store));

        PharmacyMasterViews.VendorPage page = m.vendorService.list(TENANT, f.owner.userId(), null, null, store.toString(), null,
                null);

        assertThat(page.items()).extracting(PharmacyMasterViews.Vendor::id).containsExactly(a.id());
        assertThat(page.total()).isEqualTo(1);
        assertThat(m.vendorService.list(TENANT, f.owner.userId(), null, null, UUID.randomUUID().toString(), null, null).items())
                .isEmpty();
        assertThat(m.vendorService.list(TENANT, f.owner.userId(), null, null, "", null, null).total()).isEqualTo(2);
    }

    @Test
    void suppliesStoreIdMustBeAnIdAndNeedsAtLeastPharmacyAccess() {
        assertThat(errorsOf(() -> m.vendorService.list(TENANT, f.owner.userId(), null, null, "not-an-id", null, null)))
                .extracting(FieldMessage::field).containsExactly("suppliesStoreId");
        assertThatThrownBy(() -> m.vendorService.list(TENANT, f.nurse.userId(), null, null, UUID.randomUUID().toString(), null,
                null)).isInstanceOf(ForbiddenException.class);
    }
}
