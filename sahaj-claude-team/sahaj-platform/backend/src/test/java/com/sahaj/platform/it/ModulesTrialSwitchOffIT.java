package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

/** SAH-19 / DEC-010: with {@code sahaj.modules.owner-ipd-trial=false} the trial endpoint is gone (own Spring context). */
@OnTestDatabase
@TestPropertySource(properties = "sahaj.modules.owner-ipd-trial=false")
class ModulesTrialSwitchOffIT extends SweepSupport {

    @Test
    void trialEndpointReturns404AndViewSaysNotAvailable() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.POST, ModulesIT.TRIAL, clinic.owner(), null), 404);
        JsonNode m = ok(HttpMethod.GET, ModulesIT.MODULES, clinic.owner(), null, 200);
        assertThat(m.get("ipdTrialAvailable").asBoolean()).isFalse();
        assertThat(m.get("ipd").get("state").asText()).isEqualTo("off");
    }

    @Test
    void requestPathStillWorksWhenTrialIsSwitchedOff() throws Exception {
        Clinic clinic = newClinic();
        assertThat(ok(HttpMethod.POST, ModulesIT.REQUEST, clinic.owner(), null, 200).get("ipd").get("requested").asBoolean()).isTrue();
    }

    @Test
    void nonOwnerStillGets403() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.POST, ModulesIT.TRIAL, clinic.as("NURSE"), null), 403);
    }
}
