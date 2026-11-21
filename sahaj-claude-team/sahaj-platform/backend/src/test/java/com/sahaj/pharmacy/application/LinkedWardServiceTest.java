package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-79-16: the IPD wards and the linked ward of a ward store (owner only, no-op reader in phase 1). */
class LinkedWardServiceTest {

    private static final UUID WARD_ID = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    private final PharmacyFixture f = new PharmacyFixture();

    private AdmittedPatientsReader readerWith(List<AdmittedPatientsReader.Ward> wards) {
        return new NoOpAdmittedPatientsReader() {
            @Override
            public List<Ward> wards(UUID tenantId) {
                return wards;
            }
        };
    }

    private LinkedWardService service(AdmittedPatientsReader reader) {
        StoreAssembler assembler = new StoreAssembler(f.storeRepository, f.staffRepository, f.team, f.time, reader);
        return new LinkedWardService(f.gate, f.unitRepository, f.storeRepository, assembler, reader, f.auditLog);
    }

    private PharmacyViews.Store ward() {
        f.createCentral();
        return f.createStore("Test Ward 3B Store", "W3B", StoreType.WARD);
    }

    private static PharmacyInputs.Body<PharmacyInputs.LinkedWard> body(long version, UUID wardId) {
        return PharmacyInputs.Body.of(new PharmacyInputs.LinkedWard(version, PharmacyInputs.Sent.of(wardId)));
    }

    @Test
    void thePhaseOneReaderAnswersIntegrationOffForTheListAndForALink() {
        LinkedWardService noop = service(new NoOpAdmittedPatientsReader());
        PharmacyViews.Store store = ward();

        assertThatThrownBy(() -> noop.wards(PharmacyFixture.TENANT, f.owner.userId())).isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> noop.setLinkedWard(PharmacyFixture.TENANT, f.owner.userId(), store.id().toString(),
                body(store.version(), WARD_ID))).isInstanceOf(IntegrationOffException.class);
        assertThat(f.writes).doesNotContain("store.linkedWard");
    }

    @Test
    void clearingALinkIsAlwaysAllowedAndTheStoreShowsTheLinkedWard() {
        LinkedWardService svc = service(readerWith(List.of(new AdmittedPatientsReader.Ward(WARD_ID, "Ward 3B"))));
        PharmacyViews.Store store = ward();

        PharmacyViews.Store linked = svc.setLinkedWard(PharmacyFixture.TENANT, f.owner.userId(), store.id().toString(),
                body(store.version(), WARD_ID));
        assertThat(linked.linkedWard()).isEqualTo(new PharmacyViews.WardRef(WARD_ID, "Ward 3B"));
        assertThat(linked.version()).isEqualTo(store.version() + 1);
        assertThat(f.auditActions()).contains("PHARMACY_STORE_LINKED_WARD_CHANGED");

        LinkedWardService off = service(new NoOpAdmittedPatientsReader());
        PharmacyViews.Store cleared = off.setLinkedWard(PharmacyFixture.TENANT, f.owner.userId(), store.id().toString(),
                body(linked.version(), null));
        assertThat(cleared.linkedWard()).isNull();
    }

    @Test
    void onlyAWardStoreAndOnlyAKnownWardAndTheCurrentVersion() {
        LinkedWardService svc = service(readerWith(List.of(new AdmittedPatientsReader.Ward(WARD_ID, "Ward 3B"))));
        PharmacyViews.Store store = ward();
        PharmacyViews.Store pharmacy = f.createStore("Test Main Pharmacy", "PHARM", StoreType.PHARMACY);

        assertThatThrownBy(() -> svc.setLinkedWard(PharmacyFixture.TENANT, f.owner.userId(), pharmacy.id().toString(),
                body(pharmacy.version(), WARD_ID))).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors().get(0).message()).isEqualTo(LinkedWardService.ONLY_WARD));
        assertThatThrownBy(() -> svc.setLinkedWard(PharmacyFixture.TENANT, f.owner.userId(), store.id().toString(),
                body(store.version(), UUID.randomUUID()))).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors().get(0).message()).isEqualTo(LinkedWardService.CHOOSE_WARD));
        assertThatThrownBy(() -> svc.setLinkedWard(PharmacyFixture.TENANT, f.owner.userId(), store.id().toString(),
                body(store.version() + 5, WARD_ID))).isInstanceOf(PharmacyProblemException.class);
    }

    @Test
    void aMissingWardIdIsRefusedAndOnlyTheOwnerMayCall() {
        LinkedWardService svc = service(new NoOpAdmittedPatientsReader());
        PharmacyViews.Store store = ward();

        assertThatThrownBy(() -> svc.setLinkedWard(PharmacyFixture.TENANT, f.owner.userId(), store.id().toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.LinkedWard(store.version(), PharmacyInputs.Sent.absent()))))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> svc.wards(PharmacyFixture.TENANT, f.pharmacist.userId())).isInstanceOf(ForbiddenException.class);
    }
}
