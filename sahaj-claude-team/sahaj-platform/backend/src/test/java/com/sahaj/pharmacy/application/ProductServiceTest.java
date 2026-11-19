package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.OwnProduct;
import com.sahaj.pharmacy.application.PharmacyInputs.OwnProductChange;
import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyInputs.StoreSettingInput;
import com.sahaj.pharmacy.application.PharmacyProductFixture.Cat;
import com.sahaj.pharmacy.application.PharmacyViews.DuplicateCandidate;
import com.sahaj.pharmacy.application.PharmacyViews.ProductDetail;
import com.sahaj.pharmacy.domain.ProductSchedule;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-78-5 and 78-6, the writes of the product master: adopt, create an own product, edit it, and the local settings. The
 * check order, the duplicate advice, the unit lock and the "global fields are read-only" rule (AC-M3).
 */
class ProductServiceTest {

    private final PharmacyProductFixture x = new PharmacyProductFixture();
    private final PharmacyFixture f = x.f;
    private final UUID tenant = PharmacyFixture.TENANT;
    private final UUID owner = f.owner.userId();
    private final PharmacyViews.Store central = f.createCentral();
    private final Cat ceftriaxone = x.catalogueProduct("G-000101", "Sample Ceftriaxone 1 g injection", "Ceftriaxone", "1 g",
            "injection");

    // ---- builders ----

    /** An own product body; every field can be changed before it is built. */
    private static final class Body {
        UUID clientKey = UUID.randomUUID();
        String name = "Test dressing kit (OT)";
        String salt = "Dressing kit";
        String strength = "1 kit";
        String form = "kit";
        String purchaseUnit = "Kit";
        String issueUnit = "kit";
        BigDecimal conversion = BigDecimal.ONE;
        Sent<String> manufacturer = Sent.of(null);
        Boolean madeInHouse = true;
        String hsn = "3005";
        String gstRate = "12";
        Sent<ProductSchedule> schedule = Sent.of(ProductSchedule.NONE);
        String barcode;
        Boolean ignore = false;

        OwnProduct create() {
            return new OwnProduct(clientKey, name, salt, strength, form, purchaseUnit, issueUnit, conversion, manufacturer,
                    madeInHouse, hsn, gstRate, schedule, barcode, ignore);
        }

        OwnProductChange change(long version) {
            return new OwnProductChange(version, name, salt, strength, form, purchaseUnit, issueUnit, conversion,
                    manufacturer, madeInHouse, hsn, gstRate, schedule, barcode, ignore);
        }
    }

    private ProductDetail create(Body body) {
        return x.productService.create(tenant, owner, body.create()).view();
    }

    private static void assertFields(Runnable action, String... fieldAndMessage) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors()).extracting(e -> e.field() + ": " + e.message())
                        .contains(fieldAndMessage));
    }

    private static void assertConflict(Runnable action, String slug) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConflictException.class,
                ex -> assertThat(ex.problemType()).isEqualTo(slug));
    }

    private static void assertProblem(Runnable action, int status, String slug) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
            assertThat(ex.status()).isEqualTo(status);
            assertThat(ex.slug()).isEqualTo(slug);
        });
    }

    // ---- adopt ----

    @Test
    void adoptLinksTheCatalogueProductAndCopiesNoGlobalField() {
        f.audit.clear();
        PharmacyViews.Outcome<ProductDetail> outcome = x.productService.adopt(tenant, owner, ceftriaxone.id.toString());

        assertThat(outcome.created()).isTrue();
        ProductDetail p = outcome.view();
        assertThat(p.source()).isEqualTo("GLOBAL");
        assertThat(p.inUnit()).isEqualTo("ADOPTED");
        assertThat(p.code()).isEqualTo("G-000101");
        assertThat(p.name()).isEqualTo("Sample Ceftriaxone 1 g injection");
        assertThat(p.catalogueProductId()).isEqualTo(ceftriaxone.id);
        assertThat(p.version()).isZero();
        assertThat(f.auditActions()).containsExactly("PHARMACY_PRODUCT_ADOPTED");
        assertThat(x.productRows.values().iterator().next().fields).isNull();
    }

    @Test
    void adoptingAgainIs200AndWritesNothing() {
        x.productService.adopt(tenant, owner, ceftriaxone.id.toString());
        x.productWrites.clear();
        f.audit.clear();

        PharmacyViews.Outcome<ProductDetail> again = x.productService.adopt(tenant, owner, ceftriaxone.id.toString());

        assertThat(again.created()).isFalse();
        assertThat(x.productWrites).isEmpty();
        assertThat(f.audit).isEmpty();
        assertThat(x.productRows).hasSize(1);
    }

    @Test
    void aWithdrawnProductCannotBeAdoptedButAnAdoptedOneStaysVisibleAsWithdrawn() {
        x.productService.adopt(tenant, owner, ceftriaxone.id.toString());
        Cat other = x.catalogueProduct("G-000102", "Sample Ceftriaxone 500 mg injection", "Ceftriaxone", "500 mg", "injection");
        other.active = false;
        ceftriaxone.active = false;

        assertConflict(() -> x.productService.adopt(tenant, owner, other.id.toString()), "product-withdrawn");
        PharmacyViews.Outcome<ProductDetail> adopted = x.productService.adopt(tenant, owner, ceftriaxone.id.toString());
        assertThat(adopted.created()).isFalse();
        assertThat(adopted.view().inUnit()).isEqualTo("WITHDRAWN");
    }

    @Test
    void adoptAnswers404ForAnUnknownIdAnd400ForAMalformedOneAfterTheGate() {
        assertThatThrownBy(() -> x.productService.adopt(tenant, owner, UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
        assertProblem(() -> x.productService.adopt(tenant, owner, "not-a-uuid"), 400, "bad-request");
        assertThatThrownBy(() -> x.productService.adopt(tenant, f.nurse.userId(), "not-a-uuid"))
                .isInstanceOf(ForbiddenException.class);
        f.pharmacyOn = false;
        assertThatThrownBy(() -> x.productService.adopt(tenant, owner, "not-a-uuid")).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void onlyTheOwnerWritesProducts() {
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.pharmacist, f.storeStaff, f.nurse}) {
            assertThatThrownBy(() -> x.productService.adopt(tenant, p.userId(), ceftriaxone.id.toString()))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> x.productService.create(tenant, p.userId(), new Body().create()))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> x.productService.update(tenant, p.userId(), UUID.randomUUID().toString(),
                    new Body().change(0)))
                    .isInstanceOf(ForbiddenException.class);
        }
        assertThat(x.productRows).isEmpty();
    }

    // ---- create ----

    @Test
    void createMakesAnOwnProductWithTheUnitCodeAndOneAuditRow() {
        f.audit.clear();
        PharmacyViews.Outcome<ProductDetail> outcome = x.productService.create(tenant, owner, new Body().create());

        assertThat(outcome.created()).isTrue();
        ProductDetail p = outcome.view();
        assertThat(p.code()).isEqualTo("U-SUN-0001");
        assertThat(p.source()).isEqualTo("OWN");
        assertThat(p.inUnit()).isEqualTo("ACTIVE");
        assertThat(p.madeInHouse()).isTrue();
        assertThat(p.manufacturer()).isNull();
        assertThat(p.schedule()).isEqualTo("NONE");
        assertThat(p.gstRate()).isEqualTo("12");
        assertThat(p.conversion()).isEqualTo(1);
        assertThat(p.barcodes()).isEmpty();
        assertThat(p.localSettings().storeSettings()).extracting(PharmacyViews.StoreSetting::storeId)
                .containsExactly(central.id());
        assertThat(p.unitsLocked()).isFalse();
        assertThat(f.auditActions()).containsExactly("PHARMACY_OWN_PRODUCT_CREATED");
        assertThat(f.audit.get(0).after()).containsEntry("code", "U-SUN-0001").doesNotContainKey("duplicateOfCodes");
    }

    @Test
    void theNumbersOfTheOwnProductCodesCountUp() {
        assertThat(create(new Body()).code()).isEqualTo("U-SUN-0001");
        Body second = new Body();
        second.name = "Test suture kit";
        second.salt = "Suture kit";
        assertThat(create(second).code()).isEqualTo("U-SUN-0002");
    }

    @Test
    void everyFieldErrorIsAnsweredTogetherAndNothingIsWritten() {
        Body b = new Body();
        b.name = " ";
        b.salt = "";
        b.strength = "x".repeat(81);
        b.form = "";
        b.purchaseUnit = "x".repeat(21);
        b.issueUnit = null;
        b.conversion = new BigDecimal("2.5");
        b.madeInHouse = false;
        b.manufacturer = Sent.of(" ");
        b.hsn = "123";
        b.gstRate = "100.5";
        b.schedule = Sent.of(null);
        b.barcode = "bad barcode!";

        assertThatThrownBy(() -> x.productService.create(tenant, owner, b.create()))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field() + ": " + e.message()).containsExactlyInAnyOrder(
                                "name: Enter the product name", "salt: Enter the salt",
                                "strength: Keep it to 80 characters", "form: Enter the form",
                                "purchaseUnit: Keep it to 20 characters", "issueUnit: Enter the issue unit",
                                "conversion: Enter a whole number, 1 or more",
                                "manufacturer: Enter the manufacturer or choose Made in-house",
                                "hsn: Enter a 4 to 8 digit HSN", "gstRate: Enter a GST % from 0 to 100",
                                "schedule: Choose the schedule", "barcode: Use up to 40 letters, digits or hyphens"));
        assertThat(x.productRows).isEmpty();
        assertThat(x.productWrites).isEmpty();
    }

    @Test
    void conversionMustBeAWholeNumberFromOneToTenThousand() {
        for (String bad : new String[] {"0", "-1", "10001", "1.5"}) {
            Body b = new Body();
            b.conversion = new BigDecimal(bad);
            assertFields(() -> x.productService.create(tenant, owner, b.create()), "conversion: Enter a whole number, 1 or more");
        }
        Body edge = new Body();
        edge.conversion = new BigDecimal("10000");
        assertThat(create(edge).conversion()).isEqualTo(10000);
    }

    @Test
    void aFieldTheRequestLeftOutIsRefusedNotReadAsNull() {
        Body b = new Body();
        b.manufacturer = Sent.absent();
        assertFields(() -> x.productService.create(tenant, owner, b.create()), "manufacturer: This field is required");
        Body noClientKey = new Body();
        noClientKey.clientKey = null;
        assertFields(() -> x.productService.create(tenant, owner, noClientKey.create()),
                "clientKey: Reload the page and try again.");
    }

    @Test
    void aManufacturerIsNeededUnlessMadeInHouseAndIsDroppedWhenMadeInHouse() {
        Body maker = new Body();
        maker.madeInHouse = false;
        maker.manufacturer = Sent.of("Test Pharma Works");
        assertThat(create(maker).manufacturer()).isEqualTo("Test Pharma Works");

        Body inHouse = new Body();
        inHouse.name = "Test own kit";
        inHouse.salt = "Own kit";
        inHouse.manufacturer = Sent.of("Typed but ignored");
        ProductDetail p = create(inHouse);
        assertThat(p.madeInHouse()).isTrue();
        assertThat(p.manufacturer()).isNull();
    }

    @Test
    void theDuplicateAdviceListsTheCatalogueFirstAndNothingIsSaved() {
        Body b = new Body();
        b.name = "Test ceftriaxone 1000 mg";
        b.salt = "  Ceftriaxone ";
        b.strength = "1000 mg";
        b.form = "Injection";

        assertThatThrownBy(() -> x.productService.create(tenant, owner, b.create()))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.slug()).isEqualTo("possible-duplicate");
                    @SuppressWarnings("unchecked")
                    List<DuplicateCandidate> candidates = (List<DuplicateCandidate>) ex.extensions().get("candidates");
                    assertThat(candidates).extracting(DuplicateCandidate::code).containsExactly("G-000101");
                    assertThat(candidates.get(0).inUnit()).isEqualTo("NOT_ADOPTED");
                });
        assertThat(x.productRows).isEmpty();
        assertThat(x.productWrites).isEmpty();
    }

    @Test
    void createAnywayRecordsTheMatchesInTheAudit() {
        Body b = new Body();
        b.salt = "Ceftriaxone";
        b.strength = "1 g";
        b.form = "injection";
        b.ignore = true;
        f.audit.clear();

        assertThat(create(b).code()).isEqualTo("U-SUN-0001");
        assertThat(f.audit.get(0).after()).containsEntry("duplicateOfCodes", List.of("G-000101"));
    }

    @Test
    void anOwnProductWithTheSameSaltStrengthAndFormIsAlsoAdviceAndCreateAnywayAllowsBoth() {
        Body first = new Body();
        create(first);
        Body again = new Body();
        again.name = "Test dressing kit (ward)";
        assertThatThrownBy(() -> x.productService.create(tenant, owner, again.create()))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    @SuppressWarnings("unchecked")
                    List<DuplicateCandidate> candidates = (List<DuplicateCandidate>) ex.extensions().get("candidates");
                    assertThat(candidates).extracting(DuplicateCandidate::code).containsExactly("U-SUN-0001");
                    assertThat(candidates.get(0).source()).isEqualTo("OWN");
                });
        again.ignore = true;
        create(again);
        assertThat(x.productRows).hasSize(2);
    }

    @Test
    void aBarcodeUsedByAnotherActiveProductIs409AndAnInactiveOneDoesNotBlock() {
        Body first = new Body();
        first.barcode = "TEST-BC-1";
        create(first);
        Body second = new Body();
        second.name = "Test other kit";
        second.salt = "Other kit";
        second.barcode = "test-bc-1";
        assertThatThrownBy(() -> x.productService.create(tenant, owner, second.create()))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.problemType()).isEqualTo("barcode-in-use");
                    assertThat(ex.getMessage()).isEqualTo("That barcode is already used by Test dressing kit (OT)");
                });
        x.productRows.values().iterator().next().active = false;
        assertThat(create(second).localSettings().barcode()).isEqualTo("test-bc-1");
    }

    @Test
    void theDuplicateAdviceComesBeforeTheBarcodeCheck() {
        Body first = new Body();
        first.barcode = "TEST-BC-2";
        create(first);
        Body second = new Body();
        second.barcode = "TEST-BC-2";
        assertProblem(() -> x.productService.create(tenant, owner, second.create()), 409, "possible-duplicate");
    }

    @Test
    void theSameClientKeyFromTheSameCallerAnswersTheFirstProductAgain() {
        Body b = new Body();
        ProductDetail first = create(b);
        x.productWrites.clear();
        f.audit.clear();

        PharmacyViews.Outcome<ProductDetail> again = x.productService.create(tenant, owner, b.create());

        assertThat(again.created()).isFalse();
        assertThat(again.view().productId()).isEqualTo(first.productId());
        assertThat(x.productWrites).isEmpty();
        assertThat(f.audit).isEmpty();
    }

    @Test
    void theSameClientKeyFromAnotherCallerIs409() {
        Body b = new Body();
        create(b);
        PharmacyFixture.Person secondOwner = f.person("OWNER", "Test Owner Two", true);
        assertThatThrownBy(() -> x.productService.create(tenant, secondOwner.userId(), b.create()))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.problemType()).isEqualTo("client-key-reused"));
        assertThat(x.productRows).hasSize(1);
    }

    @Test
    void theReplayAnswersBeforeTheFieldsAreChecked() {
        Body b = new Body();
        create(b);
        b.name = "";
        assertThat(x.productService.create(tenant, owner, b.create()).created()).isFalse();
    }

    // ---- edit an own product ----

    private ProductDetail ownKit() {
        return create(new Body());
    }

    @Test
    void anAdoptedProductCannotBeEditedAndTheAnswerIsTheContractsProblem() {
        ProductDetail adopted = x.productService.adopt(tenant, owner, ceftriaxone.id.toString()).view();
        assertThatThrownBy(() -> x.productService.update(tenant, owner, adopted.productId().toString(),
                new Body().change(0))).isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.slug()).isEqualTo("global-fields-read-only");
                    assertThat(ex.getMessage()).isEqualTo("Global fields cannot be changed. Use Local settings.");
                });
        assertThat(x.productWrites).doesNotContain("product.updateOwn");
    }

    @Test
    void editSavesTheChangeBumpsTheVersionAndAuditsBeforeAndAfter() {
        ProductDetail kit = ownKit();
        Body b = new Body();
        b.name = "Test dressing kit (theatre)";
        b.hsn = "3006";
        b.schedule = Sent.of(ProductSchedule.H);
        f.audit.clear();

        ProductDetail saved = x.productService.update(tenant, owner, kit.productId().toString(), b.change(kit.version()));

        assertThat(saved.name()).isEqualTo("Test dressing kit (theatre)");
        assertThat(saved.hsn()).isEqualTo("3006");
        assertThat(saved.schedule()).isEqualTo("H");
        assertThat(saved.code()).isEqualTo("U-SUN-0001");
        assertThat(saved.version()).isEqualTo(kit.version() + 1);
        assertThat(f.auditActions()).containsExactly("PHARMACY_PRODUCT_CHANGED");
        assertThat(f.audit.get(0).before()).containsEntry("name", "Test dressing kit (OT)").containsEntry("schedule", "NONE")
                .doesNotContainKey("salt");
        assertThat(f.audit.get(0).after()).containsEntry("name", "Test dressing kit (theatre)")
                .containsEntry("schedule", "H");
    }

    @Test
    void anEditThatChangesNothingWritesNothing() {
        ProductDetail kit = ownKit();
        x.productWrites.clear();
        f.audit.clear();

        ProductDetail same = x.productService.update(tenant, owner, kit.productId().toString(), new Body().change(kit.version()));

        assertThat(same.version()).isEqualTo(kit.version());
        assertThat(x.productWrites).isEmpty();
        assertThat(f.audit).isEmpty();
    }

    @Test
    void aStaleVersionAnswersSettingsChangedWithTheCurrentProduct() {
        ProductDetail kit = ownKit();
        Body b = new Body();
        b.name = "Changed once";
        x.productService.update(tenant, owner, kit.productId().toString(), b.change(kit.version()));
        b.name = "Changed twice";

        assertThatThrownBy(() -> x.productService.update(tenant, owner, kit.productId().toString(), b.change(kit.version())))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.slug()).isEqualTo("settings-changed");
                    assertThat(((ProductDetail) ex.extensions().get("current")).name()).isEqualTo("Changed once");
                });
    }

    @Test
    void unitsAndConversionAreLockedOnceTheProductHasStockButOtherFieldsStayEditable() {
        ProductDetail kit = ownKit();
        x.productRows.get(kit.productId()).hasStock = true;
        Body b = new Body();
        b.conversion = new BigDecimal("2");
        assertConflict(() -> x.productService.update(tenant, owner, kit.productId().toString(), b.change(kit.version())),
                "units-locked");
        Body unit = new Body();
        unit.purchaseUnit = "Box";
        assertConflict(() -> x.productService.update(tenant, owner, kit.productId().toString(), unit.change(kit.version())),
                "units-locked");
        Body name = new Body();
        name.name = "Renamed with stock";
        assertThat(x.productService.update(tenant, owner, kit.productId().toString(), name.change(kit.version())).name())
                .isEqualTo("Renamed with stock");
    }

    @Test
    void theDuplicateCheckRunsWhenSaltStrengthOrFormChangesAndTheProductItselfIsLeftOut() {
        ProductDetail kit = ownKit();
        Body b = new Body();
        b.salt = "Ceftriaxone";
        b.strength = "1 g";
        b.form = "injection";
        assertProblem(() -> x.productService.update(tenant, owner, kit.productId().toString(), b.change(kit.version())),
                409, "possible-duplicate");
        b.ignore = true;
        assertThat(x.productService.update(tenant, owner, kit.productId().toString(), b.change(kit.version())).salt())
                .isEqualTo("Ceftriaxone");
        // saving it again with the same key is not a new duplicate of itself
        ProductDetail current = x.searchService.get(tenant, owner, kit.productId().toString());
        Body rename = new Body();
        rename.salt = "Ceftriaxone";
        rename.strength = "1 g";
        rename.form = "injection";
        rename.name = "Renamed";
        rename.ignore = false;
        assertThat(x.productService.update(tenant, owner, kit.productId().toString(), rename.change(current.version())).name())
                .isEqualTo("Renamed");
    }

    @Test
    void editRefusesAnotherProductsBarcodeAndAnUnknownProduct() {
        Body first = new Body();
        first.barcode = "TEST-BC-9";
        create(first);
        Body secondBody = new Body();
        secondBody.name = "Test second kit";
        secondBody.salt = "Second kit";
        ProductDetail second = create(secondBody);
        secondBody.barcode = "TEST-BC-9";
        assertConflict(() -> x.productService.update(tenant, owner, second.productId().toString(),
                secondBody.change(second.version())), "barcode-in-use");
        assertThatThrownBy(() -> x.productService.update(tenant, owner, UUID.randomUUID().toString(), secondBody.change(0)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anotherClinicsProductIs404() {
        ProductDetail kit = ownKit();
        x.productRows.get(kit.productId()).tenant = PharmacyFixture.OTHER_TENANT;
        assertThatThrownBy(() -> x.productService.update(tenant, owner, kit.productId().toString(), new Body().change(0)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> x.searchService.get(tenant, owner, kit.productId().toString()))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- local settings ----

    private static StoreSettingInput setting(UUID storeId, Integer level, String rack) {
        return new StoreSettingInput(storeId, Sent.of(level == null ? null : BigDecimal.valueOf(level)), Sent.of(rack));
    }

    private PharmacyInputs.LocalSettings local(long version, List<StoreSettingInput> stores, UUID vendor, String alias,
                                               String barcode, boolean returnable, boolean active) {
        return new PharmacyInputs.LocalSettings(version, stores, Sent.of(vendor), Sent.of(alias), Sent.of(barcode),
                returnable, active);
    }

    @Test
    void localSettingsAreSavedForAnAdoptedProductWithoutTouchingTheGlobalFields() {
        PharmacyViews.Store ward = f.createStore("Test Ward 3B Store", "W3B", StoreType.WARD);
        ProductDetail adopted = x.productService.adopt(tenant, owner, ceftriaxone.id.toString()).view();
        f.audit.clear();

        ProductDetail saved = x.productService.saveLocalSettings(tenant, owner, adopted.productId().toString(),
                local(adopted.version(), List.of(setting(central.id(), 200, "A-3"), setting(ward.id(), 40, null)), null,
                        "Ceftri 1g", "TEST-BC-7", false, true));

        assertThat(saved.localSettings().storeSettings()).extracting(s -> s.storeName() + ":" + s.reorderLevel() + ":" + s.rack())
                .containsExactly("Central Store:200:A-3", "Test Ward 3B Store:40:null");
        assertThat(saved.localSettings().alias()).isEqualTo("Ceftri 1g");
        assertThat(saved.alias()).isEqualTo("Ceftri 1g");
        assertThat(saved.localSettings().barcode()).isEqualTo("TEST-BC-7");
        assertThat(saved.localSettings().returnable()).isFalse();
        assertThat(saved.version()).isEqualTo(adopted.version() + 1);
        assertThat(saved.name()).isEqualTo("Sample Ceftriaxone 1 g injection");
        assertThat(f.auditActions()).containsExactly("PHARMACY_LOCAL_SETTINGS_CHANGED");
    }

    @Test
    void aStoreSettingCanBeClearedAndANoChangeSaveWritesNothing() {
        ProductDetail kit = ownKit();
        ProductDetail first = x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(kit.version(), List.of(setting(central.id(), 10, "R1")), null, null, null, true, true));
        ProductDetail cleared = x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(first.version(), List.of(setting(central.id(), null, null)), null, null, null, true, true));
        assertThat(cleared.localSettings().storeSettings().get(0).reorderLevel()).isNull();
        assertThat(cleared.localSettings().storeSettings().get(0).rack()).isNull();

        x.productWrites.clear();
        f.audit.clear();
        ProductDetail same = x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(cleared.version(), List.of(setting(central.id(), null, null)), null, null, null, true, true));
        assertThat(same.version()).isEqualTo(cleared.version());
        assertThat(x.productWrites).isEmpty();
        assertThat(f.audit).isEmpty();
    }

    @Test
    void localSettingsFieldErrorsAreAnsweredTogether() {
        ProductDetail kit = ownKit();
        UUID unknownStore = UUID.randomUUID();
        PharmacyInputs.LocalSettings bad = local(kit.version(), List.of(
                setting(central.id(), -1, null),
                new StoreSettingInput(central.id(), Sent.of(new BigDecimal("2.5")), Sent.of("x".repeat(31))),
                setting(unknownStore, 5, null)), UUID.randomUUID(), "a".repeat(41), "bad barcode", true, true);

        assertThatThrownBy(() -> x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(), bad))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field() + ": " + e.message()).containsExactlyInAnyOrder(
                                "storeSettings[0].reorderLevel: Enter a whole number, 0 or more",
                                "storeSettings[1].storeId: List each store once",
                                "storeSettings[1].reorderLevel: Enter a whole number, 0 or more",
                                "storeSettings[1].rack: Keep the rack to 30 characters",
                                "storeSettings[2].storeId: Choose an active store",
                                "preferredVendorId: Choose an active vendor",
                                "alias: Keep the alias to 40 characters",
                                "barcode: Use up to 40 letters, digits or hyphens"));
        assertThat(x.productWrites).doesNotContain("product.updateLocal");
    }

    @Test
    void aGlobalFieldOrAMissingFieldInTheBodyIsRefused() {
        ProductDetail kit = ownKit();
        PharmacyInputs.LocalSettings absent = new PharmacyInputs.LocalSettings(kit.version(), List.of(), Sent.absent(),
                Sent.absent(), Sent.absent(), null, null);
        assertThatThrownBy(() -> x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(), absent))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field() + ": " + e.message()).containsExactlyInAnyOrder(
                                "preferredVendorId: This field is required", "alias: This field is required",
                                "barcode: This field is required", "returnable: This field is required",
                                "active: This field is required"));
    }

    @Test
    void thePreferredVendorMustBeActiveAndNotBlockedButAnUnchangedOneStaysSavable() {
        ProductDetail kit = ownKit();
        UUID good = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        UUID off = UUID.randomUUID();
        UUID licenceDayItself = UUID.randomUUID();
        x.vendors.put(good, new PharmacyProductFixture.Vendor(good, "Test Vendor One", true, true, LocalDate.of(2028, 3, 31)));
        x.vendors.put(blocked, new PharmacyProductFixture.Vendor(blocked, "Test Vendor Two", true, true, PharmacyFixture.TODAY.minusDays(1)));
        x.vendors.put(off, new PharmacyProductFixture.Vendor(off, "Test Vendor Three", false, false, null));
        x.vendors.put(licenceDayItself, new PharmacyProductFixture.Vendor(licenceDayItself, "Test Vendor Four", true, true, PharmacyFixture.TODAY));

        for (UUID vendor : new UUID[] {blocked, off, UUID.randomUUID()}) {
            assertFields(() -> x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                    local(kit.version(), List.of(), vendor, null, null, true, true)),
                    "preferredVendorId: Choose an active vendor");
        }
        ProductDetail withVendor = x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(kit.version(), List.of(), licenceDayItself, null, null, true, true));
        assertThat(withVendor.localSettings().preferredVendor().needsReview()).isFalse();

        // the vendor's licence expires tomorrow; the product shows "needs review" and the owner can still save other settings
        x.vendors.put(licenceDayItself, new PharmacyProductFixture.Vendor(licenceDayItself, "Test Vendor Four", true, true,
                PharmacyFixture.TODAY.minusDays(1)));
        ProductDetail review = x.searchService.get(tenant, owner, kit.productId().toString());
        assertThat(review.localSettings().preferredVendor().needsReview()).isTrue();
        ProductDetail saved = x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(review.version(), List.of(), licenceDayItself, "Kept vendor", null, true, true));
        assertThat(saved.localSettings().preferredVendor().id()).isEqualTo(licenceDayItself);
        assertThat(saved.localSettings().alias()).isEqualTo("Kept vendor");
    }

    @Test
    void aBarcodeUsedByAnotherActiveProductIs409AndTheSameBarcodeOnAnInactiveProductIsAllowed() {
        Body b = new Body();
        b.barcode = "TEST-BC-5";
        create(b);
        ProductDetail adopted = x.productService.adopt(tenant, owner, ceftriaxone.id.toString()).view();
        assertConflict(() -> x.productService.saveLocalSettings(tenant, owner, adopted.productId().toString(),
                local(adopted.version(), List.of(), null, null, "test-bc-5", true, true)), "barcode-in-use");
        ProductDetail inactive = x.productService.saveLocalSettings(tenant, owner, adopted.productId().toString(),
                local(adopted.version(), List.of(), null, null, "TEST-BC-5", true, false));
        assertThat(inactive.inUnit()).isEqualTo("INACTIVE");
        // switching it on again would now clash
        assertConflict(() -> x.productService.saveLocalSettings(tenant, owner, adopted.productId().toString(),
                local(inactive.version(), List.of(), null, null, "TEST-BC-5", true, true)), "barcode-in-use");
    }

    @Test
    void aProductWithStockCannotBeSwitchedOffAndOneWithoutStockIsAuditedAsSwitchedOff() {
        ProductDetail kit = ownKit();
        x.productRows.get(kit.productId()).hasStock = true;
        assertThatThrownBy(() -> x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(kit.version(), List.of(), null, null, null, true, false)))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.problemType()).isEqualTo("product-has-stock");
                    assertThat(ex.getMessage()).isEqualTo("This product has stock. Clear it in Inventory first.");
                });

        x.productRows.get(kit.productId()).hasStock = false;
        f.audit.clear();
        ProductDetail off = x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(kit.version(), List.of(), null, null, null, true, false));
        assertThat(off.inUnit()).isEqualTo("INACTIVE");
        assertThat(f.auditActions()).containsExactly("PHARMACY_LOCAL_SETTINGS_CHANGED", "PHARMACY_PRODUCT_SWITCHED_OFF");

        f.audit.clear();
        x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(off.version(), List.of(), null, null, null, true, true));
        assertThat(f.auditActions()).containsExactly("PHARMACY_LOCAL_SETTINGS_CHANGED", "PHARMACY_PRODUCT_SWITCHED_ON");
    }

    @Test
    void aStaleVersionOfTheLocalSettingsAnswersSettingsChangedWithTheCurrentProduct() {
        ProductDetail kit = ownKit();
        x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(kit.version(), List.of(), null, "First", null, true, true));
        assertThatThrownBy(() -> x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(kit.version(), List.of(), null, "Second", null, true, true)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("settings-changed");
                    assertThat(((ProductDetail) ex.extensions().get("current")).localSettings().alias()).isEqualTo("First");
                });
    }

    @Test
    void localSettingsOfAnotherClinicsProductAre404AndAnythingButTheOwnerIs403() {
        ProductDetail kit = ownKit();
        assertThatThrownBy(() -> x.productService.saveLocalSettings(tenant, f.pharmacist.userId(),
                kit.productId().toString(), local(0, List.of(), null, null, null, true, true)))
                .isInstanceOf(ForbiddenException.class);
        x.productRows.get(kit.productId()).tenant = PharmacyFixture.OTHER_TENANT;
        assertThatThrownBy(() -> x.productService.saveLocalSettings(tenant, owner, kit.productId().toString(),
                local(0, List.of(), null, null, null, true, true))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theBodyIsNotReadWhenTheModuleIsOffOrTheCallerIsNotTheOwner() {
        PharmacyInputs.Body<OwnProduct> createBody = () -> {
            throw new IllegalStateException("the body was read");
        };
        PharmacyInputs.Body<OwnProductChange> changeBody = () -> {
            throw new IllegalStateException("the body was read");
        };
        PharmacyInputs.Body<PharmacyInputs.LocalSettings> localBody = () -> {
            throw new IllegalStateException("the body was read");
        };
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> x.productService.create(tenant, f.nurse.userId(), createBody)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> x.productService.update(tenant, f.pharmacist.userId(), id, changeBody)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> x.productService.saveLocalSettings(tenant, f.pharmacist.userId(), id, localBody))
                .isInstanceOf(ForbiddenException.class);
        // a path id that is not an id is answered before the body is read
        assertProblem(() -> x.productService.update(tenant, owner, "nope", changeBody), 400, "bad-request");
        assertProblem(() -> x.productService.saveLocalSettings(tenant, owner, "nope", localBody), 400, "bad-request");
        f.pharmacyOn = false;
        assertThatThrownBy(() -> x.productService.create(tenant, owner, createBody)).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> x.productService.update(tenant, owner, id, changeBody)).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void theOwnFieldsOfTheAuditCarryNoPersonalData() {
        create(new Body());
        Map<String, Object> after = f.audit.get(f.audit.size() - 1).after();
        assertThat(after.keySet()).doesNotContain("mobile", "phone");
    }
}
