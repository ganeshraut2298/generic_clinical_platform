package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.DrugCatalogue;
import com.sahaj.ipd.domain.MedicineSafetyCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SAH-31 the medicine catalogue, loaded once (ADR-009 D3): reference data changes only by a migration, which is a
 * deploy. Loaded on first use (or at start-up by {@link MedicineCatalogueStartupCheck}); the load fails fast when the
 * catalogue lacks a class the code relies on. Holds the one {@link MedicineSafetyCheck} built on it.
 */
@Component
class MedicineCatalogue {

    private final DrugCatalogueRepository repository;
    private volatile Loaded loaded;

    @Autowired
    MedicineCatalogue(DrugCatalogueRepository repository) {
        this.repository = repository;
    }

    /** A catalogue that is already loaded (unit tests). */
    MedicineCatalogue(DrugCatalogue fixed) {
        this.repository = null;
        this.loaded = new Loaded(fixed);
    }

    DrugCatalogue catalogue() {
        return current().catalogue;
    }

    MedicineSafetyCheck safety() {
        return current().safety;
    }

    private Loaded current() {
        Loaded now = loaded;
        if (now == null) {
            synchronized (this) {
                now = loaded;
                if (now == null) {
                    now = new Loaded(new DrugCatalogue(repository.findDrugs(), repository.findInteractions()));
                    loaded = now;
                }
            }
        }
        return now;
    }

    private static final class Loaded {
        private final DrugCatalogue catalogue;
        private final MedicineSafetyCheck safety;

        Loaded(DrugCatalogue catalogue) {
            this.catalogue = catalogue;
            this.safety = new MedicineSafetyCheck(catalogue);
        }
    }
}
