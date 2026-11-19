package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.UnitService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-78-2 (docs/api/pharmacy.yaml): unit settings. FEFO and the negative-stock block are facts with no field to set them.
 * The body is taken as raw text and read by the service after its gate ({@link PharmacyBodies}).
 */
@RestController
@RequestMapping("/api/v1/pharmacy/unit-settings")
public class UnitSettingsController implements PharmacyApi {

    private final UnitService units;
    private final ObjectMapper mapper;

    public UnitSettingsController(UnitService units, ObjectMapper mapper) {
        this.units = units;
        this.mapper = mapper;
    }

    @GetMapping
    public PharmacyViews.UnitSettings get() {
        ClinicUser user = ClinicUser.current();
        return units.get(user.tenantId(), user.userId());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.UnitSettings put(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return units.save(user.tenantId(), user.userId(), PharmacyBodies.of(mapper, body, PharmacyInputs.UnitSettings.class));
    }
}
