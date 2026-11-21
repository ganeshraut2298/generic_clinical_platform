package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-80-21: who reaches the Sales & compliance reports, and what the six keys look like. */
class SalesReportAccessTest {

    private final SaleWorld w = new SaleWorld();

    private List<Store> owned() {
        return w.f.storeRepository.list(SaleWorld.TENANT, false);
    }

    @Test
    void theOwnerReachesTheSellingStoresOnlyAndAPharmacistTheirOwn() {
        assertThat(ReportService.reach(w.caller(w.f.owner), ReportDef.Tier.SALES, owned())).extracting(Store::id)
                .containsExactly(w.shop.id());
        assertThat(ReportService.reach(w.caller(w.f.pharmacist), ReportDef.Tier.SALES, List.of())).extracting(Store::id)
                .containsExactly(w.shop.id());
    }

    @Test
    void aCashierReachesNoSalesReportAndSoNoSalesGroupInTheCatalogue() {
        assertThat(ReportService.reach(w.caller(w.cashier), ReportDef.Tier.SALES, List.of())).isEmpty();
    }

    @Test
    void aStoreUserWhoIsNotAPharmacistReachesNoSalesReport() {
        PharmacyFixture.Person user = w.f.person("STORE_STAFF", "Test Store User", true);
        w.f.map(w.shop, user, StoreRole.STORE_USER);

        assertThat(ReportService.reach(w.caller(user), ReportDef.Tier.SALES, List.of())).isEmpty();
    }

    @Test
    void theSixKeysAreInTheSalesAndComplianceGroupAndEveryColumnKeyIsUnique() {
        List<String> keys = List.of("sales-margin", "sales-returns", "gst-sales-register", "h1-register", "schedule-x-register",
                "activity-log");
        for (String key : keys) {
            ReportDef def = ReportDef.byKey(key).orElseThrow();
            assertThat(def.group()).isEqualTo(ReportDef.Group.SALES_COMPLIANCE);
            assertThat(def.tier()).isEqualTo(ReportDef.Tier.SALES);
            assertThat(def.dated()).isTrue();
        }
        assertThat(ReportDef.Group.SALES_COMPLIANCE.label()).isEqualTo("Sales & compliance");
        assertThat(ReportDef.H1_REGISTER.columns()).extracting(ReportDef.Col::label).contains("Patient name", "Address");
    }
}
