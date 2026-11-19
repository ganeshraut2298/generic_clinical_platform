package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.ApprovalRulesService;
import com.sahaj.pharmacy.application.PharmacyMasterInputs;
import com.sahaj.pharmacy.application.PharmacyMasterViews;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-78-9 (docs/api/pharmacy.yaml): the approval rules, the presets and the saved versions. None of these handlers works
 * inside a store. The body is read by the service after its gate; path and query values are checked there too.
 */
@RestController
@RequestMapping("/api/v1/pharmacy/approval-rules")
public class ApprovalRulesController implements PharmacyApi {

    private final ApprovalRulesService rules;
    private final ObjectMapper mapper;

    public ApprovalRulesController(ApprovalRulesService rules, ObjectMapper mapper) {
        this.rules = rules;
        this.mapper = mapper;
    }

    @GetMapping
    public PharmacyMasterViews.ApprovalRules get() {
        ClinicUser user = ClinicUser.current();
        return rules.get(user.tenantId(), user.userId());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyMasterViews.ApprovalRules put(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return rules.save(user.tenantId(), user.userId(),
                PharmacyBodies.of(mapper, body, PharmacyMasterInputs.RulesSave.class));
    }

    @PostMapping(value = "/preset", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyMasterViews.ApprovalRules preset(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return rules.applyPreset(user.tenantId(), user.userId(),
                PharmacyBodies.of(mapper, body, PharmacyMasterInputs.PresetApply.class));
    }

    @GetMapping("/versions")
    public PharmacyMasterViews.RuleVersionPage versions(@RequestParam(name = "page", required = false) String page,
                                                        @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return rules.versions(user.tenantId(), user.userId(), page, size);
    }

    @GetMapping("/versions/{versionNo}")
    public PharmacyMasterViews.ApprovalRules version(@PathVariable String versionNo) {
        ClinicUser user = ClinicUser.current();
        return rules.version(user.tenantId(), user.userId(), versionNo);
    }
}
