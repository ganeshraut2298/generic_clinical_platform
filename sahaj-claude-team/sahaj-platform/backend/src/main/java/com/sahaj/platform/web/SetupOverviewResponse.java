package com.sahaj.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.platform.application.SetupOverviewService.Check;
import com.sahaj.platform.application.SetupOverviewService.Overview;
import java.util.List;

/** {@code SetupOverview} in {@code docs/api/platform.yaml} (SAH-7): percent, checklist and the latest change. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetupOverviewResponse(int percent, List<CheckResponse> checks, AuditEventResponse lastChange) {

    public record CheckResponse(String key, String label, String why, boolean done, boolean optional) {

        static CheckResponse from(Check check) {
            return new CheckResponse(check.key(), check.label(), check.why(), check.done(), check.optional());
        }
    }

    public static SetupOverviewResponse from(Overview overview) {
        return new SetupOverviewResponse(overview.percent(),
                overview.checks().stream().map(CheckResponse::from).toList(),
                overview.lastChange() == null ? null : AuditEventResponse.from(overview.lastChange()));
    }
}
