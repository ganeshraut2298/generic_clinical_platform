package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyViews.StaffMapping;
import com.sahaj.pharmacy.application.PharmacyViews.Store;
import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-78-4: mapping staff to stores. Which store role each team role may hold (Store staff only as Store user or Central
 * Store user, and there is no pharmacist store role), derived access columns, one HOD and one delegate, re-mapping, history.
 */
class StaffMappingServiceTest {

    private static final LocalDate TODAY = PharmacyFixture.TODAY;

    private final PharmacyFixture f = new PharmacyFixture();
    private final Store central = f.createCentral();
    private final Store ward = f.createStore("Test Ward 3B Store", "W3B", StoreType.WARD);
    private final Store pharmacy = f.createStore("Test Main Pharmacy", "PHARM", StoreType.PHARMACY);

    private StaffMapping map(Store store, PharmacyFixture.Person p, StoreRole role) {
        return f.staffService.map(PharmacyFixture.TENANT, f.owner.userId(), store.id(),
                new PharmacyInputs.MapStaff(p.id(), role, null, null));
    }

    private StaffMapping mapDelegate(Store store, PharmacyFixture.Person p, LocalDate from, LocalDate to) {
        return f.staffService.map(PharmacyFixture.TENANT, f.owner.userId(), store.id(),
                new PharmacyInputs.MapStaff(p.id(), StoreRole.DELEGATE, from, to));
    }

    private PharmacyViews.StaffMappings list(Store store, boolean includeRemoved) {
        return f.staffService.list(PharmacyFixture.TENANT, f.owner.userId(), store.id(), includeRemoved);
    }

    private static void assertField(Runnable action, String field, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors()).extracting(e -> e.field() + ": " + e.message())
                        .contains(field + ": " + message));
    }

    private static void assertConflict(Runnable action, String slug) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConflictException.class,
                ex -> assertThat(ex.problemType()).isEqualTo(slug));
    }

    // ---- mapping and the derived columns ----

    @Test
    void aStoreStaffMemberIsMappedAsStoreUserWithTheDerivedColumnsAndAnAuditRow() {
        f.audit.clear();
        StaffMapping m = map(ward, f.storeStaff, StoreRole.STORE_USER);

        assertThat(m.storeId()).isEqualTo(ward.id());
        assertThat(m.name()).isEqualTo("Test Store Staff One");
        assertThat(m.role()).isEqualTo("STORE_STAFF");
        assertThat(m.roleLabel()).isEqualTo("Store staff");
        assertThat(m.isHod()).isFalse();
        assertThat(m.storeRole()).isEqualTo(StoreRole.STORE_USER);
        assertThat(m.effectiveStoreRole()).isEqualTo(StoreRole.STORE_USER);
        assertThat(m.delegateFrom()).isNull();
        assertThat(m.delegateState()).isNull();
        assertThat(m.memberState()).isEqualTo("ACTIVE");
        assertThat(m.needsReview()).isFalse();
        assertThat(m.status()).isEqualTo("ACTIVE");
        assertThat(m.access().raiseIndent()).isTrue();
        assertThat(m.access().approve()).isFalse();
        assertThat(m.access().receive()).isTrue();
        assertThat(m.access().receiveScheduleX()).isFalse();
        assertThat(m.access().consumeReturn()).isTrue(); // a ward store allows consumption entry
        assertThat(m.access().uploadOpeningStock()).isFalse();
        assertThat(m.otherStores()).isEmpty();
        assertThat(f.auditActions()).containsExactly("PHARMACY_STAFF_MAPPED");
    }

    @Test
    void aPharmacistMappedAsStoreUserShowsScheduleXAndUpload() {
        StaffMapping m = map(pharmacy, f.pharmacist, StoreRole.STORE_USER);
        assertThat(m.access().receiveScheduleX()).isTrue();
        assertThat(m.access().uploadOpeningStock()).isTrue();
        assertThat(m.access().consumeReturn()).isFalse(); // a pharmacy does not allow consumption entry
    }

    @Test
    void theSamePersonCanBeMappedToSeveralStoresAndSeesTheOthers() {
        map(ward, f.nurse, StoreRole.STORE_USER);
        map(pharmacy, f.nurse, StoreRole.STORE_USER);
        StaffMapping inWard = list(ward, false).items().get(0);
        assertThat(inWard.otherStores()).extracting(PharmacyViews.OtherStore::name).containsExactly("Test Main Pharmacy");
    }

    @Test
    void aSecondMappingOfTheSamePersonIsRefused() {
        map(ward, f.nurse, StoreRole.STORE_USER);
        assertConflict(() -> map(ward, f.nurse, StoreRole.STORE_USER), "already-mapped");
    }

    // ---- which store role for which team role ----

    @Test
    void aStoreStaffMemberCannotBeApproverOrDelegate() {
        assertField(() -> map(ward, f.storeStaff, StoreRole.APPROVER), "storeRole", "This person cannot have this store role");
        assertField(() -> mapDelegate(ward, f.storeStaff, TODAY, TODAY.plusDays(5)), "storeRole",
                "This person cannot have this store role");
        assertThat(f.staffRows).isEmpty();
    }

    @Test
    void frontDeskCounsellorAndVisitingDoctorMayOnlyBeStoreUser() {
        PharmacyFixture.Person counsellor = f.person("COUNSELLOR", "Test Counsellor One", true);
        PharmacyFixture.Person visiting = f.person("VISITING_DOCTOR", "Test Visiting One", true);
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.frontDesk, counsellor, visiting}) {
            assertField(() -> map(ward, p, StoreRole.APPROVER), "storeRole", "This person cannot have this store role");
            assertField(() -> map(central, p, StoreRole.CENTRAL_STORE_USER), "storeRole",
                    "This person cannot have this store role");
            assertThat(map(ward, p, StoreRole.STORE_USER).storeRole()).isEqualTo(StoreRole.STORE_USER);
        }
    }

    @Test
    void aBillingMemberMayBeCentralStoreUserButNotApprover() {
        PharmacyFixture.Person billing = f.person("BILLING", "Test Billing One", true);
        assertField(() -> map(central, billing, StoreRole.APPROVER), "storeRole", "This person cannot have this store role");
        assertThat(map(central, billing, StoreRole.CENTRAL_STORE_USER).storeRole()).isEqualTo(StoreRole.CENTRAL_STORE_USER);
    }

    @Test
    void centralStoreUserIsOnlyForTheCentralStore() {
        assertField(() -> map(ward, f.storeStaff, StoreRole.CENTRAL_STORE_USER), "storeRole",
                "Central Store user is only for the Central Store");
        StaffMapping ok = map(central, f.storeStaff, StoreRole.CENTRAL_STORE_USER);
        assertThat(ok.access().raiseIndent()).isFalse();
        assertThat(ok.access().receive()).isTrue();
        assertThat(ok.access().uploadOpeningStock()).isTrue();
    }

    @Test
    void thereIsNoWayToGiveAPharmacistRoleToAnyoneWhoIsNotAPharmacist() {
        // the only way to Schedule X and to the pharmacist-only upload is the team role, whatever store role is chosen
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.nurse, f.doctor, f.storeStaff, f.frontDesk}) {
            StaffMapping m = map(ward, p, StoreRole.STORE_USER);
            assertThat(m.access().receiveScheduleX()).as(p.member().role()).isFalse();
        }
        for (StoreRole role : StoreRole.values()) {
            assertThat(role.name()).isNotEqualTo("PHARMACIST");
        }
    }

    @Test
    void theMemberMustBeActiveAndOfThisClinic() {
        f.switchOff(f.nurse);
        assertField(() -> map(ward, f.nurse, StoreRole.STORE_USER), "membershipId", "Choose an active team member");
        assertField(() -> f.staffService.map(PharmacyFixture.TENANT, f.owner.userId(), ward.id(),
                new PharmacyInputs.MapStaff(UUID.randomUUID(), StoreRole.STORE_USER, null, null)), "membershipId",
                "Choose an active team member");
        assertField(() -> f.staffService.map(PharmacyFixture.TENANT, f.owner.userId(), ward.id(),
                new PharmacyInputs.MapStaff(null, null, null, null)), "storeRole", "Choose a store role");
    }

    // ---- HOD and delegate seats ----

    @Test
    void aSecondApproverOrDelegateIsRefusedWithApproverExists() {
        map(ward, f.doctor, StoreRole.APPROVER);
        assertThatThrownBy(() -> map(ward, f.nurse, StoreRole.APPROVER)).isInstanceOfSatisfying(ConflictException.class,
                ex -> {
                    assertThat(ex.problemType()).isEqualTo("approver-exists");
                    assertThat(ex.getMessage()).isEqualTo("This store already has an approver. Change it on the store.");
                });
        mapDelegate(ward, f.nurse, TODAY, TODAY.plusDays(5));
        assertThatThrownBy(() -> mapDelegate(ward, f.pharmacist, TODAY, TODAY.plusDays(5)))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("This store already has a delegate. Change it on the store."));
    }

    @Test
    void theHodShowsAsHodAndApprovesAndListsFirstThenTheDelegate() {
        map(ward, f.storeStaff, StoreRole.STORE_USER);
        mapDelegate(ward, f.nurse, TODAY, TODAY.plusDays(5));
        map(ward, f.doctor, StoreRole.APPROVER);

        var items = list(ward, false).items();

        assertThat(items).extracting(StaffMapping::name).containsExactly("Test Doctor One", "Test Nurse One",
                "Test Store Staff One");
        assertThat(items.get(0).isHod()).isTrue();
        assertThat(items.get(0).access().approve()).isTrue();
        assertThat(items.get(1).delegateState()).isEqualTo(DelegateState.CURRENT);
        assertThat(items.get(1).effectiveStoreRole()).isEqualTo(StoreRole.DELEGATE);
        assertThat(items.get(1).access().approve()).isTrue();
    }

    @Test
    void aDelegateMustNotBeTheHodAndDatesAreChecked() {
        map(ward, f.doctor, StoreRole.APPROVER);
        assertField(() -> mapDelegate(ward, f.doctor, TODAY, TODAY.plusDays(5)), "membershipId",
                "The delegate must be a different person from the approver");
        assertField(() -> mapDelegate(ward, f.nurse, null, null), "delegateFrom", "Enter the start date");
        assertField(() -> mapDelegate(ward, f.nurse, TODAY, TODAY.plusDays(90)), "delegateTo",
                "A delegation can be at most 90 days");
        assertField(() -> mapDelegate(ward, f.nurse, TODAY, TODAY.plusDays(91)), "delegateTo",
                "A delegation can be at most 90 days");
        assertField(() -> mapDelegate(ward, f.nurse, TODAY.plusDays(3), TODAY), "delegateTo",
                "The end date must be on or after the start date");
    }

    @Test
    void aDelegateWhoseDatesEndedCountsAsStoreUserAndShowsEnded() {
        StaffMapping m = mapDelegate(ward, f.nurse, TODAY.minusDays(10), TODAY.plusDays(1));
        f.putStaff(new StoreStaff(m.id(), ward.id(), f.nurse.id(), StoreRole.DELEGATE, TODAY.minusDays(20),
                TODAY.minusDays(10), true, m.version()));

        StaffMapping ended = list(ward, false).items().get(0);

        assertThat(ended.delegateState()).isEqualTo(DelegateState.ENDED);
        assertThat(ended.storeRole()).isEqualTo(StoreRole.DELEGATE);
        assertThat(ended.effectiveStoreRole()).isEqualTo(StoreRole.STORE_USER);
        assertThat(ended.access().approve()).isFalse();
    }

    // ---- changing ----

    @Test
    void changingTheStoreRoleSavesAndAuditsBeforeAndAfter() {
        StaffMapping m = map(ward, f.nurse, StoreRole.STORE_USER);
        f.audit.clear();

        StaffMapping changed = f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id(),
                new PharmacyInputs.ChangeMapping(m.version(), StoreRole.APPROVER, null, null));

        assertThat(changed.storeRole()).isEqualTo(StoreRole.APPROVER);
        assertThat(changed.isHod()).isTrue();
        assertThat(changed.version()).isEqualTo(m.version() + 1);
        assertThat(f.auditActions()).containsExactly("PHARMACY_STAFF_MAPPING_CHANGED");
        assertThat(f.audit.get(0).before()).containsEntry("storeRole", "STORE_USER");
        assertThat(f.audit.get(0).after()).containsEntry("storeRole", "APPROVER");
    }

    @Test
    void endingADelegationEarlyBySendingANewEndDate() {
        StaffMapping m = mapDelegate(ward, f.nurse, TODAY, TODAY.plusDays(20));
        StaffMapping changed = f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id(),
                new PharmacyInputs.ChangeMapping(m.version(), StoreRole.DELEGATE, TODAY, TODAY.plusDays(3)));
        assertThat(changed.delegateTo()).isEqualTo(TODAY.plusDays(3));
        assertField(() -> f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id(),
                new PharmacyInputs.ChangeMapping(changed.version(), StoreRole.DELEGATE, TODAY, TODAY.minusDays(1))),
                "delegateTo", "The end date must be on or after the start date");
    }

    @Test
    void aChangeToAStoreRoleNotAllowedForTheTeamRoleOrASecondApproverIsRefused() {
        StaffMapping staff = map(ward, f.storeStaff, StoreRole.STORE_USER);
        map(ward, f.doctor, StoreRole.APPROVER);
        StaffMapping nurse = map(ward, f.nurse, StoreRole.STORE_USER);

        assertField(() -> f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), staff.id(),
                new PharmacyInputs.ChangeMapping(staff.version(), StoreRole.APPROVER, null, null)), "storeRole",
                "This person cannot have this store role");
        assertConflict(() -> f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), nurse.id(),
                new PharmacyInputs.ChangeMapping(nurse.version(), StoreRole.APPROVER, null, null)), "approver-exists");
    }

    @Test
    void aChangeForASwitchedOffOrMissingMemberIsRefusedAndNeverSkipsTheRoleCheck() {
        StaffMapping nurse = map(ward, f.nurse, StoreRole.STORE_USER);
        StaffMapping staff = map(ward, f.storeStaff, StoreRole.STORE_USER);
        f.switchOff(f.nurse);
        f.people.remove(f.storeStaff.id()); // the member row is gone (the mapping row stays)

        for (StaffMapping m : new StaffMapping[] {nurse, staff}) {
            assertField(() -> f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id(),
                    new PharmacyInputs.ChangeMapping(m.version(), StoreRole.APPROVER, null, null)), "membershipId",
                    "Choose an active team member");
        }
        // nothing was raised to approver
        assertThat(list(ward, false).items()).extracting(StaffMapping::storeRole)
                .containsOnly(StoreRole.STORE_USER);
    }

    @Test
    void aMappingThatNeedsReviewIsNotShownAsTheHod() {
        map(ward, f.nurse, StoreRole.APPROVER);
        assertThat(list(ward, false).items().get(0).isHod()).isTrue();
        f.changeRole(f.nurse, "FRONT_DESK");

        StaffMapping m = list(ward, false).items().get(0);

        assertThat(m.needsReview()).isTrue();
        assertThat(m.storeRole()).isEqualTo(StoreRole.APPROVER);
        assertThat(m.isHod()).isFalse();
    }

    @Test
    void aDelegationOfExactly90DaysCountingBothEndsIsAccepted() {
        StaffMapping m = mapDelegate(ward, f.nurse, TODAY, TODAY.plusDays(89));
        assertThat(m.delegateTo()).isEqualTo(TODAY.plusDays(89));
    }

    @Test
    void aStaleVersionAnswersSettingsChangedWithTheCurrentMapping() {
        StaffMapping m = map(ward, f.nurse, StoreRole.STORE_USER);
        f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id(),
                new PharmacyInputs.ChangeMapping(m.version(), StoreRole.APPROVER, null, null));

        assertThatThrownBy(() -> f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id(),
                new PharmacyInputs.ChangeMapping(m.version(), StoreRole.STORE_USER, null, null)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("settings-changed");
                    assertThat(((StaffMapping) ex.extensions().get("current")).storeRole()).isEqualTo(StoreRole.APPROVER);
                });
    }

    @Test
    void aMappingOfAnotherStoreOrARemovedOneIs404() {
        StaffMapping m = map(ward, f.nurse, StoreRole.STORE_USER);
        assertThatThrownBy(() -> f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), pharmacy.id(), m.id(),
                new PharmacyInputs.ChangeMapping(m.version(), StoreRole.STORE_USER, null, null)))
                .isInstanceOf(NotFoundException.class);
        f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id());
        assertThatThrownBy(() -> f.staffService.change(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id(),
                new PharmacyInputs.ChangeMapping(m.version() + 1, StoreRole.STORE_USER, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), pharmacy.id(), m.id()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- remove and re-map ----

    @Test
    void removingKeepsTheRowAsHistoryAuditsWhoRemovedWhomAndIsSafeToRepeat() {
        StaffMapping m = map(ward, f.doctor, StoreRole.APPROVER);
        f.audit.clear();

        StaffMapping removed = f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id());

        assertThat(removed.status()).isEqualTo("REMOVED");
        assertThat(removed.isHod()).isFalse();
        assertThat(removed.access()).isEqualTo(com.sahaj.pharmacy.domain.StoreAccess.NONE);
        assertThat(f.auditActions()).containsExactly("PHARMACY_STAFF_REMOVED");
        assertThat(f.audit.get(0).after()).containsEntry("removedBy", f.owner.userId().toString());
        assertThat(list(ward, false).items()).isEmpty();
        assertThat(list(ward, true).items()).extracting(StaffMapping::status).containsExactly("REMOVED");
        // the store now has no approver
        assertThat(f.storeService.get(PharmacyFixture.TENANT, f.owner.userId(), ward.id()).approverState()).isEqualTo("NONE");

        f.audit.clear();
        f.writes.clear();
        assertThat(f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id()).status())
                .isEqualTo("REMOVED");
        assertThat(f.audit).isEmpty();
        assertThat(f.writes).isEmpty();
    }

    @Test
    void aReMapReactivatesTheSameRowWithTheNewRole() {
        StaffMapping m = map(ward, f.nurse, StoreRole.APPROVER);
        f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), m.id());

        StaffMapping again = map(ward, f.nurse, StoreRole.STORE_USER);

        assertThat(again.id()).isEqualTo(m.id());
        assertThat(again.status()).isEqualTo("ACTIVE");
        assertThat(again.storeRole()).isEqualTo(StoreRole.STORE_USER);
        assertThat(f.staffRows.values().stream().filter(h -> h.row().membershipId().equals(f.nurse.id()))).hasSize(1);
    }

    @Test
    void mappingToASwitchedOffStoreIsRefused() {
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), pharmacy.id());
        assertConflict(() -> map(pharmacy, f.nurse, StoreRole.STORE_USER), "store-inactive");
    }

    // ---- members who change ----

    @Test
    void aSwitchedOffMemberShowsCannotSignInAndHasNoAccess() {
        map(ward, f.nurse, StoreRole.STORE_USER);
        f.switchOff(f.nurse);

        StaffMapping m = list(ward, false).items().get(0);

        assertThat(m.memberState()).isEqualTo("CANNOT_SIGN_IN");
        assertThat(m.access()).isEqualTo(com.sahaj.pharmacy.domain.StoreAccess.NONE);
        assertThat(m.status()).isEqualTo("ACTIVE");
    }

    @Test
    void aMappingThatNoLongerFitsTheTeamRoleNeedsReviewAndCountsAsStoreUser() {
        map(ward, f.nurse, StoreRole.APPROVER);
        f.changeRole(f.nurse, "FRONT_DESK");

        StaffMapping m = list(ward, false).items().get(0);

        assertThat(m.needsReview()).isTrue();
        assertThat(m.storeRole()).isEqualTo(StoreRole.APPROVER);
        assertThat(m.effectiveStoreRole()).isEqualTo(StoreRole.STORE_USER);
        assertThat(m.access().approve()).isFalse();
    }

    // ---- candidates ----

    @Test
    void candidatesListActiveMembersWithTheStoreRolesEachMayTake() {
        f.switchOff(f.frontDesk);
        var items = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), null).items();

        assertThat(items).extracting(PharmacyViews.StaffCandidate::name).doesNotContain("Test Front Desk One");
        assertThat(items.get(0).role()).isEqualTo("OWNER");
        var staff = items.stream().filter(c -> c.role().equals("STORE_STAFF")).findFirst().orElseThrow();
        assertThat(staff.roleLabel()).isEqualTo("Store staff");
        assertThat(staff.allowedStoreRoles()).containsExactly(StoreRole.STORE_USER);
        var nurse = items.stream().filter(c -> c.role().equals("NURSE")).findFirst().orElseThrow();
        assertThat(nurse.allowedStoreRoles()).containsExactly(StoreRole.STORE_USER, StoreRole.APPROVER, StoreRole.DELEGATE);
    }

    @Test
    void centralStoreUserIsOfferedOnlyAtTheCentralStoreAndTheFilterNarrowsTheList() {
        var atCentral = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), central.id(), null).items();
        assertThat(atCentral.stream().filter(c -> c.role().equals("STORE_STAFF")).findFirst().orElseThrow()
                .allowedStoreRoles()).containsExactly(StoreRole.STORE_USER, StoreRole.CENTRAL_STORE_USER);

        var filtered = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), central.id(),
                StoreRole.APPROVER).items();
        assertThat(filtered).extracting(PharmacyViews.StaffCandidate::role)
                .containsExactlyInAnyOrder("OWNER", "PHARMACIST", "NURSE", "DOCTOR");
        assertThat(f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), ward.id(),
                StoreRole.CENTRAL_STORE_USER).items()).isEmpty();
    }

    @Test
    void candidatesShowWhoIsAlreadyMappedHereAndNeverAMobileNumber() {
        map(ward, f.nurse, StoreRole.STORE_USER);
        var items = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), ward.id(), null).items();
        assertThat(items.stream().filter(c -> c.membershipId().equals(f.nurse.id())).findFirst().orElseThrow()
                .alreadyMappedHere()).isTrue();
        assertThat(items.stream().filter(c -> c.membershipId().equals(f.doctor.id())).findFirst().orElseThrow()
                .alreadyMappedHere()).isFalse();
        assertThat(PharmacyViews.StaffCandidate.class.getRecordComponents()).extracting(c -> c.getName())
                .noneMatch(n -> n.toLowerCase().contains("mobile") || n.toLowerCase().contains("phone"));
        assertThatThrownBy(() -> f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- candidates without a store (contract 0.1.1, F-11) ----

    @Test
    void withoutAStoreTheCandidatesAreFilteredByTeamRoleOnlyAndNobodyIsMappedHere() {
        map(ward, f.nurse, StoreRole.STORE_USER);
        for (String none : new String[] {null, ""}) {
            var items = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), none, null).items();
            assertThat(items).extracting(PharmacyViews.StaffCandidate::alreadyMappedHere).containsOnly(false);
            var staff = items.stream().filter(c -> c.role().equals("STORE_STAFF")).findFirst().orElseThrow();
            // no store type is known, so the Central Store role is offered where the team role allows it
            assertThat(staff.allowedStoreRoles()).containsExactly(StoreRole.STORE_USER, StoreRole.CENTRAL_STORE_USER);
            var nurse = items.stream().filter(c -> c.role().equals("NURSE")).findFirst().orElseThrow();
            assertThat(nurse.allowedStoreRoles()).containsExactly(StoreRole.STORE_USER, StoreRole.CENTRAL_STORE_USER,
                    StoreRole.APPROVER, StoreRole.DELEGATE);
        }
    }

    @Test
    void withoutAStoreTheStoreRoleFilterNarrowsByTeamRoleForTheHodAndDelegateSelects() {
        var approvers = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), (String) null, "APPROVER")
                .items();
        assertThat(approvers).extracting(PharmacyViews.StaffCandidate::role)
                .containsExactlyInAnyOrder("OWNER", "PHARMACIST", "NURSE", "DOCTOR");
        var delegates = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), "", "DELEGATE").items();
        assertThat(delegates).extracting(PharmacyViews.StaffCandidate::role)
                .containsExactlyInAnyOrder("OWNER", "PHARMACIST", "NURSE", "DOCTOR");
        var central = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), (String) null,
                "CENTRAL_STORE_USER").items();
        assertThat(central).extracting(PharmacyViews.StaffCandidate::role).contains("STORE_STAFF", "OWNER");
    }

    @Test
    void withoutAStoreThereIsNeverA404ButTheGateStillComesFirst() {
        // unknown store: 404 only when a storeId is given
        assertThatThrownBy(() -> f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(),
                UUID.randomUUID().toString(), null)).isInstanceOf(NotFoundException.class);
        assertThat(f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), (String) null, null).items())
                .isNotEmpty();
        // module off 403 first, then the access level, then the bad value
        f.pharmacyOn = false;
        assertThatThrownBy(() -> f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), (String) null, null))
                .isInstanceOf(com.sahaj.platform.api.ModuleOffException.class);
        f.pharmacyOn = true;
        assertThatThrownBy(() -> f.staffService.candidates(PharmacyFixture.TENANT, f.nurse.userId(), (String) null,
                "APPROVER")).isInstanceOf(ForbiddenException.class);
        assertField(() -> f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), (String) null, "PHARMACIST"),
                "storeRole", "Choose a valid option");
        assertField(() -> f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), "nope", "PHARMACIST"),
                "storeId", "Choose a store");
    }

    // ---- access ----

    @Test
    void pharmacistAndCentralStoreUsersReadTheStaffButCannotChangeIt() {
        f.map(central, f.storeStaff, StoreRole.CENTRAL_STORE_USER);
        var staff = f.staffService.list(PharmacyFixture.TENANT, f.storeStaff.userId(), ward.id(), false);
        assertThat(staff.items()).isEmpty();
        f.staffService.list(PharmacyFixture.TENANT, f.pharmacist.userId(), ward.id(), false);

        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.pharmacist, f.storeStaff, f.nurse}) {
            assertThatThrownBy(() -> f.staffService.map(PharmacyFixture.TENANT, p.userId(), ward.id(),
                    new PharmacyInputs.MapStaff(f.doctor.id(), StoreRole.STORE_USER, null, null)))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.staffService.candidates(PharmacyFixture.TENANT, p.userId(), ward.id(), null))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    void anotherClinicsStoreIs404ForList() {
        UUID foreign = UUID.randomUUID();
        f.storeRows.put(foreign, new PharmacyFixture.Held<>(PharmacyFixture.OTHER_TENANT,
                new com.sahaj.pharmacy.domain.Store(foreign, UUID.randomUUID(), "Other", "OTH", StoreType.CENTRAL, null,
                        false, false, false, true, 0L)));
        assertThatThrownBy(() -> f.staffService.list(PharmacyFixture.TENANT, f.owner.userId(), foreign, false))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.staffService.map(PharmacyFixture.TENANT, f.owner.userId(), foreign,
                new PharmacyInputs.MapStaff(f.nurse.id(), StoreRole.STORE_USER, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- contract order: module off, access level, then path ids and query values, then the store ----

    private static void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
            assertThat(ex.status()).isEqualTo(400);
            assertThat(ex.slug()).isEqualTo("bad-request");
            assertThat(ex.getMessage()).doesNotContain("not-a-uuid");
        });
    }

    @Test
    void aMalformedIdOrMissingQueryValueIsReadAfterTheModuleAndAccessChecks() {
        UUID tenant = PharmacyFixture.TENANT;
        UUID owner = f.owner.userId();
        String id = ward.id().toString();
        PharmacyInputs.MapStaff mapBody = new PharmacyInputs.MapStaff(f.nurse.id(), StoreRole.STORE_USER, null, null);
        PharmacyInputs.ChangeMapping changeBody = new PharmacyInputs.ChangeMapping(0L, StoreRole.STORE_USER, null, null);

        f.pharmacyOn = false;
        assertThatThrownBy(() -> f.staffService.candidates(tenant, owner, (String) null, (String) null))
                .isInstanceOf(com.sahaj.platform.api.ModuleOffException.class);
        assertThatThrownBy(() -> f.staffService.list(tenant, owner, "not-a-uuid", "maybe"))
                .isInstanceOf(com.sahaj.platform.api.ModuleOffException.class);
        f.pharmacyOn = true;

        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.nurse, f.pharmacist, f.storeStaff}) {
            assertThatThrownBy(() -> f.staffService.candidates(tenant, p.userId(), (String) null, (String) null))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.staffService.candidates(tenant, p.userId(), "nope", "PHARMACIST"))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.staffService.map(tenant, p.userId(), "not-a-uuid", mapBody))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.staffService.change(tenant, p.userId(), "not-a-uuid", "not-a-uuid", changeBody))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> f.staffService.remove(tenant, p.userId(), "not-a-uuid", "not-a-uuid"))
                    .isInstanceOf(ForbiddenException.class);
        }
        assertThatThrownBy(() -> f.staffService.list(tenant, f.nurse.userId(), "not-a-uuid", "maybe"))
                .isInstanceOf(ForbiddenException.class);

        assertBadRequest(() -> f.staffService.list(tenant, owner, "not-a-uuid", null));
        assertBadRequest(() -> f.staffService.map(tenant, owner, "not-a-uuid", mapBody));
        assertBadRequest(() -> f.staffService.change(tenant, owner, id, "not-a-uuid", changeBody));
        assertBadRequest(() -> f.staffService.change(tenant, owner, "not-a-uuid", id, changeBody));
        assertBadRequest(() -> f.staffService.remove(tenant, owner, id, "not-a-uuid"));
        assertField(() -> f.staffService.list(tenant, owner, id, "maybe"), "includeRemoved", "Choose a valid option");
        assertField(() -> f.staffService.candidates(tenant, owner, "not-a-uuid", null), "storeId", "Choose a store");
        assertField(() -> f.staffService.candidates(tenant, owner, "  ", null), "storeId", "Choose a store");
        assertField(() -> f.staffService.candidates(tenant, owner, id, "PHARMACIST"), "storeRole", "Choose a valid option");
    }

    @Test
    void wellFormedTextValuesWorkLikeTheTypedOnes() {
        StaffMapping mapped = f.staffService.map(PharmacyFixture.TENANT, f.owner.userId(), ward.id().toString(),
                new PharmacyInputs.MapStaff(f.nurse.id(), StoreRole.STORE_USER, null, null));
        assertThat(f.staffService.list(PharmacyFixture.TENANT, f.owner.userId(), ward.id().toString(), "false").items())
                .extracting(StaffMapping::id).containsExactly(mapped.id());
        assertThat(f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), ward.id().toString(),
                "STORE_USER").items()).isNotEmpty();
        StaffMapping removed = f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), ward.id().toString(),
                mapped.id().toString());
        assertThat(removed.status()).isEqualTo("REMOVED");
    }
}
