package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.SaleInputs;
import com.sahaj.pharmacy.application.SaleViews;
import com.sahaj.pharmacy.application.StoreBillSeriesService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-80-1 (docs/api/pharmacy-sales.yaml): the bill series of each selling store. Both operations are masters (SETUP_VIEW and
 * SETUP_EDIT). The path id and the body are taken as text and read by the service after its gate, as for every pharmacy master.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class StoreBillSeriesController implements PharmacyApi {

    private final StoreBillSeriesService series;
    private final ObjectMapper mapper;

    public StoreBillSeriesController(StoreBillSeriesService series, ObjectMapper mapper) {
        this.series = series;
        this.mapper = mapper;
    }

    @GetMapping("/document-series/bill-stores")
    public SaleViews.StoreBillSeriesList list() {
        ClinicUser user = ClinicUser.current();
        return series.list(user.tenantId(), user.userId());
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @PutMapping(value = "/stores/{storeId}/bill-series", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaleViews.StoreBillSeries put(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return series.put(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, SaleInputs.StoreBillSeriesInput.class));
    }
}
