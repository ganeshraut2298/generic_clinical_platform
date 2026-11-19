package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyMasterInputs;
import com.sahaj.pharmacy.application.PharmacyMasterViews;
import com.sahaj.pharmacy.application.PrintTemplateService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-78-10 (docs/api/pharmacy.yaml): print template settings. The body is read by the service after its gate. */
@RestController
@RequestMapping("/api/v1/pharmacy/print-templates")
public class PrintTemplateController implements PharmacyApi {

    private final PrintTemplateService templates;
    private final ObjectMapper mapper;

    public PrintTemplateController(PrintTemplateService templates, ObjectMapper mapper) {
        this.templates = templates;
        this.mapper = mapper;
    }

    @GetMapping
    public PharmacyMasterViews.PrintTemplateList list() {
        ClinicUser user = ClinicUser.current();
        return templates.list(user.tenantId(), user.userId());
    }

    @PutMapping(value = "/{docType}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyMasterViews.PrintTemplate put(@PathVariable String docType, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return templates.save(user.tenantId(), user.userId(), docType,
                PharmacyBodies.of(mapper, body, PharmacyMasterInputs.TemplateSave.class));
    }
}
