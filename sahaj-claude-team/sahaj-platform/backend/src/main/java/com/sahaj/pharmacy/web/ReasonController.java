package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyMasterInputs;
import com.sahaj.pharmacy.application.PharmacyMasterViews;
import com.sahaj.pharmacy.application.ReasonService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-78-10 (docs/api/pharmacy.yaml): the reason lists. Every write answers the whole set of lists. */
@RestController
@RequestMapping("/api/v1/pharmacy/reason-lists")
public class ReasonController implements PharmacyApi {

    private final ReasonService reasons;
    private final ObjectMapper mapper;

    public ReasonController(ReasonService reasons, ObjectMapper mapper) {
        this.reasons = reasons;
        this.mapper = mapper;
    }

    @GetMapping
    public PharmacyMasterViews.ReasonLists list() {
        ClinicUser user = ClinicUser.current();
        return reasons.list(user.tenantId(), user.userId());
    }

    @PostMapping(value = "/{listKey}/reasons", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PharmacyMasterViews.ReasonLists> add(@PathVariable String listKey,
                                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return ResponseEntity.status(HttpStatus.CREATED).body(reasons.add(user.tenantId(), user.userId(), listKey,
                PharmacyBodies.of(mapper, body, PharmacyMasterInputs.ReasonAdd.class)));
    }

    @PostMapping("/{listKey}/reasons/{reasonKey}/switch-off")
    public PharmacyMasterViews.ReasonLists switchOff(@PathVariable String listKey, @PathVariable String reasonKey) {
        ClinicUser user = ClinicUser.current();
        return reasons.switchOff(user.tenantId(), user.userId(), listKey, reasonKey);
    }

    @PostMapping("/{listKey}/reasons/{reasonKey}/switch-on")
    public PharmacyMasterViews.ReasonLists switchOn(@PathVariable String listKey, @PathVariable String reasonKey) {
        ClinicUser user = ClinicUser.current();
        return reasons.switchOn(user.tenantId(), user.userId(), listKey, reasonKey);
    }
}
