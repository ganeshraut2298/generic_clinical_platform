package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.ProductSearchService;
import com.sahaj.pharmacy.application.ProductService;
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
 * SAH-78-5 and 78-6 (docs/api/pharmacy.yaml): the product master. None of these handlers works inside a store, so none
 * carries a store marker. Path ids, query values and bodies are taken as text and checked by the services after the module
 * and access-level checks (module off 403, access level 403, then 400, then 404).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class ProductController implements PharmacyApi {

    private final ProductSearchService search;
    private final ProductService products;
    private final ObjectMapper mapper;

    public ProductController(ProductSearchService search, ProductService products, ObjectMapper mapper) {
        this.search = search;
        this.products = products;
        this.mapper = mapper;
    }

    @GetMapping("/products")
    public PharmacyViews.ProductPage list(@RequestParam(name = "q", required = false) String q,
                                          @RequestParam(name = "source", required = false) String source,
                                          @RequestParam(name = "page", required = false) String page,
                                          @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return search.search(user.tenantId(), user.userId(), q, source, page, size);
    }

    @PostMapping(value = "/products", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PharmacyViews.ProductDetail> create(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return answer(products.create(user.tenantId(), user.userId(),
                PharmacyBodies.of(mapper, body, PharmacyInputs.OwnProduct.class)));
    }

    @GetMapping("/products/duplicates")
    public PharmacyViews.Duplicates duplicates(@RequestParam(name = "salt", required = false) String salt,
                                               @RequestParam(name = "strength", required = false) String strength,
                                               @RequestParam(name = "form", required = false) String form,
                                               @RequestParam(name = "excludeProductId", required = false) String exclude) {
        ClinicUser user = ClinicUser.current();
        return search.duplicates(user.tenantId(), user.userId(), salt, strength, form, exclude);
    }

    @GetMapping("/product-options")
    public PharmacyViews.ProductOptions options() {
        ClinicUser user = ClinicUser.current();
        return search.options(user.tenantId(), user.userId());
    }

    @PostMapping("/catalogue-products/{catalogueProductId}/adopt")
    public ResponseEntity<PharmacyViews.ProductDetail> adopt(@PathVariable String catalogueProductId) {
        ClinicUser user = ClinicUser.current();
        return answer(products.adopt(user.tenantId(), user.userId(), catalogueProductId));
    }

    @GetMapping("/products/{productId}")
    public PharmacyViews.ProductDetail get(@PathVariable String productId) {
        ClinicUser user = ClinicUser.current();
        return search.get(user.tenantId(), user.userId(), productId);
    }

    @PutMapping(value = "/products/{productId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.ProductDetail update(@PathVariable String productId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return products.update(user.tenantId(), user.userId(), productId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.OwnProductChange.class));
    }

    @PutMapping(value = "/products/{productId}/local-settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.ProductDetail localSettings(@PathVariable String productId,
                                                     @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return products.saveLocalSettings(user.tenantId(), user.userId(), productId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.LocalSettings.class));
    }

    /** 201 when the call created the record, 200 when it found it already there (nothing written). */
    private static ResponseEntity<PharmacyViews.ProductDetail> answer(PharmacyViews.Outcome<PharmacyViews.ProductDetail> outcome) {
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }
}
