package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.DischargeRepository.SignoffRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-27 small rules: default template, going home today, the follow-up reason cut and the outcome word. */
class DischargeRulesTest {

    private static SignoffRow signedAt(String instant) {
        return new SignoffRow(UUID.randomUUID(), UUID.randomUUID(), "normal", "general", UUID.randomUUID(),
                Instant.parse(instant));
    }

    @Test
    void theFixedListsAreInTheMockupOrder() {
        assertThat(DischargeRules.TYPES).extracting(t -> t.key() + "=" + t.label()).containsExactly("normal=Normal",
                "lama=On request (LAMA)", "referred=Referred", "death=Death");
        assertThat(DischargeRules.TEMPLATES).extracting(t -> t.key() + "=" + t.label()).containsExactly(
                "general=General medicine", "surgical=Surgical", "maternity=Maternity", "daycare=Day-care");
    }

    @Test
    void theDefaultTemplateIsTheSetupChoiceThenTheAdmissionTypeThenAPackageThenGeneral() {
        assertThat(DischargeRules.defaultTemplate("surgical", "maternity", false)).isEqualTo("surgical");
        assertThat(DischargeRules.defaultTemplate(null, "maternity", false)).isEqualTo("maternity");
        assertThat(DischargeRules.defaultTemplate(null, "daycare", false)).isEqualTo("daycare");
        assertThat(DischargeRules.defaultTemplate(null, "planned", false)).isEqualTo("general");
        assertThat(DischargeRules.defaultTemplate(null, "emergency", false)).isEqualTo("general");
        // SAH-29 (mockup ipdis): surgical for any other admission with a package.
        assertThat(DischargeRules.defaultTemplate(null, "planned", true)).isEqualTo("surgical");
        assertThat(DischargeRules.defaultTemplate(null, "emergency", true)).isEqualTo("surgical");
        assertThat(DischargeRules.defaultTemplate(null, "maternity", true)).isEqualTo("maternity");
        assertThat(DischargeRules.defaultTemplate(null, "daycare", true)).isEqualTo("daycare");
        assertThat(DischargeRules.defaultTemplate("general", "planned", true)).isEqualTo("general");
    }

    @Test
    void goingHomeTodayIsASignOffOnTodaysIstDate() {
        LocalDate today = LocalDate.of(2026, 11, 11);
        // 11 Nov 00:00:30 IST is 10 Nov 18:30:30 UTC: today in India, yesterday in UTC.
        assertThat(DischargeRules.goingHomeToday(signedAt("2026-11-10T18:30:30Z"), today)).isTrue();
        assertThat(DischargeRules.goingHomeToday(signedAt("2026-11-11T18:29:59Z"), today)).isTrue();
        // 12 Nov 00:00:00 IST is the next day.
        assertThat(DischargeRules.goingHomeToday(signedAt("2026-11-11T18:30:00Z"), today)).isFalse();
        // 10 Nov 23:59:59 IST is yesterday.
        assertThat(DischargeRules.goingHomeToday(signedAt("2026-11-10T18:29:59Z"), today)).isFalse();
        assertThat(DischargeRules.goingHomeToday(null, today)).isFalse();
    }

    @Test
    void theFollowUpReasonHasTheMockupPrefixAndIsCutTo200CodePoints() {
        assertThat(DischargeRules.followUpReason("Chest pain")).isEqualTo("Review after discharge · Chest pain");

        String prefix = "Review after discharge · ";
        String exactly = "a".repeat(200 - prefix.length());
        assertThat(DischargeRules.followUpReason(exactly)).hasSize(200).endsWith("a");
        assertThat(DischargeRules.followUpReason(exactly + "b")).hasSize(200).endsWith("a");

        // Emoji are two UTF-16 units each: the cut is by code point, so no half character is left.
        String cut = DischargeRules.followUpReason("😀".repeat(300));
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(200);
        assertThat(Character.isLowSurrogate(cut.charAt(cut.length() - 1))).isTrue();
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 2))).isTrue();
    }

    @Test
    void theOutcomeFollowsTheSignOfTheBalance() {
        assertThat(DischargeRules.outcome(1)).isEqualTo("collected");
        assertThat(DischargeRules.outcome(-1)).isEqualTo("refunded");
        assertThat(DischargeRules.outcome(0)).isEqualTo("settled");
    }
}
