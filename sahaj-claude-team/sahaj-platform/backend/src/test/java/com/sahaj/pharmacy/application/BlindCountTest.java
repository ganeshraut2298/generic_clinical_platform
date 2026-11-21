package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.pharmacy.domain.StoreType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Rule 24 (DEC-080 L12, BR-30): a caller who can count at a store but cannot review the count is blind there, for the products on
 * the store's open audit only; the owner, the HOD and the current delegate never are, and another store's quantities are not hidden.
 */
class BlindCountTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID OTHER_STORE = UUID.randomUUID();
    private static final UUID AUDITED = UUID.randomUUID();
    private static final UUID NOT_AUDITED = UUID.randomUUID();

    private final StockAuditRepository audits = mock(StockAuditRepository.class);
    private final BlindCount blind = new BlindCount(audits);

    private static PharmacyCaller caller(String teamRole, StoreRole role) {
        Store store = new Store(STORE, UUID.randomUUID(), "Test Ward 3B", "W3B", StoreType.WARD, null, false, true, true, true, 0);
        List<PharmacyCaller.Mapping> mappings = role == null ? List.of()
                : List.of(new PharmacyCaller.Mapping(store, null, role, StoreRules.access(teamRole, role, false)));
        return new PharmacyCaller(TENANT, UUID.randomUUID(), UUID.randomUUID(), "Test Person", teamRole, mappings);
    }

    private void openAudit() {
        when(audits.openAuditProducts(TENANT, STORE)).thenReturn(Set.of(AUDITED));
    }

    @Test
    void aStoreUserAndACentralStoreUserAreBlindButTheOwnerTheHodAndTheDelegateAreNot() {
        assertThat(BlindCount.blindAt(caller("STORE_STAFF", StoreRole.STORE_USER), STORE)).isTrue();
        assertThat(BlindCount.blindAt(caller("NURSE", StoreRole.STORE_USER), STORE)).isTrue();
        assertThat(BlindCount.blindAt(caller("STORE_STAFF", StoreRole.CENTRAL_STORE_USER), STORE)).isTrue();
        assertThat(BlindCount.blindAt(caller("OWNER", null), STORE)).isFalse();
        assertThat(BlindCount.blindAt(caller("NURSE", StoreRole.APPROVER), STORE)).isFalse();
        assertThat(BlindCount.blindAt(caller("NURSE", StoreRole.DELEGATE), STORE)).isFalse();
    }

    @Test
    void aCallerWithNoAccessToTheStoreIsNotBlindThereAndAnotherStoresStockIsNeverHidden() {
        PharmacyCaller user = caller("STORE_STAFF", StoreRole.STORE_USER);

        assertThat(BlindCount.blindAt(user, OTHER_STORE)).as("not mapped there").isFalse();
        assertThat(blind.at(user, OTHER_STORE).active()).isFalse();
        verify(audits, never()).openAuditProducts(TENANT, OTHER_STORE);
    }

    @Test
    void theAuditedProductsOfTheOpenAuditAreHiddenFromABlindCallerOnly() {
        openAudit();
        PharmacyCaller user = caller("STORE_STAFF", StoreRole.STORE_USER);

        BlindCount.Mask mask = blind.at(user, STORE);

        assertThat(mask.active()).isTrue();
        assertThat(mask.hides(AUDITED)).isTrue();
        assertThat(mask.hides(NOT_AUDITED)).isFalse();
        assertThat(mask.qty(AUDITED, 12)).isNull();
        assertThat(mask.qty(NOT_AUDITED, 12)).isEqualTo(12);
        assertThat(blind.hides(caller("NURSE", StoreRole.APPROVER), STORE, AUDITED)).isFalse();
    }

    @Test
    void withNoOpenAuditNothingIsHiddenAndTheReviewerPaysNoQuery() {
        PharmacyCaller user = caller("STORE_STAFF", StoreRole.STORE_USER);

        assertThat(blind.at(user, STORE).active()).isFalse();
        assertThat(blind.available(user, STORE, AUDITED, 5)).isEqualTo(5);

        org.mockito.Mockito.clearInvocations(audits);
        blind.at(caller("OWNER", null), STORE);
        verify(audits, never()).openAuditProducts(TENANT, STORE);
    }

    @Test
    void anInsufficientStockProblemKeepsItsLinesButLosesTheNumbersOfAuditedProductsForABlindCaller() {
        openAudit();
        PharmacyCaller user = caller("STORE_STAFF", StoreRole.STORE_USER);
        PharmacyProblemException refusal = PharmacyProblemException.insufficientStock(3, List.of(
                new FailingLine(null, 1, AUDITED, 3), new FailingLine(null, 2, NOT_AUDITED, 9)));

        RuntimeException answered = blind.scrub(user, STORE, refusal);

        assertThat(answered).isInstanceOf(PharmacyProblemException.class);
        PharmacyProblemException scrubbed = (PharmacyProblemException) answered;
        assertThat(scrubbed.slug()).isEqualTo("insufficient-stock");
        assertThat(scrubbed.extensions()).containsEntry("available", null);
        @SuppressWarnings("unchecked")
        List<FailingLine> lines = (List<FailingLine>) scrubbed.extensions().get("lines");
        assertThat(lines).extracting(FailingLine::available).containsExactly(null, 9);
        assertThat(lines).extracting(FailingLine::productId).containsExactly(AUDITED, NOT_AUDITED);
    }

    @Test
    void aProblemIsLeftAloneForAReviewerAndWhenItIsNotAnInsufficientStockProblem() {
        openAudit();
        PharmacyProblemException refusal = PharmacyProblemException.insufficientStock(3, List.of(
                new FailingLine(null, 1, AUDITED, 3)));

        assertThat(blind.scrub(caller("NURSE", StoreRole.APPROVER), STORE, refusal)).isSameAs(refusal);
        PharmacyProblemException other = PharmacyProblemException.batchExpired();
        assertThat(blind.scrub(caller("STORE_STAFF", StoreRole.STORE_USER), STORE, other)).isSameAs(other);
        IllegalStateException unrelated = new IllegalStateException("x");
        assertThat(blind.scrub(caller("STORE_STAFF", StoreRole.STORE_USER), STORE, unrelated)).isSameAs(unrelated);
    }

    @Test
    void theChangedProblemOfABlindCallerCarriesNoCurrentValue() {
        assertThat(PharmacyProblemException.changed(null).extensions()).doesNotContainKey("current");
        assertThat(PharmacyProblemException.changed("now").extensions()).containsEntry("current", "now");
    }

    @Test
    void theBlindQuantityMessageHasNoNumber() {
        assertThat(BlindCount.MORE_THAN_IN_STOCK).isEqualTo("This is more than the quantity in stock.").doesNotContainPattern("\\d");
    }
}
