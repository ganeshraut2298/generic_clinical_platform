package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.Delegate;
import com.sahaj.pharmacy.application.PharmacyViews.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-78-3: stores. One Central Store, the others issue from it; HOD and delegate are mappings; locks, switch-off, audit. */
class StoreServiceTest {

    private static final LocalDate TODAY = PharmacyFixture.TODAY;

    private final PharmacyFixture f = new PharmacyFixture();

    private Store create(PharmacyInputs.StoreCreate input) {
        return f.storeService.create(PharmacyFixture.TENANT, f.owner.userId(), input);
    }

    private PharmacyInputs.StoreCreate body(String name, String code, StoreType type) {
        return new PharmacyInputs.StoreCreate(name, code, type, null, null, null, null);
    }

    private Store update(Store store, PharmacyInputs.StoreUpdate input) {
        return f.storeService.update(PharmacyFixture.TENANT, f.owner.userId(), store.id(), input);
    }

    private PharmacyInputs.StoreUpdate updateOf(Store s, long version, String name, String code, StoreType type,
                                                UUID hod, Delegate delegate) {
        return PharmacyInputs.StoreUpdate.of(version, name, code, type, s.saleAllowed(), s.consumptionAllowed(), hod,
                delegate);
    }

    private static void assertConflict(Runnable action, String slug) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConflictException.class,
                ex -> assertThat(ex.problemType()).isEqualTo(slug));
    }

    private static void assertField(Runnable action, String field, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors()).extracting(e -> e.field() + ": " + e.message())
                        .contains(field + ": " + message));
    }

    // ---- create ----

    @Test
    void theCentralStoreComesFirstIssuesFromNobodyAndTheUnitRowIsCreatedLazily() {
        Store central = create(body(" Central Store ", "cs-1", StoreType.CENTRAL));

        assertThat(central.name()).isEqualTo("Central Store");
        assertThat(central.code()).isEqualTo("CS-1");
        assertThat(central.issuesFromStoreId()).isNull();
        assertThat(central.issuesFromName()).isNull();
        assertThat(central.status()).isEqualTo("ACTIVE");
        assertThat(central.hasStock()).isFalse();
        assertThat(central.lockedFields()).isEmpty();
        assertThat(central.approverState()).isEqualTo("NONE");
        assertThat(central.version()).isZero();
        assertThat(f.units).hasSize(1);
        assertThat(f.auditActions()).containsExactly("PHARMACY_STORE_CREATED");
    }

    @Test
    void everyOtherStoreIssuesFromTheCentralStoreAndHasTheDocumentedDefaults() {
        Store central = f.createCentral();
        Store pharmacy = create(body("Test Main Pharmacy", "PHARM", StoreType.PHARMACY));
        Store ward = create(body("Test Ward 3B Store", "W3B", StoreType.WARD));
        Store sub = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));

        assertThat(pharmacy.issuesFromStoreId()).isEqualTo(central.id());
        assertThat(pharmacy.issuesFromName()).isEqualTo("Central Store");
        assertThat(pharmacy.saleAllowed()).isTrue();
        assertThat(pharmacy.consumptionAllowed()).isFalse();
        assertThat(ward.saleAllowed()).isFalse();
        assertThat(ward.consumptionAllowed()).isTrue();
        assertThat(sub.saleAllowed()).isFalse();
        assertThat(sub.consumptionAllowed()).isFalse();
    }

    @Test
    void theFlagsCanBeChosenAtCreation() {
        f.createCentral();
        Store s = create(new PharmacyInputs.StoreCreate("Test Pharmacy 2", "P2", StoreType.PHARMACY, false, true, null, null));
        assertThat(s.saleAllowed()).isFalse();
        assertThat(s.consumptionAllowed()).isTrue();
    }

    @Test
    void anotherStoreBeforeTheCentralStoreIsRefused() {
        assertConflict(() -> create(body("Test OPD Store", "OPD", StoreType.SUB_STORE)), "central-store-missing");
        assertThat(f.storeRows).isEmpty();
    }

    @Test
    void aSecondCentralStoreIsRefused() {
        f.createCentral();
        assertConflict(() -> create(body("Central Two", "CS2", StoreType.CENTRAL)), "central-store-exists");
    }

    @Test
    void nameAndCodeAreUniqueInTheUnitIgnoringCase() {
        f.createCentral();
        create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        assertConflict(() -> create(body("test opd store", "OPD2", StoreType.SUB_STORE)), "store-name-in-use");
        assertConflict(() -> create(body("Test OPD Store 2", "opd", StoreType.SUB_STORE)), "store-code-in-use");
    }

    @Test
    void aSwitchedOffStoreKeepsItsNameAndCode() {
        Store central = f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertConflict(() -> create(body("Test OPD Store", "OPD3", StoreType.SUB_STORE)), "store-name-in-use");
        assertThat(central).isNotNull();
    }

    @Test
    void everyFieldErrorIsReportedTogether() {
        assertThatThrownBy(() -> create(new PharmacyInputs.StoreCreate("  ", "ab cd", null, null, null, null, null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field() + ": " + e.message()).containsExactly(
                                "name: Enter the store name",
                                "code: Use up to 10 letters, digits or hyphens",
                                "type: Choose the store type"));
        assertThatThrownBy(() -> create(new PharmacyInputs.StoreCreate("n".repeat(61), "", StoreType.CENTRAL, null, null,
                null, null))).isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                .extracting(e -> e.field() + ": " + e.message()).containsExactly(
                        "name: Keep the store name to 60 characters", "code: Enter the store code"));
        assertField(() -> create(body("Central", "ELEVENCHARS", StoreType.CENTRAL)), "code",
                "Use up to 10 letters, digits or hyphens");
        assertThat(f.storeRows).isEmpty();
    }

    @Test
    void anHodMustBeAnActiveOwnerDoctorNurseOrPharmacist() {
        f.createCentral();
        for (PharmacyFixture.Person bad : new PharmacyFixture.Person[] {f.storeStaff, f.frontDesk}) {
            assertField(() -> create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null,
                    bad.id(), null)), "hodMembershipId", "Choose an active owner, doctor, nurse or pharmacist");
        }
        assertField(() -> create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null,
                UUID.randomUUID(), null)), "hodMembershipId", "Choose an active owner, doctor, nurse or pharmacist");
        f.switchOff(f.nurse);
        assertField(() -> create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null,
                f.nurse.id(), null)), "hodMembershipId", "Choose an active owner, doctor, nurse or pharmacist");
    }

    @Test
    void theHodAndDelegateAreMappedInTheSameTransactionAndShowOnTheStore() {
        f.createCentral();
        Delegate delegate = new Delegate(f.nurse.id(), TODAY, TODAY.plusDays(13));

        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward 3B Store", "W3B", StoreType.WARD, false, true,
                f.doctor.id(), delegate));

        assertThat(ward.hod().membershipId()).isEqualTo(f.doctor.id());
        assertThat(ward.hod().name()).isEqualTo("Test Doctor One");
        assertThat(ward.hod().roleLabel()).isEqualTo("Doctor");
        assertThat(ward.hod().canSignIn()).isTrue();
        assertThat(ward.approverState()).isEqualTo("SET");
        assertThat(ward.delegate().membershipId()).isEqualTo(f.nurse.id());
        assertThat(ward.delegate().from()).isEqualTo(TODAY);
        assertThat(ward.delegate().to()).isEqualTo(TODAY.plusDays(13));
        assertThat(ward.delegate().state().name()).isEqualTo("CURRENT");
        assertThat(ward.staffCount()).isEqualTo(2);
        assertThat(f.auditActions()).contains("PHARMACY_STORE_CREATED", "PHARMACY_STAFF_MAPPED");
        assertThat(f.audit.stream().filter(a -> a.action().equals("PHARMACY_STAFF_MAPPED"))).hasSize(2);
    }

    @Test
    void delegateRulesAreChecked() {
        f.createCentral();
        PharmacyInputs.StoreCreate base = new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null,
                f.doctor.id(), null);
        assertField(() -> create(withDelegate(base, new Delegate(f.doctor.id(), TODAY, TODAY.plusDays(5)))),
                "delegate.membershipId", "The delegate must be a different person from the approver");
        assertField(() -> create(withDelegate(base, new Delegate(f.nurse.id(), TODAY, TODAY.minusDays(1)))), "delegate.to",
                "The end date must be on or after the start date");
        assertField(() -> create(withDelegate(base, new Delegate(f.nurse.id(), TODAY, TODAY.plusDays(90)))), "delegate.to",
                "A delegation can be at most 90 days");
        assertField(() -> create(withDelegate(base, new Delegate(f.nurse.id(), TODAY.minusDays(10), TODAY.minusDays(1)))),
                "delegate.to", "The end date cannot be in the past");
        assertField(() -> create(withDelegate(base, new Delegate(f.nurse.id(), TODAY, null))), "delegate.to",
                "Enter the end date");
        assertField(() -> create(withDelegate(base, new Delegate(f.nurse.id(), null, TODAY))), "delegate.from",
                "Enter the start date");
        assertField(() -> create(withDelegate(base, new Delegate(f.storeStaff.id(), TODAY, TODAY.plusDays(5)))),
                "delegate.membershipId", "Choose an active owner, doctor, nurse or pharmacist");
        // DEC-068 Q1: at most 90 calendar days counting both ends, so to - from <= 89
        assertThat(create(withDelegate(base, new Delegate(f.nurse.id(), TODAY, TODAY.plusDays(89)))).delegate()).isNotNull();
    }

    private static PharmacyInputs.StoreCreate withDelegate(PharmacyInputs.StoreCreate base, Delegate delegate) {
        return new PharmacyInputs.StoreCreate(base.name(), base.code(), base.type(), base.saleAllowed(),
                base.consumptionAllowed(), base.hodMembershipId(), delegate);
    }

    @Test
    void onlyTheOwnerWritesAndTheOtherRolesGetForbidden() {
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.pharmacist, f.nurse, f.storeStaff}) {
            assertThatThrownBy(() -> f.storeService.create(PharmacyFixture.TENANT, p.userId(),
                    body("Central Store", "CS", StoreType.CENTRAL))).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.storeService.switchOff(PharmacyFixture.TENANT, p.userId(), UUID.randomUUID()))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.storeService.update(PharmacyFixture.TENANT, p.userId(), UUID.randomUUID(),
                    PharmacyInputs.StoreUpdate.of(0L, "n", "c", StoreType.WARD, true, true, null, null)))
                    .isInstanceOf(ForbiddenException.class);
        }
        assertThat(f.storeRows).isEmpty();
    }

    // ---- reads ----

    @Test
    void theListHasTheCentralStoreFirstThenByNameAndHidesSwitchedOffStoresUnlessAsked() {
        Store central = f.createCentral();
        Store b = create(body("B store", "B", StoreType.SUB_STORE));
        Store a = create(body("A store", "A", StoreType.PHARMACY));
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), b.id());

        PharmacyViews.StoreList active = f.storeService.list(PharmacyFixture.TENANT, f.owner.userId(), false);
        assertThat(active.items()).extracting(Store::id).containsExactly(central.id(), a.id());
        assertThat(active.centralStoreId()).isEqualTo(central.id());

        PharmacyViews.StoreList all = f.storeService.list(PharmacyFixture.TENANT, f.pharmacist.userId(), true);
        assertThat(all.items()).extracting(Store::id).containsExactly(central.id(), a.id(), b.id());
        assertThat(all.items().get(2).status()).isEqualTo("INACTIVE");
    }

    @Test
    void anEmptyUnitListsNothingAndHasNoCentralStoreAndWritesNothing() {
        PharmacyViews.StoreList list = f.storeService.list(PharmacyFixture.TENANT, f.owner.userId(), false);
        assertThat(list.items()).isEmpty();
        assertThat(list.centralStoreId()).isNull();
        assertThat(f.writes).isEmpty();
    }

    @Test
    void aStoreOfAnotherClinicOrAnUnknownIdIs404() {
        UUID foreign = UUID.randomUUID();
        f.storeRows.put(foreign, new PharmacyFixture.Held<>(PharmacyFixture.OTHER_TENANT,
                new com.sahaj.pharmacy.domain.Store(foreign, UUID.randomUUID(), "Other", "OTH", StoreType.CENTRAL, null,
                        false, false, false, true, 0L)));
        assertThatThrownBy(() -> f.storeService.get(PharmacyFixture.TENANT, f.owner.userId(), foreign))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.storeService.get(PharmacyFixture.TENANT, f.owner.userId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.storeService.update(PharmacyFixture.TENANT, f.owner.userId(), foreign,
                PharmacyInputs.StoreUpdate.of(0L, "n", "c", StoreType.CENTRAL, true, true, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), foreign))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aSwitchedOffStoreIsStillReadable() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertThat(f.storeService.get(PharmacyFixture.TENANT, f.pharmacist.userId(), opd.id()).status())
                .isEqualTo("INACTIVE");
    }

    @Test
    void noApproverWhenTheHodCannotSignInAnymoreAndStaffCountSkipsThem() {
        f.createCentral();
        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null, f.nurse.id(), null));
        assertThat(ward.approverState()).isEqualTo("SET");
        f.switchOff(f.nurse);
        Store after = f.storeService.get(PharmacyFixture.TENANT, f.owner.userId(), ward.id());
        assertThat(after.approverState()).isEqualTo("NONE");
        assertThat(after.hod().canSignIn()).isFalse();
        assertThat(after.staffCount()).isZero();
    }

    /** Code review F-5: the mappings of a switched-off store are history; they stop counting. */
    @Test
    void aSwitchedOffStoreHasNoStaffCountAndNoApproverStateEvenThoughItsMappingsRemain() {
        f.createCentral();
        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null, f.nurse.id(), null));
        f.map(ward, f.doctor, com.sahaj.pharmacy.domain.StoreRole.STORE_USER);
        Store active = f.storeService.get(PharmacyFixture.TENANT, f.owner.userId(), ward.id());
        assertThat(active.staffCount()).isEqualTo(2);
        assertThat(active.approverState()).isEqualTo("SET");

        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), ward.id());
        Store off = f.storeService.get(PharmacyFixture.TENANT, f.owner.userId(), ward.id());

        assertThat(off.status()).isEqualTo("INACTIVE");
        assertThat(off.staffCount()).isZero();
        assertThat(off.approverState()).isEqualTo("NONE");
        assertThat(f.storeService.list(PharmacyFixture.TENANT, f.owner.userId(), true).items())
                .filteredOn(s -> s.id().equals(ward.id())).singleElement()
                .satisfies(s -> {
                    assertThat(s.staffCount()).isZero();
                    assertThat(s.approverState()).isEqualTo("NONE");
                });
    }

    // ---- update ----

    @Test
    void aChangeSavesAndBumpsTheVersionAndAuditsBeforeAndAfter() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        f.audit.clear();

        Store saved = update(opd, PharmacyInputs.StoreUpdate.of(0L, "Test OPD Sub-store", "opd-1", StoreType.PHARMACY, true,
                false, null, null));

        assertThat(saved.name()).isEqualTo("Test OPD Sub-store");
        assertThat(saved.code()).isEqualTo("OPD-1");
        assertThat(saved.type()).isEqualTo(StoreType.PHARMACY);
        assertThat(saved.saleAllowed()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(f.auditActions()).containsExactly("PHARMACY_STORE_CHANGED");
        assertThat(f.audit.get(0).before()).containsEntry("name", "Test OPD Store");
        assertThat(f.audit.get(0).after()).containsEntry("name", "Test OPD Sub-store");
    }

    @Test
    void aStaleVersionAnswersSettingsChangedWithTheCurrentStore() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        update(opd, updateOf(opd, 0, "Renamed", "OPD", StoreType.SUB_STORE, null, null));

        assertThatThrownBy(() -> update(opd, updateOf(opd, 0, "Again", "OPD", StoreType.SUB_STORE, null, null)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("settings-changed");
                    assertThat(((Store) ex.extensions().get("current")).name()).isEqualTo("Renamed");
                });
    }

    @Test
    void aSwitchedOffStoreCannotBeChanged() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertConflict(() -> update(opd, updateOf(opd, 1, "Renamed", "OPD", StoreType.SUB_STORE, null, null)),
                "store-inactive");
    }

    @Test
    void typeAndCodeAreLockedOnceTheStoreHasStockButTheNameAndFlagsCanStillChange() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        com.sahaj.pharmacy.domain.Store row = f.storeRows.get(opd.id()).row();
        f.putStore(new com.sahaj.pharmacy.domain.Store(row.id(), row.unitId(), row.name(), row.code(), row.type(),
                row.issuesFromStoreId(), row.saleAllowed(), row.consumptionAllowed(), true, true, row.version()));

        assertThatThrownBy(() -> update(opd, updateOf(opd, 0, "Test OPD Store", "NEW", StoreType.PHARMACY, null, null)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("store-has-stock");
                    assertThat(ex.getMessage()).isEqualTo("This store has stock. Type and code cannot change.");
                    assertThat(ex.extensions().get("lockedFields")).isEqualTo(List.of("TYPE", "CODE"));
                });
        assertThatThrownBy(() -> update(opd, updateOf(opd, 0, "Test OPD Store", "OPD", StoreType.PHARMACY, null, null)))
                .isInstanceOfSatisfying(PharmacyProblemException.class,
                        ex -> assertThat(ex.extensions().get("lockedFields")).isEqualTo(List.of("TYPE")));

        Store renamed = update(opd, updateOf(opd, 0, "Renamed OPD", "OPD", StoreType.SUB_STORE, null, null));
        assertThat(renamed.name()).isEqualTo("Renamed OPD");
        assertThat(renamed.hasStock()).isTrue();
        assertThat(renamed.lockedFields()).containsExactly("TYPE", "CODE", "ISSUES_FROM");
    }

    @Test
    void theTypeCannotMoveToOrFromCentral() {
        Store central = f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        assertConflict(() -> update(opd, updateOf(opd, 0, "Test OPD Store", "OPD", StoreType.CENTRAL, null, null)),
                "central-store-exists");
        assertConflict(() -> update(central, updateOf(central, 0, "Central Store", "CS", StoreType.PHARMACY, null, null)),
                "central-store-in-use");
    }

    @Test
    void theOnlyStoreCannotLeaveCentralEither() {
        Store central = f.createCentral();
        assertConflict(() -> update(central, updateOf(central, 0, "Central Store", "CS", StoreType.PHARMACY, null, null)),
                "central-store-missing");
    }

    @Test
    void nameAndCodeUniquenessAppliesToChangesButNotToTheStoreItself() {
        f.createCentral();
        Store a = create(body("Store A", "A", StoreType.SUB_STORE));
        Store b = create(body("Store B", "B", StoreType.SUB_STORE));
        assertThat(update(a, updateOf(a, 0, "Store A", "A", StoreType.SUB_STORE, null, null)).version()).isEqualTo(1);
        assertConflict(() -> update(b, updateOf(b, 0, "store a", "B", StoreType.SUB_STORE, null, null)), "store-name-in-use");
        assertConflict(() -> update(b, updateOf(b, 0, "Store B", "a", StoreType.SUB_STORE, null, null)), "store-code-in-use");
    }

    @Test
    void aMissingVersionIsAFieldError() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        assertField(() -> update(opd, PharmacyInputs.StoreUpdate.of(null, "x", "OPD", StoreType.SUB_STORE, true, true,
                null, null)), "version", "Reload the page and try again.");
    }

    // ---- a field the request leaves out is never read as null ----

    private static final PharmacyInputs.Sent<Boolean> OMITTED_FLAG = PharmacyInputs.Sent.absent();

    @Test
    void aChangeThatLeavesOutAFieldTheContractRequiresIs400AndChangesNothing() {
        f.createCentral();
        Store ward = create(withPeople(body("Test Ward", "W1", StoreType.WARD), f.nurse.id(), null));
        long version = ward.version();
        int audits = f.audit.size();
        PharmacyInputs.StoreUpdate full = updateOf(ward, version, "Test Ward", "W1", StoreType.WARD, f.nurse.id(), null);
        PharmacyInputs.Sent<UUID> noHod = PharmacyInputs.Sent.absent();
        PharmacyInputs.Sent<Delegate> noDelegate = PharmacyInputs.Sent.absent();
        Object[][] cases = {
                {new PharmacyInputs.StoreUpdate(version, "Test Ward", "W1", StoreType.WARD, OMITTED_FLAG,
                        full.consumptionAllowed(), full.hodMembershipId(), full.delegate()), "saleAllowed"},
                {new PharmacyInputs.StoreUpdate(version, "Test Ward", "W1", StoreType.WARD, full.saleAllowed(),
                        OMITTED_FLAG, full.hodMembershipId(), full.delegate()), "consumptionAllowed"},
                {new PharmacyInputs.StoreUpdate(version, "Test Ward", "W1", StoreType.WARD, full.saleAllowed(),
                        full.consumptionAllowed(), noHod, full.delegate()), "hodMembershipId"},
                {new PharmacyInputs.StoreUpdate(version, "Test Ward", "W1", StoreType.WARD, full.saleAllowed(),
                        full.consumptionAllowed(), full.hodMembershipId(), noDelegate), "delegate"},
                {new PharmacyInputs.StoreUpdate(version, "Test Ward", "W1", StoreType.WARD, PharmacyInputs.Sent.of(null),
                        full.consumptionAllowed(), full.hodMembershipId(), full.delegate()), "saleAllowed"}};
        for (Object[] c : cases) {
            assertField(() -> update(ward, (PharmacyInputs.StoreUpdate) c[0]), (String) c[1], UnitService.REQUIRED);
        }
        Store after = f.storeService.get(PharmacyFixture.TENANT, f.owner.userId(), ward.id());
        assertThat(after.version()).isEqualTo(version);
        assertThat(after.saleAllowed()).isEqualTo(ward.saleAllowed());
        assertThat(after.consumptionAllowed()).isEqualTo(ward.consumptionAllowed());
        assertThat(after.hod()).isNotNull();
        assertThat(f.audit).hasSize(audits);
    }

    @Test
    void anExplicitNullHodOrDelegateIsAllowedAndMeansNone() {
        f.createCentral();
        Store ward = create(withPeople(body("Test Ward", "W1", StoreType.WARD), f.nurse.id(), null));

        Store cleared = update(ward, updateOf(ward, ward.version(), "Test Ward", "W1", StoreType.WARD, null, null));

        assertThat(cleared.hod()).isNull();
    }

    @Test
    void everyMissingFieldIsReportedTogether() {
        f.createCentral();
        Store ward = create(body("Test Ward", "W1", StoreType.WARD));
        PharmacyInputs.Sent<Boolean> noFlag = PharmacyInputs.Sent.absent();
        PharmacyInputs.Sent<UUID> noHod = PharmacyInputs.Sent.absent();
        PharmacyInputs.Sent<Delegate> noDelegate = PharmacyInputs.Sent.absent();
        assertThatThrownBy(() -> update(ward, new PharmacyInputs.StoreUpdate(null, "Test Ward", "W1", StoreType.WARD,
                noFlag, noFlag, noHod, noDelegate))).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors()).extracting(e -> e.field()).containsExactly("version", "saleAllowed",
                        "consumptionAllowed", "hodMembershipId", "delegate"));
    }

    private static PharmacyInputs.StoreCreate withPeople(PharmacyInputs.StoreCreate base, UUID hod, Delegate delegate) {
        return new PharmacyInputs.StoreCreate(base.name(), base.code(), base.type(), base.saleAllowed(),
                base.consumptionAllowed(), hod, delegate);
    }

    // ---- contract order: module off, access level, then path id and query value, then the store ----

    @Test
    void aMalformedPathIdOrQueryValueIsReadAfterTheModuleAndAccessChecks() {
        UUID tenant = PharmacyFixture.TENANT;
        PharmacyInputs.StoreUpdate update = PharmacyInputs.StoreUpdate.of(0L, "n", "c", StoreType.WARD, true, true, null, null);
        f.pharmacyOn = false;
        assertThatThrownBy(() -> f.storeService.get(tenant, f.owner.userId(), "not-a-uuid"))
                .isInstanceOf(com.sahaj.platform.api.ModuleOffException.class);
        assertThatThrownBy(() -> f.storeService.list(tenant, f.owner.userId(), "perhaps"))
                .isInstanceOf(com.sahaj.platform.api.ModuleOffException.class);
        f.pharmacyOn = true;

        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.nurse, f.storeStaff}) {
            assertThatThrownBy(() -> f.storeService.get(tenant, p.userId(), "not-a-uuid")).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.storeService.list(tenant, p.userId(), "perhaps")).isInstanceOf(ForbiddenException.class);
        }
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.pharmacist, f.nurse, f.storeStaff}) {
            assertThatThrownBy(() -> f.storeService.update(tenant, p.userId(), "not-a-uuid", update))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.storeService.switchOff(tenant, p.userId(), "not-a-uuid"))
                    .isInstanceOf(ForbiddenException.class);
        }
        for (Runnable bad : new Runnable[] {
                () -> f.storeService.get(tenant, f.owner.userId(), "not-a-uuid"),
                () -> f.storeService.update(tenant, f.owner.userId(), "12345", update),
                () -> f.storeService.switchOff(tenant, f.owner.userId(), "x")}) {
            assertThatThrownBy(bad::run).isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                assertThat(ex.status()).isEqualTo(400);
                assertThat(ex.slug()).isEqualTo("bad-request");
                assertThat(ex.getMessage()).doesNotContain("not-a-uuid").doesNotContain("12345");
            });
        }
        assertField(() -> f.storeService.list(tenant, f.owner.userId(), "perhaps"), "includeInactive", "Choose a valid option");
    }

    @Test
    void aWellFormedTextIdWorksLikeTheTypedOne() {
        Store central = f.createCentral();
        assertThat(f.storeService.get(PharmacyFixture.TENANT, f.owner.userId(), central.id().toString()).id())
                .isEqualTo(central.id());
        assertThat(f.storeService.list(PharmacyFixture.TENANT, f.owner.userId(), "true").items()).hasSize(1);
        assertThat(f.storeService.list(PharmacyFixture.TENANT, f.owner.userId(), (String) null).items()).hasSize(1);
        assertThat(f.storeService.update(PharmacyFixture.TENANT, f.owner.userId(), central.id().toString(),
                updateOf(central, central.version(), "Central Store", "CS", StoreType.CENTRAL, null, null)).id())
                .isEqualTo(central.id());
    }

    // ---- HOD and delegate changes ----

    private StoreStaff activeRole(Store s, StoreRole role) {
        return f.staffRepository.activeWithRole(PharmacyFixture.TENANT, s.id(), role).orElse(null);
    }

    private StoreStaff rowOf(Store s, PharmacyFixture.Person p) {
        return f.staffRepository.findForMember(PharmacyFixture.TENANT, s.id(), p.id()).orElseThrow();
    }

    @Test
    void aNewHodTurnsThePreviousOneIntoAStoreUserKeptAndAudited() {
        f.createCentral();
        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null, f.doctor.id(), null));
        f.audit.clear();

        Store changed = update(ward, updateOf(ward, 0, "Test Ward", "W1", StoreType.WARD, f.nurse.id(), null));

        assertThat(changed.hod().membershipId()).isEqualTo(f.nurse.id());
        assertThat(rowOf(ward, f.doctor).storeRole()).isEqualTo(StoreRole.STORE_USER);
        assertThat(rowOf(ward, f.doctor).active()).isTrue();
        assertThat(activeRole(ward, StoreRole.APPROVER).membershipId()).isEqualTo(f.nurse.id());
        assertThat(f.auditActions()).contains("PHARMACY_STORE_CHANGED", "PHARMACY_STAFF_MAPPING_CHANGED",
                "PHARMACY_STAFF_MAPPED");
    }

    @Test
    void clearingTheHodLeavesNoApproverAndKeepsThePerson() {
        f.createCentral();
        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null, f.doctor.id(), null));

        Store changed = update(ward, updateOf(ward, 0, "Test Ward", "W1", StoreType.WARD, null, null));

        assertThat(changed.hod()).isNull();
        assertThat(changed.approverState()).isEqualTo("NONE");
        assertThat(rowOf(ward, f.doctor).storeRole()).isEqualTo(StoreRole.STORE_USER);
    }

    @Test
    void aNewDelegateReplacesTheOldOneAndNullEndsTheDelegation() {
        f.createCentral();
        Delegate first = new Delegate(f.nurse.id(), TODAY, TODAY.plusDays(10));
        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null, f.doctor.id(), first));

        Store swapped = update(ward, updateOf(ward, 0, "Test Ward", "W1", StoreType.WARD, f.doctor.id(),
                new Delegate(f.pharmacist.id(), TODAY, TODAY.plusDays(20))));
        assertThat(swapped.delegate().membershipId()).isEqualTo(f.pharmacist.id());
        assertThat(rowOf(ward, f.nurse).storeRole()).isEqualTo(StoreRole.STORE_USER);
        assertThat(rowOf(ward, f.nurse).delegateFrom()).isNull();

        Store ended = update(ward, updateOf(ward, swapped.version(), "Test Ward", "W1", StoreType.WARD, f.doctor.id(), null));
        assertThat(ended.delegate()).isNull();
        assertThat(rowOf(ward, f.pharmacist).storeRole()).isEqualTo(StoreRole.STORE_USER);
    }

    @Test
    void theSameDelegateWithNewDatesIsUpdatedInPlace() {
        f.createCentral();
        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null, f.doctor.id(),
                new Delegate(f.nurse.id(), TODAY, TODAY.plusDays(10))));
        Store changed = update(ward, updateOf(ward, 0, "Test Ward", "W1", StoreType.WARD, f.doctor.id(),
                new Delegate(f.nurse.id(), TODAY, TODAY.plusDays(30))));
        assertThat(changed.delegate().to()).isEqualTo(TODAY.plusDays(30));
        assertThat(f.staffRows.values().stream().filter(h -> h.row().membershipId().equals(f.nurse.id()))).hasSize(1);
    }

    @Test
    void theDelegateCanBecomeTheHodWithoutALeftOverDelegateRow() {
        f.createCentral();
        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null, f.doctor.id(),
                new Delegate(f.nurse.id(), TODAY, TODAY.plusDays(10))));

        Store changed = update(ward, updateOf(ward, 0, "Test Ward", "W1", StoreType.WARD, f.nurse.id(), null));

        assertThat(changed.hod().membershipId()).isEqualTo(f.nurse.id());
        assertThat(changed.delegate()).isNull();
        assertThat(rowOf(ward, f.nurse).storeRole()).isEqualTo(StoreRole.APPROVER);
        assertThat(rowOf(ward, f.nurse).delegateFrom()).isNull();
    }

    @Test
    void anEndedDelegateSentBackUnchangedIsNotAnErrorButAChangedPastDateIs() {
        f.createCentral();
        Store ward = create(new PharmacyInputs.StoreCreate("Test Ward", "W1", StoreType.WARD, null, null, f.doctor.id(),
                new Delegate(f.nurse.id(), TODAY, TODAY.plusDays(2))));
        StoreStaff delegateRow = activeRole(ward, StoreRole.DELEGATE);
        f.putStaff(new StoreStaff(delegateRow.id(), ward.id(), f.nurse.id(), StoreRole.DELEGATE, TODAY.minusDays(20),
                TODAY.minusDays(10), true, delegateRow.version()));

        Store same = update(ward, updateOf(ward, 0, "Renamed Ward", "W1", StoreType.WARD, f.doctor.id(),
                new Delegate(f.nurse.id(), TODAY.minusDays(20), TODAY.minusDays(10))));
        assertThat(same.delegate().state().name()).isEqualTo("ENDED");

        assertField(() -> update(ward, updateOf(ward, same.version(), "Renamed Ward", "W1", StoreType.WARD, f.doctor.id(),
                new Delegate(f.nurse.id(), TODAY.minusDays(20), TODAY.minusDays(5)))), "delegate.to",
                "The end date cannot be in the past");
    }

    // ---- switch-off ----

    @Test
    void switchOffMakesTheStoreInactiveAuditsItAndIsSafeToRepeat() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        f.audit.clear();

        Store off = f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertThat(off.status()).isEqualTo("INACTIVE");
        assertThat(f.auditActions()).containsExactly("PHARMACY_STORE_SWITCHED_OFF");

        f.audit.clear();
        f.writes.clear();
        Store again = f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertThat(again.status()).isEqualTo("INACTIVE");
        assertThat(f.audit).isEmpty();
        assertThat(f.writes).isEmpty();
    }

    @Test
    void aStoreWithStockCannotBeSwitchedOff() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        com.sahaj.pharmacy.domain.Store row = f.storeRows.get(opd.id()).row();
        f.putStore(new com.sahaj.pharmacy.domain.Store(row.id(), row.unitId(), row.name(), row.code(), row.type(),
                row.issuesFromStoreId(), row.saleAllowed(), row.consumptionAllowed(), true, true, row.version()));

        assertThatThrownBy(() -> f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id()))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("store-has-stock");
                    assertThat(ex.getMessage()).isEqualTo("This store has stock. Clear it in Inventory first.");
                });
        assertThat(f.storeRows.get(opd.id()).row().active()).isTrue();
    }

    @Test
    void theCentralStoreCannotBeSwitchedOffWhileAnotherStoreIsActive() {
        Store central = f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        assertConflict(() -> f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), central.id()),
                "central-store-in-use");
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertThat(f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), central.id()).status())
                .isEqualTo("INACTIVE");
    }

    @Test
    void switchingOffAStoreStopsItsMappingsFromCounting() {
        f.createCentral();
        Store opd = create(body("Test OPD Store", "OPD", StoreType.SUB_STORE));
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        assertThat(f.staffRepository.hasActiveMapping(PharmacyFixture.TENANT, f.nurse.id())).isTrue();

        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());

        assertThat(f.staffRepository.hasActiveMapping(PharmacyFixture.TENANT, f.nurse.id())).isFalse();
        // the mapping row stays as a record
        assertThat(f.staffRows.values()).anyMatch(h -> h.row().membershipId().equals(f.nurse.id()) && h.row().active());
    }
}
