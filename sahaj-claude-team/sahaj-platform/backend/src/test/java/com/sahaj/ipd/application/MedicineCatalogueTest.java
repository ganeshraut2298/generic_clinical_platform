package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.SeedCatalogue;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** SAH-31: the catalogue is read once, on first use or at start-up, and fails fast when it cannot be used. */
class MedicineCatalogueTest {

    @Test
    void theCatalogueIsReadOnceAndSharedByEveryCaller() {
        DrugCatalogueRepository repository = mock(DrugCatalogueRepository.class);
        when(repository.findDrugs()).thenReturn(SeedCatalogue.drugs());
        when(repository.findInteractions()).thenReturn(SeedCatalogue.interactions());
        MedicineCatalogue catalogue = new MedicineCatalogue(repository);

        assertThat(catalogue.catalogue().drugs()).hasSize(14);
        assertThat(catalogue.catalogue()).isSameAs(catalogue.catalogue());
        assertThat(catalogue.safety()).isNotNull();
        verify(repository, times(1)).findDrugs();
        verify(repository, times(1)).findInteractions();
    }

    @Test
    void theStartUpCheckLoadsTheCatalogue() {
        DrugCatalogueRepository repository = mock(DrugCatalogueRepository.class);
        when(repository.findDrugs()).thenReturn(SeedCatalogue.drugs());
        when(repository.findInteractions()).thenReturn(SeedCatalogue.interactions());

        new MedicineCatalogueStartupCheck(new MedicineCatalogue(repository)).run(new DefaultApplicationArguments());

        verify(repository, times(1)).findDrugs();
    }

    @Test
    void aCatalogueWithoutTheClassesTheCodeReliesOnStopsTheStartUp() {
        DrugCatalogueRepository repository = mock(DrugCatalogueRepository.class);
        List<Drug> noPenicillin = new ArrayList<>();
        for (Drug d : SeedCatalogue.drugs()) {
            noPenicillin.add(new Drug(d.key(), d.name(), d.route(), Set.of(), d.highAlert()));
        }
        when(repository.findDrugs()).thenReturn(noPenicillin);
        when(repository.findInteractions()).thenReturn(List.of());

        assertThatThrownBy(() -> new MedicineCatalogueStartupCheck(new MedicineCatalogue(repository))
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDatabaseThatCannotBeReachedAtStartUpIsNotJudgedThereAndIsReadAgainOnFirstUse() {
        DrugCatalogueRepository repository = mock(DrugCatalogueRepository.class);
        when(repository.findDrugs()).thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("down"))
                .thenReturn(SeedCatalogue.drugs());
        when(repository.findInteractions()).thenReturn(SeedCatalogue.interactions());
        MedicineCatalogue catalogue = new MedicineCatalogue(repository);

        new MedicineCatalogueStartupCheck(catalogue).run(new DefaultApplicationArguments());

        assertThat(catalogue.catalogue().drugs()).hasSize(14);
    }

    @Test
    void anEmptyCatalogueStopsTheStartUp() {
        DrugCatalogueRepository repository = mock(DrugCatalogueRepository.class);
        when(repository.findDrugs()).thenReturn(List.of());

        assertThatThrownBy(() -> new MedicineCatalogue(repository).catalogue())
                .isInstanceOf(IllegalStateException.class);
    }
}
