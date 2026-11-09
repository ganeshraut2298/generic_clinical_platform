package com.sahaj.ipd.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.card.CardAccessRule;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * SAH-37 (code review finding 5): who may collect a deposit has one source, {@link CardAccessRule#DEPOSIT}, which the
 * service and the card's Deposit action use. The endpoint's annotation needs a constant string, so this test keeps that
 * second form equal to the source: a drift would show a Deposit button the endpoint refuses.
 */
class DepositRolesParityTest {

    private static Set<String> rolesOf(String expression) {
        Set<String> roles = new TreeSet<>();
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(expression);
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }

    @Test
    void theEndpointAnnotationNamesExactlyTheRolesOfTheCardRule() {
        assertThat(rolesOf(AdmissionController.DEPOSIT_TAKERS)).isEqualTo(new TreeSet<>(CardAccessRule.DEPOSIT));
    }

    @Test
    void theCardRuleHoldsOwnerAndFrontDeskAndTheDepositFlagGoesToTheSameRoles() {
        assertThat(CardAccessRule.DEPOSIT).containsExactlyInAnyOrder("OWNER", "FRONT_DESK");
        assertThat(CardAccessRule.DEPOSIT_FLAG).isEqualTo(CardAccessRule.DEPOSIT);
    }
}
