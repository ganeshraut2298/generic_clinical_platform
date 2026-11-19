package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.NumberingService;
import com.sahaj.pharmacy.application.PharmacyMasterInputs;
import com.sahaj.pharmacy.application.PharmacyMasterViews;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-78-10 (docs/api/pharmacy.yaml): the numbering series. The body is read by the service after its gate. */
@RestController
@RequestMapping("/api/v1/pharmacy/document-series")
public class NumberingController implements PharmacyApi {

    private final NumberingService numbering;
    private final ObjectMapper mapper;

    public NumberingController(NumberingService numbering, ObjectMapper mapper) {
        this.numbering = numbering;
        this.mapper = mapper;
    }

    @GetMapping
    public PharmacyMasterViews.DocumentSeriesList list() {
        ClinicUser user = ClinicUser.current();
        return numbering.list(user.tenantId(), user.userId());
    }

    @PutMapping(value = "/{docType}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyMasterViews.DocumentSeries put(@PathVariable String docType,
                                                  @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return numbering.save(user.tenantId(), user.userId(), docType,
                PharmacyBodies.of(mapper, body, PharmacyMasterInputs.SeriesSave.class));
    }
}
