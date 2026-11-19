package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyMasterInputs.ReasonAdd;
import com.sahaj.pharmacy.application.PharmacyMasterViews.Reason;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-78-10, reason lists (eleven lists in 0.2.0; the seeded rows of the seven new lists come with the SAH-79 migration). */
class ReasonServiceTest {

    private static final UUID TENANT = PharmacyFixture.TENANT;

    private final MasterFixture m = new MasterFixture();
    private final PharmacyFixture f = m.f;

    private PharmacyMasterViews.ReasonListView list(PharmacyMasterViews.ReasonLists lists, String key) {
        return lists.lists().stream().filter(l -> l.listKey().equals(key)).findFirst().orElseThrow();
    }

    private PharmacyMasterViews.ReasonLists add(String listKey, String label) {
        return m.reasonService.add(TENANT, f.owner.userId(), listKey, new ReasonAdd(label));
    }

    private static List<String> labels(PharmacyMasterViews.ReasonListView list) {
        return list.reasons().stream().map(Reason::label).toList();
    }

    @Test
    void elevenListsInScreenOrderWithTheSeededReasonsFirstAndNothingWritten() {
        PharmacyMasterViews.ReasonLists lists = m.reasonService.list(TENANT, f.pharmacist.userId());

        assertThat(lists.lists()).extracting(PharmacyMasterViews.ReasonListView::listKey).containsExactly("store-return",
                "sales-return", "damage", "stock-adjustment", "receipt-reject", "cs-change", "approval-reject", "transfer",
                "consumption-purpose", "dispute", "vendor-return");
        assertThat(list(lists, "store-return").label()).isEqualTo("Store return");
        assertThat(labels(list(lists, "store-return")))
                .containsExactly("Excess stock", "Near expiry", "Not needed (order stopped)");
        assertThat(labels(list(lists, "sales-return"))).containsExactly("Doctor changed medicine", "Excess bought",
                "Wrong item given", "Adverse reaction");
        assertThat(labels(list(lists, "damage"))).containsExactly("Damaged / leaking", "Expired", "Cold-chain break");
        assertThat(list(lists, "damage").reasons()).allSatisfy(r -> {
            assertThat(r.seeded()).isTrue();
            assertThat(r.active()).isTrue();
        });
        assertThat(list(lists, "dispute").reasons()).isEmpty();
        assertThat(m.writes).isEmpty();
    }

    @Test
    void anAddedReasonIsAChipAtTheEndOfItsListWithAnOwnKey() {
        PharmacyMasterViews.ReasonLists lists = add("damage", "  Packaging torn ");

        List<Reason> reasons = list(lists, "damage").reasons();
        assertThat(labels(list(lists, "damage"))).containsExactly("Damaged / leaking", "Expired", "Cold-chain break",
                "Packaging torn");
        Reason added = reasons.get(3);
        assertThat(added.seeded()).isFalse();
        assertThat(added.active()).isTrue();
        assertThat(added.reasonKey()).matches("^c-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(f.auditActions()).contains("PHARMACY_REASON_ADDED");
        assertThat(labels(list(lists, "sales-return"))).doesNotContain("Packaging torn");
    }

    @Test
    void aLabelAlreadyInTheListIsRefusedIgnoringCaseWhetherSeededOrOwnAndWhateverItsState() {
        add("damage", "Packaging torn");

        assertThatThrownBy(() -> add("damage", "expired")).isInstanceOfSatisfying(ConflictException.class, e -> {
            assertThat(e.problemType()).isEqualTo("reason-exists");
            assertThat(e.getMessage()).isEqualTo("That reason is already in the list");
        });
        assertThatThrownBy(() -> add("damage", "PACKAGING TORN")).isInstanceOf(ConflictException.class);
        assertThat(labels(list(add("store-return", "Expired"), "store-return"))).contains("Expired");
    }

    @Test
    void aBlankOrTooLongLabelIsAValidationError() {
        for (String bad : new String[] {null, "   ", "x".repeat(41)}) {
            assertThatThrownBy(() -> add("damage", bad)).isInstanceOfSatisfying(InvalidFieldsException.class,
                    e -> assertThat(e.errors()).containsExactly(
                            new FieldMessage("label", "Enter the reason (up to 40 characters)")));
        }
        assertThat(add("damage", "x".repeat(40))).isNotNull();
    }

    @Test
    void anUnknownListIs400AndTheBodyIsNotReadFirst() {
        PharmacyInputs.Body<ReasonAdd> unreadable = () -> {
            throw new IllegalStateException("the body must not be read before the path");
        };

        assertThatThrownBy(() -> m.reasonService.add(TENANT, f.owner.userId(), "nope", unreadable))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("bad-request"));
        assertThatThrownBy(() -> m.reasonService.switchOff(TENANT, f.owner.userId(), "nope", "x"))
                .isInstanceOf(PharmacyProblemException.class);
    }

    @Test
    void aSeededReasonCanBeSwitchedOffAndOnAgainAndRepeatingWritesNothing() {
        PharmacyMasterViews.ReasonLists off = m.reasonService.switchOff(TENANT, f.owner.userId(), "store-return",
                "store-return.near-expiry");

        assertThat(list(off, "store-return").reasons().get(1).active()).isFalse();
        assertThat(f.auditActions()).contains("PHARMACY_REASON_SWITCHED_OFF");
        m.writes.clear();
        m.reasonService.switchOff(TENANT, f.owner.userId(), "store-return", "store-return.near-expiry");
        assertThat(m.writes).isEmpty();

        PharmacyMasterViews.ReasonLists on = m.reasonService.switchOn(TENANT, f.owner.userId(), "store-return",
                "store-return.near-expiry");
        assertThat(list(on, "store-return").reasons().get(1).active()).isTrue();
        assertThat(f.auditActions()).contains("PHARMACY_REASON_SWITCHED_ON");
        m.writes.clear();
        m.reasonService.switchOn(TENANT, f.owner.userId(), "store-return", "store-return.near-expiry");
        assertThat(m.writes).isEmpty();
    }

    @Test
    void anOwnReasonCanBeSwitchedOffAndOn() {
        PharmacyMasterViews.ReasonLists lists = add("sales-return", "Price mismatch");
        String key = list(lists, "sales-return").reasons().get(4).reasonKey();

        PharmacyMasterViews.ReasonLists off = m.reasonService.switchOff(TENANT, f.owner.userId(), "sales-return", key);
        assertThat(list(off, "sales-return").reasons().get(4).active()).isFalse();
        assertThat(list(m.reasonService.switchOn(TENANT, f.owner.userId(), "sales-return", key), "sales-return")
                .reasons().get(4).active()).isTrue();
    }

    @Test
    void theLastActiveDamageReasonCannotBeSwitchedOffButTheLastOfAnotherListCan() {
        m.reasonService.switchOff(TENANT, f.owner.userId(), "damage", "damage.damaged-leaking");
        m.reasonService.switchOff(TENANT, f.owner.userId(), "damage", "damage.expired");

        assertThatThrownBy(() -> m.reasonService.switchOff(TENANT, f.owner.userId(), "damage", "damage.cold-chain-break"))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("last-active-reason");
                    assertThat(e.getMessage()).isEqualTo("Keep at least one damage reason.");
                });

        m.reasonService.switchOff(TENANT, f.owner.userId(), "stock-adjustment", "stock-adjustment.breakage");
        m.reasonService.switchOff(TENANT, f.owner.userId(), "stock-adjustment", "stock-adjustment.unknown");
        assertThat(m.reasonService.switchOff(TENANT, f.owner.userId(), "stock-adjustment",
                "stock-adjustment.consumption-not-entered")).isNotNull();
    }

    @Test
    void anAddedDamageReasonCountsAsTheActiveOneThatKeepsTheListOpen() {
        PharmacyMasterViews.ReasonLists lists = add("damage", "Packaging torn");
        m.reasonService.switchOff(TENANT, f.owner.userId(), "damage", "damage.damaged-leaking");
        m.reasonService.switchOff(TENANT, f.owner.userId(), "damage", "damage.expired");
        m.reasonService.switchOff(TENANT, f.owner.userId(), "damage", "damage.cold-chain-break");

        assertThat(list(m.reasonService.list(TENANT, f.owner.userId()), "damage").reasons())
                .filteredOn(Reason::active).extracting(Reason::label).containsExactly("Packaging torn");
        assertThat(lists).isNotNull();
    }

    @Test
    void theFixedReceiptReasonCannotBeSwitchedOff() {
        m.seed("receipt-reject", "damaged-in-transit", "Damaged in transit", "short", "Short / not received");

        assertThatThrownBy(() -> m.reasonService.switchOff(TENANT, f.owner.userId(), "receipt-reject",
                "receipt-reject.short")).isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("last-active-reason");
                    assertThat(e.getMessage()).isEqualTo("This reason is fixed.");
                });
        assertThat(m.reasonService.switchOff(TENANT, f.owner.userId(), "receipt-reject",
                "receipt-reject.damaged-in-transit")).isNotNull();
    }

    @Test
    void onlyTheFixedReasonsCarryFixedTrueAndTheReturnConditionKeysAreFixedToo() {
        m.seed("receipt-reject", "damaged-in-transit", "Damaged in transit", "short", "Short / not received");

        PharmacyMasterViews.ReasonListView receipt = list(m.reasonService.list(TENANT, f.owner.userId()), "receipt-reject");
        assertThat(receipt.reasons()).filteredOn(Reason::fixed).extracting(Reason::reasonKey)
                .containsExactly("receipt-reject.short");
        assertThat(list(m.reasonService.list(TENANT, f.owner.userId()), "damage").reasons())
                .noneMatch(Reason::fixed);
        assertThat(add("damage", "Packaging torn").lists().stream().flatMap(l -> l.reasons().stream())
                .filter(r -> r.reasonKey().startsWith("c-"))).noneMatch(Reason::fixed);
        // DEC-079 O-13: the return-condition keys cannot be switched off either
        assertThat(com.sahaj.pharmacy.domain.ReasonList.isFixed("return-condition.sealed")).isTrue();
        assertThat(com.sahaj.pharmacy.domain.ReasonList.isFixed("return-condition.opened")).isTrue();
        assertThat(com.sahaj.pharmacy.domain.ReasonList.isFixed("return-condition.damaged")).isTrue();
        assertThat(com.sahaj.pharmacy.domain.ReasonList.isFixed("receipt-reject.short")).isTrue();
        assertThat(com.sahaj.pharmacy.domain.ReasonList.isFixed("receipt-reject.wrong-item")).isFalse();
        assertThat(com.sahaj.pharmacy.domain.ReasonList.isFixed(null)).isFalse();
    }

    @Test
    void anUnknownReasonKeyInThatListIs404() {
        assertThatThrownBy(() -> m.reasonService.switchOff(TENANT, f.owner.userId(), "damage", "store-return.near-expiry"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> m.reasonService.switchOn(TENANT, f.owner.userId(), "damage", "c-unknown"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPharmacistReadsAndNobodyButTheOwnerWrites() {
        assertThat(m.reasonService.list(TENANT, f.pharmacist.userId()).lists()).hasSize(11);
        assertThatThrownBy(() -> m.reasonService.add(TENANT, f.pharmacist.userId(), "damage", new ReasonAdd("Torn")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> m.reasonService.switchOff(TENANT, f.pharmacist.userId(), "damage", "damage.expired"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> m.reasonService.list(TENANT, f.nurse.userId())).isInstanceOf(ForbiddenException.class);
    }
}
