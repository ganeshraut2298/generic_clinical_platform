package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sahaj.ipd.application.AdmissionViews.AdmittedClinicalFlags;
import com.sahaj.ipd.application.OrderNoticeViews.NoticeCount;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-76-3 the card badge "n to acknowledge" on the clinical flags of an admitted patient (ADR-027 D5). */
class AdmittedCardsOrdersTest {

    private static final Instant AS_OF = Instant.parse("2026-11-11T06:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static AdmittedClinicalFlags flags() {
        return AdmittedCards.flags(null, null, new PatientClinical(UUID.randomUUID(), "UH-0001", "Meena Patil", 41, "F",
                null), AS_OF);
    }

    @Test
    void theFlagsStartWithNothingToAcknowledge() {
        assertThat(flags().ordersToAcknowledge()).isEqualTo(NoticeCount.NONE);
    }

    @Test
    void theBadgeIsSetWithoutTouchingTheOtherFlags() {
        AdmittedClinicalFlags withBadge = flags().withOrdersToAcknowledge(new NoticeCount(3, 1, 2)).withRoundDone(true);

        assertThat(withBadge.ordersToAcknowledge()).isEqualTo(new NoticeCount(3, 1, 2));
        assertThat(withBadge.roundDoneToday()).isTrue();
        assertThat(withBadge.vitalsDue()).isTrue();
        assertThat(withBadge.allergy().status().name()).isEqualTo("NOT_RECORDED");
    }

    @Test
    void theJsonCarriesTheThreeNumbersUnderTheContractName() throws Exception {
        JsonNode node = JSON.readTree(JSON.writeValueAsString(flags().withOrdersToAcknowledge(new NoticeCount(2, 1, 0))));

        assertThat(node.get("ordersToAcknowledge").get("count").asInt()).isEqualTo(2);
        assertThat(node.get("ordersToAcknowledge").get("stat").asInt()).isEqualTo(1);
        assertThat(node.get("ordersToAcknowledge").get("overdue").asInt()).isZero();
        assertThat(flags().toString()).doesNotContain("Meena");
    }
}
