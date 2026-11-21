package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.application.SaleInputs.BuyerInput;
import com.sahaj.pharmacy.application.SaleInputs.CartInput;
import com.sahaj.pharmacy.application.SaleInputs.CartLineInput;
import com.sahaj.pharmacy.application.SaleInputs.PrescriptionInput;
import com.sahaj.pharmacy.application.SaleInputs.ReasonInput;
import com.sahaj.pharmacy.application.SaleInputs.SubstitutionInput;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

/** The field checks of a cart (docs/api/pharmacy-sales.yaml CartInput): all errors together, nothing echoed. */
class SaleCartTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final BiPredicate<ReasonList, String> ACTIVE = (list, key) -> !key.startsWith("off");
    private static final UUID P1 = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID P2 = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    private static BuyerInput walkIn() {
        return new BuyerInput("WALK_IN", null, null, "Test Walk-in One", "9000000101", false, false);
    }

    private static CartLineInput line(UUID product, int qty) {
        return new CartLineInput(product, BigDecimal.valueOf(qty), null, null, null, null, null);
    }

    private static List<FieldMessage> check(CartInput cart) {
        List<FieldMessage> errors = new ArrayList<>();
        SaleCart.validate(cart, TODAY, ACTIVE, errors);
        return errors;
    }

    private static List<String> fields(List<FieldMessage> errors) {
        return errors.stream().map(FieldMessage::field).toList();
    }

    @Test
    void aCleanCartIsReadInTypedFormAndWritesBackWithWholeQuantities() {
        List<FieldMessage> errors = new ArrayList<>();
        SaleCart.Cart cart = SaleCart.validate(new CartInput(walkIn(), List.of(line(P1, 2)), "5.00", null, null, null), TODAY,
                ACTIVE, errors);

        assertThat(errors).isEmpty();
        assertThat(cart.lines().get(0).qty()).isEqualTo(2);
        assertThat(cart.percent()).isEqualByComparingTo("5");
        CartInput back = cart.toInput();
        assertThat(back.discountPercent()).isEqualTo("5");
        assertThat(back.lines().get(0).qty()).isEqualTo(BigDecimal.valueOf(2));
        assertThat(back.buyer().kind()).isEqualTo("WALK_IN");
    }

    @Test
    void noLinesAndMoreThanAHundredLinesAreRefusedWithTheContractSentences() {
        assertThat(check(new CartInput(walkIn(), List.of(), null, null, null, null))).extracting(FieldMessage::message)
                .containsExactly("Add at least one item.");
        List<CartLineInput> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(line(P1, 1));
        }
        assertThat(check(new CartInput(walkIn(), many, null, null, null, null))).extracting(FieldMessage::message)
                .containsExactly("A bill can have at most 100 items.");
    }

    @Test
    void aQuantityMustBeAWholeNumberFromOneToNineNineNineNineNineNine() {
        CartInput cart = new CartInput(walkIn(), List.of(
                new CartLineInput(P1, new BigDecimal("1.5"), null, null, null, null, null),
                new CartLineInput(P1, BigDecimal.ZERO, null, null, null, null, null),
                new CartLineInput(P1, new BigDecimal("1000000"), null, null, null, null, null),
                line(P2, 999_999)), null, null, null, null);

        assertThat(fields(check(cart))).containsExactly("lines[0].qty", "lines[1].qty", "lines[2].qty");
    }

    @Test
    void aSubstitutionNeedsAnActiveReasonAndTheAgreementOfThePatient() {
        CartLineInput none = new CartLineInput(P1, BigDecimal.ONE, P2, null, null, null, null);
        CartLineInput notAgreed = new CartLineInput(P1, BigDecimal.ONE, P2,
                new SubstitutionInput("substitution.out-of-stock", null, false), null, null, null);
        CartLineInput switchedOff = new CartLineInput(P1, BigDecimal.ONE, P2,
                new SubstitutionInput("off.reason", null, true), null, null, null);
        CartLineInput fine = new CartLineInput(P1, BigDecimal.ONE, P2,
                new SubstitutionInput("substitution.out-of-stock", null, true), null, null, null);

        List<FieldMessage> errors = check(new CartInput(walkIn(), List.of(none, notAgreed, switchedOff, fine), null, null, null,
                null));

        assertThat(fields(errors)).containsExactly("lines[0].substitution", "lines[1].substitution", "lines[2].substitution");
        assertThat(errors.get(0).message()).isEqualTo("Choose a reason and confirm the patient agrees.");
    }

    @Test
    void aReasonThatEndsInOtherNeedsANoteOfFourCharactersAndNoNoteIsOverTwoHundred() {
        CartLineInput shortNote = new CartLineInput(P1, BigDecimal.ONE, null, null,
                new ReasonInput("allergy-override.other", "ok"), null, null);
        CartLineInput goodNote = new CartLineInput(P1, BigDecimal.ONE, null, null,
                new ReasonInput("allergy-override.other", "Prescriber phoned"), null, null);
        CartLineInput longNote = new CartLineInput(P1, BigDecimal.ONE, null, null, null,
                new ReasonInput("h1-override.confirmed", "x".repeat(201)), null);

        assertThat(fields(check(new CartInput(walkIn(), List.of(shortNote, goodNote, longNote), null, null, null, null))))
                .containsExactly("lines[0].allergyOverride", "lines[2].h1Override");
    }

    @Test
    void theBuyerIsCheckedByItsKind() {
        assertThat(fields(check(new CartInput(null, List.of(line(P1, 1)), null, null, null, null)))).containsExactly("buyer.kind");
        assertThat(fields(check(new CartInput(new BuyerInput("PATIENT", null, null, null, null, false, false),
                List.of(line(P1, 1)), null, null, null, null)))).containsExactly("buyer.patientId");
        assertThat(fields(check(new CartInput(new BuyerInput("CUSTOMER", null, null, null, null, false, false),
                List.of(line(P1, 1)), null, null, null, null)))).containsExactly("buyer.customerId");
        assertThat(fields(check(new CartInput(new BuyerInput("WALK_IN", null, null, " ", "123", false, false),
                List.of(line(P1, 1)), null, null, null, null)))).containsExactly("buyer.walkInName", "buyer.walkInPhone");
        assertThat(check(new CartInput(new BuyerInput("NONE", null, null, null, null, false, false), List.of(line(P1, 1)), null,
                null, null, null))).isEmpty();
        assertThat(fields(check(new CartInput(new BuyerInput("CUSTOMER", null, UUID.randomUUID(), null, null, false, true),
                List.of(line(P1, 1)), null, null, null, null)))).containsExactly("buyer.linkPending");
    }

    @Test
    void theDiscountPercentIsZeroToHundredWithAtMostTwoDecimals() {
        for (String bad : new String[] {"-1", "100.01", "abc", "5.555"}) {
            assertThat(fields(check(new CartInput(walkIn(), List.of(line(P1, 1)), bad, null, null, null))))
                    .as(bad).containsExactly("discountPercent");
        }
        for (String good : new String[] {null, "", "0", "100", "12.5"}) {
            assertThat(check(new CartInput(walkIn(), List.of(line(P1, 1)), good, null, null, null))).as(String.valueOf(good))
                    .isEmpty();
        }
    }

    @Test
    void thePrescriptionCardIsCheckedAndAFutureDateIsRefused() {
        PrescriptionInput card = new PrescriptionInput("TYPED", "Dr Test One", "MH-12345", TODAY.plusDays(1), "Test Walk-in One",
                new BigDecimal("131"), "Test Lane 1");
        PrescriptionInput badSource = new PrescriptionInput("PAPER", null, null, null, null, null, null);

        assertThat(fields(check(new CartInput(walkIn(), List.of(line(P1, 1)), null, null, card, null))))
                .containsExactly("prescription.prescriptionDate", "prescription.patientAge");
        assertThat(fields(check(new CartInput(walkIn(), List.of(line(P1, 1)), null, null, badSource, null))))
                .containsExactly("prescription.source");
    }

    @Test
    void noErrorEchoesWhatWasTyped() {
        CartInput cart = new CartInput(new BuyerInput("WALK_IN", null, null, "Secret Name", "12345", false, false),
                List.of(new CartLineInput(P1, new BigDecimal("0.5"), null, null, null, null, null)), "zzz-typed", null, null, null);

        assertThat(check(cart)).extracting(FieldMessage::message).noneMatch(m -> m.contains("Secret") || m.contains("zzz")
                || m.contains("12345"));
    }

    @Test
    void anEPrescriptionOrAnRxLineMakesTheCartWantTheOpdPort() {
        List<FieldMessage> errors = new ArrayList<>();
        SaleCart.Cart withRx = SaleCart.validate(new CartInput(walkIn(), List.of(new CartLineInput(P1, BigDecimal.ONE, null, null,
                null, null, "line-1")), null, null, null, null), TODAY, ACTIVE, errors);
        SaleCart.Cart plain = SaleCart.validate(new CartInput(walkIn(), List.of(line(P1, 1)), null, null, null, null), TODAY,
                ACTIVE, errors);

        assertThat(withRx.wantsRx()).isTrue();
        assertThat(plain.wantsRx()).isFalse();
    }
}
