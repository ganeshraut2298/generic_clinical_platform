package com.sahaj.ipd.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * SAH-31 (ADR-009 D3): loads the medicine catalogue when the application starts, so a catalogue without the classes
 * the safety check relies on stops the start instead of failing the first order. A catalogue that is wrong is a fault
 * and propagates; a database that cannot be reached at this moment is not judged here (the first use reads it again and
 * fails then), so a start-up without a database, as in the context tests, still works.
 */
@Component
class MedicineCatalogueStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MedicineCatalogueStartupCheck.class);

    private final MedicineCatalogue catalogue;

    MedicineCatalogueStartupCheck(MedicineCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            catalogue.catalogue();
        } catch (DataAccessException e) {
            log.warn("The medicine catalogue could not be read at start-up; it is read again on first use");
        }
    }
}
