package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.StoreAccessDeniedException;
import com.sahaj.pharmacy.application.StoreDenialAuditor;
import com.sahaj.pharmacy.domain.StoreCapability;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** The handler answers the store denial after it has written the audit (code review N-1, security L7). */
class PharmacyStoreDenialHandlerTest {

    private final StoreDenialAuditor auditor = mock(StoreDenialAuditor.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<StoreDenialAuditor> provider = mock(ObjectProvider.class);
    private final PharmacyProblemHandler handler = new PharmacyProblemHandler(provider);
    private final StoreAccessDeniedException denied = new StoreAccessDeniedException(UUID.randomUUID(),
            StoreCapability.APPROVE, UUID.randomUUID());

    @Test
    void theDenialIsAuditedThenAnsweredAs403StorePermissionDenied() {
        when(provider.getObject()).thenReturn(auditor);

        ResponseEntity<ProblemDetail> response = handler.handleStoreDenied(denied);

        verify(auditor).record(denied);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getType().toString()).isEqualTo("https://sahaj.in/problems/store-permission-denied");
        assertThat(response.getBody().getTitle()).isEqualTo("Not allowed in this store");
        assertThat(response.getBody().getDetail()).isEqualTo("You cannot do this in this store.");
        assertThat(response.getBody().getProperties()).isNull();
    }

    @Test
    void ifTheAuditCannotBeWrittenThePersonIsStillRefusedAndNothingLeaksInTheAnswer() {
        when(provider.getObject()).thenReturn(auditor);
        doThrow(new QueryTimeoutException("db is slow for " + denied.storeId())).when(auditor).record(any());

        ResponseEntity<ProblemDetail> response = handler.handleStoreDenied(denied);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getDetail()).isEqualTo("You cannot do this in this store.");
        assertThat(response.getBody().toString()).doesNotContain(denied.storeId().toString());
    }
}
