package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.ComplianceService;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.RoleReadoutService;
import com.sahaj.pharmacy.application.SaleInputs;
import com.sahaj.pharmacy.application.SaleViews;
import com.sahaj.pharmacy.application.SalesReturnViews;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-80-19 (docs/api/pharmacy-sales.yaml): licences and renewals, the pharmacist registrations, the role read-out and the audit-log
 * feed. None of them works inside one store, except that the feed reads the stores the caller can reach (rule 15, SCOPED).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class ComplianceController implements PharmacyApi {

    private final RoleReadoutService roleReadout;
    private final ComplianceService compliance;
    private final ObjectMapper mapper;

    public ComplianceController(RoleReadoutService roleReadout, ComplianceService compliance, ObjectMapper mapper) {
        this.roleReadout = roleReadout;
        this.compliance = compliance;
        this.mapper = mapper;
    }

    @GetMapping("/compliance/role-readout")
    public SaleViews.RoleReadout roleReadout() {
        ClinicUser user = ClinicUser.current();
        return roleReadout.get(user.tenantId(), user.userId());
    }

    @GetMapping("/compliance/licences")
    public SalesReturnViews.Licences licences() {
        ClinicUser user = ClinicUser.current();
        return compliance.licences(user.tenantId(), user.userId());
    }

    @PostMapping(value = "/compliance/pharmacist-registrations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SalesReturnViews.PharmacistRegistration> addRegistration(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<SalesReturnViews.PharmacistRegistration> outcome = compliance.add(user.tenantId(), user.userId(),
                PharmacyBodies.of(mapper, body, SaleInputs.PharmacistRegistrationCreate.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @PutMapping(value = "/compliance/pharmacist-registrations/{registrationId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SalesReturnViews.PharmacistRegistration updateRegistration(@PathVariable String registrationId,
                                                                      @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return compliance.update(user.tenantId(), user.userId(), registrationId,
                PharmacyBodies.of(mapper, body, SaleInputs.PharmacistRegistrationUpdate.class));
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @GetMapping("/activity")
    public SalesReturnViews.ActivityPage activity(@RequestParam(name = "storeId", required = false) String storeId,
                                                  @RequestParam(name = "from", required = false) String from,
                                                  @RequestParam(name = "to", required = false) String to,
                                                  @RequestParam(name = "membershipId", required = false) String membershipId,
                                                  @RequestParam(name = "type", required = false) String type,
                                                  @RequestParam(name = "page", required = false) String page,
                                                  @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return compliance.activity(user.tenantId(), user.userId(), storeId, from, to, membershipId, type, page, size);
    }
}
