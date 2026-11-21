package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-7: exact substitutes for a pharmacist only; same salt, strength, form and schedule; stock in this store. */
class SubstituteServiceTest {

    private static final LocalDate FAR = LocalDate.of(2027, 6, 30);

    private final SaleWorld w = new SaleWorld();
    private final DispatchRepository dispatches = mock(DispatchRepository.class);
    private final SubstituteService service = new SubstituteService(w.f.gate, w.f.guard, w.products, w.stock, w.posting,
            dispatches, w.blind, w.f.time);
    private final String shop = w.shop.id().toString();
    private final UUID wanted = w.product("Testamol", "Paracetamol", "NONE");
    private final UUID same = w.product("Testacet", "Paracetamol", "NONE");
    private final UUID cheaper = w.product("Testfebrin", "Paracetamol", "NONE");
    private final UUID otherSalt = w.product("Testprofen", "Ibuprofen", "NONE");
    private final UUID noStock = w.product("Testpyrin", "Paracetamol", "NONE");

    SubstituteServiceTest() {
        w.shelf(same, "S-1", FAR, 30, "40.00");
        w.shelf(cheaper, "C-1", FAR, 10, "30.00");
        w.shelf(otherSalt, "O-1", FAR, 10, "20.00");
        w.shelf(noStock, "N-1", LocalDate.of(2026, 1, 31), 10, "10.00"); // expired
        when(dispatches.replacementCandidates(any(), eq(wanted), anyInt())).thenReturn(List.of(same, cheaper, otherSalt, noStock));
    }

    @Test
    void aCashierAndTheOwnerAreToldAPharmacistMustSubstitute() {
        assertThatThrownBy(() -> service.options(SaleWorld.TENANT, w.cashier.userId(), shop, wanted.toString()))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.slug()).isEqualTo("pharmacist-required");
                    assertThat(e.getMessage()).isEqualTo("A pharmacist must substitute.");
                });
        assertThatThrownBy(() -> service.options(SaleWorld.TENANT, w.f.owner.userId(), shop, wanted.toString()))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("pharmacist-required"));
    }

    @Test
    void onlyExactEquivalentsInStockAreOfferedCheapestFirst() {
        SaleCheckViews.SubstituteOptions out = service.options(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, wanted.toString());

        assertThat(out.items()).extracting(o -> o.product().productId()).containsExactly(cheaper, same);
        assertThat(out.items().get(0).mrp()).isEqualTo("30.00");
        assertThat(out.items().get(0).availableQty()).isEqualTo(10);
        assertThat(out.items().get(0).checks().salt()).isTrue();
        assertThat(out.items().get(0).marginPerUnit()).isEqualTo("29.00"); // MRP less the landed rate of 1.00
        assertThat(out.outOfStock()).isTrue();
        assertThat(out.sentence()).isEqualTo("Testamol is out of stock. Only exact equivalents are offered.");
    }

    @Test
    void aBlindCallerSeesTheOptionButNotItsStock() {
        when(w.audits.openAuditProducts(any(), any())).thenReturn(Set.of(cheaper));
        // the pharmacist is blind at a store with an open audit of the product unless HOD
        SaleCheckViews.SubstituteOptions out = service.options(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, wanted.toString());

        assertThat(out.items()).hasSize(2);
        assertThat(out.items().stream().filter(o -> o.product().productId().equals(cheaper)).findFirst().orElseThrow()
                .availableQty()).isNull();
    }

    @Test
    void whenTheProductHasStockTheSentenceIsTheShortOne() {
        w.shelf(wanted, "W-1", FAR, 5, "50.00");
        SaleCheckViews.SubstituteOptions out = service.options(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, wanted.toString());

        assertThat(out.outOfStock()).isFalse();
        assertThat(out.sentence()).isEqualTo("Only exact equivalents are offered.");
    }

    @Test
    void anUnknownProductIsNotFound() {
        assertThatThrownBy(() -> service.options(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, UUID.randomUUID().toString()))
                .isInstanceOf(com.sahaj.platform.api.NotFoundException.class);
    }
}
