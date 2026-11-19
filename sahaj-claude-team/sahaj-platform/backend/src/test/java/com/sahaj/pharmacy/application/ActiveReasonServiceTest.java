package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.ReasonRepository.OwnRow;
import com.sahaj.pharmacy.application.ReasonRepository.SeededRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-79 common (C-5): GET /reasons answers the active reasons of the named lists, seeded first, never a switched-off one. */
class ActiveReasonServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final ReasonRepository repo = mock(ReasonRepository.class);
    private final ActiveReasonService service = new ActiveReasonService(gate, repo);

    private void seed() {
        when(repo.seeded(TENANT)).thenReturn(List.of(
                new SeededRow("stock-adjustment.count-mismatch", "stock-adjustment", "Count mismatch", 1, true),
                new SeededRow("stock-adjustment.found", "stock-adjustment", "Found stock", 2, false),
                new SeededRow("stock-adjustment.other", "stock-adjustment", "Other", 3, true),
                new SeededRow("receipt-reject.short", "receipt-reject", "Short / not received", 1, true)));
        when(repo.own(TENANT)).thenReturn(List.of(
                new OwnRow(UUID.randomUUID(), "stock-adjustment", "stock-adjustment.breakage", "Breakage", true, 4),
                new OwnRow(UUID.randomUUID(), "stock-adjustment", "stock-adjustment.old", "Old reason", false, 5)));
    }

    @Test
    void onlyActiveReasonsAreListedSeededFirstThenTheUnitsOwnAndEveryListIsAnswered() {
        seed();

        StockViews.ActiveReasons answer = service.list(TENANT, USER, "stock-adjustment,receipt-reject");

        assertThat(answer.lists()).extracting(StockViews.ActiveReasonList::listKey).containsExactly("stock-adjustment", "receipt-reject");
        assertThat(answer.lists().get(0).reasons()).extracting(StockViews.ActiveReason::reasonKey).containsExactly(
                "stock-adjustment.count-mismatch", "stock-adjustment.other", "stock-adjustment.breakage");
    }

    @Test
    void aKeyEndingInOtherNeedsANoteAndTheShortReceiptReasonIsFixed() {
        seed();

        StockViews.ActiveReasons answer = service.list(TENANT, USER, "stock-adjustment,receipt-reject");

        assertThat(answer.lists().get(0).reasons()).filteredOn(r -> r.reasonKey().endsWith(".other"))
                .singleElement().satisfies(r -> assertThat(r.needsNote()).isTrue());
        assertThat(answer.lists().get(0).reasons()).filteredOn(r -> !r.reasonKey().endsWith(".other"))
                .allSatisfy(r -> assertThat(r.needsNote()).isFalse());
        assertThat(answer.lists().get(1).reasons().get(0).fixed()).isTrue();
    }

    @Test
    void theGateRunsFirstAtThePharmacyAccessLevel() {
        seed();
        service.list(TENANT, USER, "stock-adjustment");

        org.mockito.Mockito.verify(gate).require(TENANT, USER, AccessLevel.PHARMACY_ACCESS);
    }

    @Test
    void noListAnUnknownListOrMoreThanElevenListsIs400() {
        for (String bad : new String[] {null, "", "  ", "nonsense", "stock-adjustment,nonsense", "sales-return-x",
                "a,b,c,d,e,f,g,h,i,j,k,l"}) {
            assertThatThrownBy(() -> service.list(TENANT, USER, bad)).as(String.valueOf(bad))
                    .isInstanceOf(InvalidFieldsException.class);
        }
    }

    @Test
    void aRepeatedListIsReadOnceAndTheActiveCheckKnowsSeededAndOwnReasons() {
        seed();

        assertThat(service.list(TENANT, USER, "stock-adjustment,stock-adjustment").lists()).hasSize(1);
        assertThat(service.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, "stock-adjustment.count-mismatch")).isTrue();
        assertThat(service.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, "stock-adjustment.breakage")).isTrue();
        assertThat(service.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, "stock-adjustment.found")).as("switched off").isFalse();
        assertThat(service.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, "stock-adjustment.old")).isFalse();
        assertThat(service.isActive(TENANT, ReasonList.DAMAGE, "stock-adjustment.count-mismatch")).as("another list").isFalse();
        assertThat(service.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, null)).isFalse();
        assertThat(service.labels(TENANT)).containsEntry("stock-adjustment.found", "Found stock");
        org.mockito.Mockito.verify(gate, org.mockito.Mockito.atLeastOnce()).require(any(), any(), any());
    }
}
