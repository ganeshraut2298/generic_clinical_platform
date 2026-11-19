package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyProductFixture.Cat;
import com.sahaj.pharmacy.domain.ProductSchedule;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-78-5 and 78-6, the reads: the one list of global and own products, one product, the duplicate check, the options. */
class ProductSearchServiceTest {

    private final PharmacyProductFixture x = new PharmacyProductFixture();
    private final PharmacyFixture f = x.f;
    private final UUID tenant = PharmacyFixture.TENANT;
    private final UUID owner = f.owner.userId();
    private final PharmacyViews.Store central = f.createCentral();
    private final Cat ceftriaxone = x.catalogueProduct("G-000101", "Sample Ceftriaxone 1 g injection", "Ceftriaxone", "1 g",
            "injection");
    private final Cat paracetamol = x.catalogueProduct("G-000103", "Sample Paracetamol 650 mg tablet", "Paracetamol",
            "650 mg", "tablet");

    private PharmacyViews.ProductDetail own(String name, String salt, String barcode) {
        return x.productService.create(tenant, owner, new PharmacyInputs.OwnProduct(UUID.randomUUID(), name, salt, "1 kit",
                "kit", "Kit", "kit", BigDecimal.ONE, Sent.of(null), true, "3005", "12", Sent.of(ProductSchedule.NONE),
                barcode, false)).view();
    }

    private PharmacyViews.ProductPage search(String q, String source, String page, String size) {
        return x.searchService.search(tenant, owner, q, source, page, size);
    }

    @Test
    void withoutATextOrWithOneCharacterTheFirstPageIsUnfilteredAndWritesNothing() {
        own("Test dressing kit (OT)", "Dressing kit", null);
        x.productWrites.clear();

        for (String q : new String[] {null, "", " ", "c", " p "}) {
            PharmacyViews.ProductPage page = search(q, null, null, null);
            assertThat(page.total()).isEqualTo(3);
            assertThat(page.page()).isZero();
            assertThat(page.size()).isEqualTo(25);
        }
        assertThat(x.productWrites).isEmpty();
    }

    @Test
    void theListHoldsGlobalRowsWithTheUnitsAdoptionAndOwnRows() {
        x.productService.adopt(tenant, owner, ceftriaxone.id.toString());
        own("Test dressing kit (OT)", "Dressing kit", null);

        PharmacyViews.ProductPage page = search(null, null, null, null);

        assertThat(page.items()).extracting(r -> r.code() + ":" + r.source() + ":" + r.inUnit()).containsExactly(
                "G-000101:GLOBAL:ADOPTED", "G-000103:GLOBAL:NOT_ADOPTED", "U-SUN-0001:OWN:ACTIVE");
        PharmacyViews.ProductRow adopted = page.items().get(0);
        assertThat(adopted.productId()).isNotNull();
        assertThat(adopted.catalogueProductId()).isEqualTo(ceftriaxone.id);
        assertThat(adopted.sample()).isTrue();
        assertThat(adopted.gstRate()).isEqualTo("12");
        assertThat(page.items().get(1).productId()).isNull();
        assertThat(page.items().get(2).catalogueProductId()).isNull();
        assertThat(page.items().get(2).madeInHouse()).isTrue();
    }

    @Test
    void aWithdrawnProductStaysForUnitsThatAdoptedItAndIsHiddenFromOthers() {
        x.productService.adopt(tenant, owner, ceftriaxone.id.toString());
        ceftriaxone.active = false;
        paracetamol.active = false;

        PharmacyViews.ProductPage page = search(null, null, null, null);

        assertThat(page.items()).extracting(r -> r.code() + ":" + r.inUnit()).containsExactly("G-000101:WITHDRAWN");
    }

    @Test
    void aSwitchedOffAdoptedProductReadsInactive() {
        PharmacyViews.ProductDetail adopted = x.productService.adopt(tenant, owner, ceftriaxone.id.toString()).view();
        x.productService.saveLocalSettings(tenant, owner, adopted.productId().toString(), new PharmacyInputs.LocalSettings(
                adopted.version(), java.util.List.of(), Sent.of(null), Sent.of(null), Sent.of(null), true, false));
        assertThat(search("ceftri", null, null, null).items()).extracting(PharmacyViews.ProductRow::inUnit)
                .containsExactly("INACTIVE");
    }

    @Test
    void aTextOfTwoOrMoreCharactersMatchesIgnoringCaseAndTheCodeComesFirst() {
        own("Test paracetamol syrup", "Paracetamol syrup", null);

        assertThat(search("PARACET", null, null, null).items()).extracting(PharmacyViews.ProductRow::code)
                .containsExactly("G-000103", "U-SUN-0001");
        assertThat(search("g-000103", null, null, null).items()).extracting(PharmacyViews.ProductRow::code)
                .containsExactly("G-000103");
        assertThat(search("zzzz", null, null, null).items()).isEmpty();
        assertThat(search("zzzz", null, null, null).total()).isZero();
    }

    @Test
    void theSourceFilterAndThePagingAreApplied() {
        own("Test dressing kit (OT)", "Dressing kit", null);
        own("Test suture kit", "Suture kit", null);

        assertThat(search(null, "OWN", null, null).items()).extracting(PharmacyViews.ProductRow::source).containsOnly("OWN");
        assertThat(search(null, "GLOBAL", null, null).items()).extracting(PharmacyViews.ProductRow::source)
                .containsOnly("GLOBAL");
        PharmacyViews.ProductPage second = search(null, null, "1", "2");
        assertThat(second.items()).hasSize(2);
        assertThat(second.total()).isEqualTo(4);
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);
    }

    @Test
    void aBadQueryValueIsAFieldErrorAndTheTextIsNeverEchoed() {
        assertThatThrownBy(() -> search("x".repeat(61), null, null, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors()).extracting(e -> e.field() + ": " + e.message())
                        .containsExactly("q: Type at most 60 characters."));
        assertThat(search("x".repeat(60), null, null, null).items()).isEmpty();
        for (String[] bad : new String[][] {{"source", "MINE"}, {"page", "-1"}, {"page", "2001"}, {"size", "0"}, {"size", "51"},
                {"size", "ten"}}) {
            assertThatThrownBy(() -> search(null, "source".equals(bad[0]) ? bad[1] : null,
                    "page".equals(bad[0]) ? bad[1] : null, "size".equals(bad[0]) ? bad[1] : null))
                    .isInstanceOfSatisfying(InvalidFieldsException.class,
                            ex -> assertThat(ex.errors().get(0).field()).isEqualTo(bad[0]));
        }
        assertThat(search(null, null, "2000", "50").size()).isEqualTo(50);
    }

    @Test
    void thePercentAndUnderscoreOfASearchTextAreLiteral() {
        assertThat(ProductSearchService.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }

    @Test
    void readersOfSetupSeeTheListAndEveryoneElseIs403() {
        f.map(central, f.storeStaff, StoreRole.CENTRAL_STORE_USER);
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.owner, f.pharmacist, f.storeStaff}) {
            assertThat(x.searchService.search(tenant, p.userId(), null, null, null, null).total()).isEqualTo(2);
            assertThat(x.searchService.options(tenant, p.userId()).units()).isNotEmpty();
        }
        PharmacyFixture.Person unmapped = f.person("STORE_STAFF", "Test Store Staff Two", true);
        f.map(f.createStore("Test Ward 3B Store", "W3B", com.sahaj.pharmacy.domain.StoreType.WARD), f.nurse, StoreRole.STORE_USER);
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.nurse, unmapped, f.frontDesk}) {
            assertThatThrownBy(() -> x.searchService.search(tenant, p.userId(), null, null, null, null))
                    .isInstanceOf(ForbiddenException.class);
        }
        f.pharmacyOn = false;
        assertThatThrownBy(() -> x.searchService.search(tenant, owner, "x".repeat(99), "bad", "bad", "bad"))
                .isInstanceOf(ModuleOffException.class);
    }

    @Test
    void aProductDetailReadsTheCatalogueAtReadTimeAndShowsTheBarcodesAndEveryActiveStore() {
        ceftriaxone.barcodes.add("8900000000017");
        PharmacyViews.Store ward = f.createStore("Test Ward 3B Store", "W3B", com.sahaj.pharmacy.domain.StoreType.WARD);
        PharmacyViews.ProductDetail adopted = x.productService.adopt(tenant, owner, ceftriaxone.id.toString()).view();
        ceftriaxone.name = "Sample Ceftriaxone 1 g injection (renamed)";

        PharmacyViews.ProductDetail read = x.searchService.get(tenant, f.pharmacist.userId(), adopted.productId().toString());

        assertThat(read.name()).isEqualTo("Sample Ceftriaxone 1 g injection (renamed)");
        assertThat(read.barcodes()).containsExactly("8900000000017");
        assertThat(read.localSettings().storeSettings()).extracting(PharmacyViews.StoreSetting::storeId)
                .containsExactly(central.id(), ward.id());
        assertThat(read.localSettings().preferredVendor()).isNull();
        assertThat(read.localSettings().returnable()).isTrue();
        assertThat(read.localSettings().active()).isTrue();
        assertThat(read.unitsLocked()).isFalse();
        x.productRows.get(adopted.productId()).hasStock = true;
        assertThat(x.searchService.get(tenant, owner, adopted.productId().toString()).unitsLocked()).isTrue();
    }

    @Test
    void aProductDetailIsMissingForAnUnknownIdAndBadRequestForAMalformedOne() {
        assertThatThrownBy(() -> x.searchService.get(tenant, owner, UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> x.searchService.get(tenant, owner, "not-a-uuid"))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
        // a catalogue id is not a unit product id
        assertThatThrownBy(() -> x.searchService.get(tenant, owner, ceftriaxone.id.toString()))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- duplicates ----

    @Test
    void theDuplicateCheckListsCatalogueMatchesThenOwnMatchesAndLeavesTheProductItselfOut() {
        PharmacyViews.ProductDetail mine = own("Test ceftriaxone vial", "Ceftriaxone", null);
        x.productService.update(tenant, owner, mine.productId().toString(), new PharmacyInputs.OwnProductChange(
                mine.version(), "Test ceftriaxone vial", "Ceftriaxone", "1000 mg", "Injection", "Kit", "kit", BigDecimal.ONE,
                Sent.of(null), true, "3005", "12", Sent.of(ProductSchedule.NONE), null, true));

        PharmacyViews.Duplicates found = x.searchService.duplicates(tenant, owner, "ceftriaxone", " 1 g", "INJECTION", null);
        assertThat(found.candidates()).extracting(c -> c.source() + ":" + c.code()).containsExactly("GLOBAL:G-000101",
                "OWN:U-SUN-0001");
        assertThat(found.candidates().get(0).inUnit()).isEqualTo("NOT_ADOPTED");
        assertThat(found.candidates().get(1).inUnit()).isEqualTo("ACTIVE");

        assertThat(x.searchService.duplicates(tenant, owner, "ceftriaxone", "1 g", "injection", mine.productId().toString())
                .candidates()).extracting(PharmacyViews.DuplicateCandidate::code).containsExactly("G-000101");
        assertThat(x.searchService.duplicates(tenant, owner, "Nothing", "1 g", "injection", null).candidates()).isEmpty();
    }

    @Test
    void anAdoptedCatalogueMatchIsMarkedAdoptedAndWithdrawnOnesAreNotOffered() {
        x.productService.adopt(tenant, owner, ceftriaxone.id.toString());
        assertThat(x.searchService.duplicates(tenant, owner, "Ceftriaxone", "1000 mg", "injection", null).candidates())
                .extracting(PharmacyViews.DuplicateCandidate::inUnit).containsExactly("ADOPTED");
        ceftriaxone.active = false;
        assertThat(x.searchService.duplicates(tenant, owner, "Ceftriaxone", "1000 mg", "injection", null).candidates())
                .isEmpty();
    }

    @Test
    void theDuplicateCheckNeedsSaltStrengthAndFormAndIsForTheOwnerOnly() {
        assertThatThrownBy(() -> x.searchService.duplicates(tenant, owner, "", " ", "x".repeat(31), "nope"))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field() + ": " + e.message()).containsExactly("salt: Enter the salt",
                                "strength: Enter the strength", "form: Keep it to 30 characters",
                                "excludeProductId: Choose a valid option"));
        assertThatThrownBy(() -> x.searchService.duplicates(tenant, f.pharmacist.userId(), "a", "b", "c", null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void atMostTenCandidatesAreAnswered() {
        for (int i = 0; i < 12; i++) {
            x.catalogueProduct(String.format("G-0002%02d", i), "Sample Ceftriaxone " + i, "Ceftriaxone", "1 g", "injection");
        }
        assertThat(x.searchService.duplicates(tenant, owner, "Ceftriaxone", "1 g", "injection", null).candidates())
                .hasSize(DuplicateFinder.MAX);
    }

    // ---- options ----

    @Test
    void theOptionsAreTheStarterListPlusWhatTheUnitUsedSortedAndWithoutRepeats() {
        PharmacyViews.ProductOptions starter = x.searchService.options(tenant, owner);
        assertThat(starter.units()).containsExactly("Ampoule", "Bottle", "Box", "Capsule", "Kit", "Sachet", "Strip", "Tablet",
                "Tube", "Vial");
        assertThat(starter.forms()).startsWith("capsule", "consumable").contains("syrup").hasSize(12);

        x.productService.create(tenant, owner, new PharmacyInputs.OwnProduct(UUID.randomUUID(), "Test pack", "Pack salt", "1",
                "Blister pack", "box", "Pair", BigDecimal.TEN, Sent.of(null), true, "3005", "12",
                Sent.of(ProductSchedule.NONE), null, false));
        PharmacyViews.ProductOptions used = x.searchService.options(tenant, owner);
        assertThat(used.units()).contains("Pair").containsOnlyOnce("Box");
        assertThat(used.units()).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        assertThat(used.forms()).contains("Blister pack").isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }
}
