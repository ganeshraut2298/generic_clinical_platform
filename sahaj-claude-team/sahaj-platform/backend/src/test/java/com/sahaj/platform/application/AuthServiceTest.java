package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.domain.AppUser;
import com.sahaj.platform.domain.AuthMode;
import com.sahaj.platform.domain.Membership;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private static final String MOBILE = "9123457410";

    private OtpService otpService;
    private AuthTransactionalOps ops;
    private TokenService tokenService;
    private AuthService service;

    @BeforeEach
    void setUp() {
        otpService = mock(OtpService.class);
        ops = mock(AuthTransactionalOps.class);
        tokenService = mock(TokenService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
        AuthenticationScope passThroughScope = new AuthenticationScope() {
            @Override
            public <T> T runAsTenant(UUID userId, UUID tenantId, Supplier<T> action) {
                return action.get();
            }

            @Override
            public <T> T runAsVerifiedUser(UUID userId, Supplier<T> action) {
                return action.get();
            }

            @Override
            public <T> T runAsTenantOnly(UUID tenantId, Supplier<T> action) {
                return action.get();
            }
        };
        service = new AuthService(otpService, ops, passThroughScope, tokenService, clock, new SecureRandom());
    }

    @Test
    void loginWithAnUnregisteredMobileIsNotRegisteredNotAnError() {
        when(ops.findUserByMobile(MOBILE)).thenReturn(Optional.empty());

        VerifyOutcome outcome = service.verify(MOBILE, "123456", AuthMode.LOGIN, null);

        assertThat(outcome.next()).isEqualTo(VerifyOutcome.Next.NOT_REGISTERED);
        verify(ops, never()).createTenantAndOwner(any(), any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void signUpWithoutConsentIsRejected() {
        when(ops.findUserByMobile(MOBILE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(MOBILE, "123456", AuthMode.SIGNUP, null))
                .isInstanceOf(ConsentRequiredException.class);
    }

    @Test
    void signUpWithConsentCreatesATenantAndOpensSetup() {
        when(ops.findUserByMobile(MOBILE)).thenReturn(Optional.empty());
        when(tokenService.issueAccessToken(any(), any(), eq("OWNER")))
                .thenReturn(new TokenService.AccessToken("jwt", 900));

        VerifyOutcome outcome = service.verify(MOBILE, "123456", AuthMode.SIGNUP,
                new AuthService.Consent("v0.1", "v0.1", "127.0.0.1"));

        assertThat(outcome.next()).isEqualTo(VerifyOutcome.Next.SETUP);
        assertThat(outcome.accessToken().token()).isEqualTo("jwt");
        assertThat(outcome.rawRefreshToken()).isNotBlank();
        verify(ops).createTenantAndOwner(any(), any(), eq(MOBILE), eq("v0.1"), eq("v0.1"), eq("127.0.0.1"), anyString(), any());
    }

    @Test
    void anExistingUserWithOneActiveMembershipLogsStraightIn() {
        AppUser user = new AppUser(UUID.randomUUID(), MOBILE, "Owner");
        UUID tenantId = UUID.randomUUID();
        Membership membership = new Membership(UUID.randomUUID(), tenantId, user.id(), MembershipRole.OWNER, MembershipStatus.ACTIVE);
        when(ops.findUserByMobile(MOBILE)).thenReturn(Optional.of(user));
        when(ops.listMemberships(user.id())).thenReturn(List.of(membership));
        when(ops.isSetupComplete(tenantId)).thenReturn(true);
        when(tokenService.issueAccessToken(user.id(), tenantId, "OWNER"))
                .thenReturn(new TokenService.AccessToken("jwt", 900));

        VerifyOutcome outcome = service.verify(MOBILE, "123456", AuthMode.LOGIN, null);

        assertThat(outcome.next()).isEqualTo(VerifyOutcome.Next.APP);
        verify(ops).finalizeLogin(eq(tenantId), eq(user.id()), eq(membership.id()), eq(MOBILE), anyString(), any());
    }

    @Test
    void anOwnerWhoHasNotFinishedSetupReturnsToSetupInsteadOfTheApp() {
        AppUser user = new AppUser(UUID.randomUUID(), MOBILE, "Owner");
        UUID tenantId = UUID.randomUUID();
        Membership membership = new Membership(UUID.randomUUID(), tenantId, user.id(), MembershipRole.OWNER, MembershipStatus.ACTIVE);
        when(ops.findUserByMobile(MOBILE)).thenReturn(Optional.of(user));
        when(ops.listMemberships(user.id())).thenReturn(List.of(membership));
        when(ops.isSetupComplete(tenantId)).thenReturn(false);
        when(tokenService.issueAccessToken(user.id(), tenantId, "OWNER"))
                .thenReturn(new TokenService.AccessToken("jwt", 900));

        VerifyOutcome outcome = service.verify(MOBILE, "123456", AuthMode.LOGIN, null);

        assertThat(outcome.next()).isEqualTo(VerifyOutcome.Next.SETUP);
    }

    @Test
    void aNonOwnerLogsStraightInWithoutCheckingSetup() {
        AppUser user = new AppUser(UUID.randomUUID(), MOBILE, "Nurse");
        UUID tenantId = UUID.randomUUID();
        Membership membership = new Membership(UUID.randomUUID(), tenantId, user.id(), MembershipRole.NURSE, MembershipStatus.ACTIVE);
        when(ops.findUserByMobile(MOBILE)).thenReturn(Optional.of(user));
        when(ops.listMemberships(user.id())).thenReturn(List.of(membership));
        when(tokenService.issueAccessToken(user.id(), tenantId, "NURSE"))
                .thenReturn(new TokenService.AccessToken("jwt", 900));

        VerifyOutcome outcome = service.verify(MOBILE, "123456", AuthMode.LOGIN, null);

        assertThat(outcome.next()).isEqualTo(VerifyOutcome.Next.APP);
        verify(ops, never()).isSetupComplete(any());
    }

    @Test
    void anExistingUserWithTwoActiveMembershipsMustChooseAClinic() {
        AppUser user = new AppUser(UUID.randomUUID(), MOBILE, "Owner");
        Membership first = new Membership(UUID.randomUUID(), UUID.randomUUID(), user.id(), MembershipRole.OWNER, MembershipStatus.ACTIVE);
        Membership second = new Membership(UUID.randomUUID(), UUID.randomUUID(), user.id(), MembershipRole.DOCTOR, MembershipStatus.ACTIVE);
        when(ops.findUserByMobile(MOBILE)).thenReturn(Optional.of(user));
        when(ops.listMemberships(user.id())).thenReturn(List.of(first, second));
        when(ops.tenantName(any())).thenReturn(Optional.of("A clinic"));
        when(tokenService.issueSelectionToken(user.id())).thenReturn("selection-token");

        VerifyOutcome outcome = service.verify(MOBILE, "123456", AuthMode.LOGIN, null);

        assertThat(outcome.next()).isEqualTo(VerifyOutcome.Next.CHOOSE_CLINIC);
        assertThat(outcome.selectionToken()).isEqualTo("selection-token");
        assertThat(outcome.clinics()).hasSize(2);
        verify(ops, never()).finalizeLogin(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aUserWhoseOnlyMembershipsAreRemovedIsRefused() {
        AppUser user = new AppUser(UUID.randomUUID(), MOBILE, "Owner");
        Membership removed = new Membership(UUID.randomUUID(), UUID.randomUUID(), user.id(), MembershipRole.FRONT_DESK, MembershipStatus.REMOVED);
        when(ops.findUserByMobile(MOBILE)).thenReturn(Optional.of(user));
        when(ops.listMemberships(user.id())).thenReturn(List.of(removed));

        assertThatThrownBy(() -> service.verify(MOBILE, "123456", AuthMode.LOGIN, null))
                .isInstanceOf(AccessRefusedException.class);
    }

    @Test
    void selectClinicWithAnInvalidTokenIsRejected() {
        when(tokenService.parseSelectionToken("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.selectClinic("bad", UUID.randomUUID()))
                .isInstanceOf(SelectionInvalidException.class);
    }

    @Test
    void selectClinicRequiresAnActiveMembershipForThatTenant() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(tokenService.parseSelectionToken("tok")).thenReturn(Optional.of(userId));
        when(ops.listMemberships(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.selectClinic("tok", tenantId)).isInstanceOf(AccessRefusedException.class);
    }

    @Test
    void refreshWithAMalformedCookieIsRejected() {
        assertThatThrownBy(() -> service.refresh("not-a-tenant-prefixed-token"))
                .isInstanceOf(RefreshInvalidException.class);
        assertThatThrownBy(() -> service.refresh(null)).isInstanceOf(RefreshInvalidException.class);
    }

    @Test
    void refreshRotatesAndIssuesAFreshAccessToken() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String raw = tenantId + ".some-secret";
        when(ops.rotateRefreshToken(eq(tenantId), anyString(), anyString(), any()))
                .thenReturn(new AuthTransactionalOps.RefreshRotationResult(userId, tenantId, MembershipRole.OWNER));
        when(tokenService.issueAccessToken(userId, tenantId, "OWNER")).thenReturn(new TokenService.AccessToken("jwt2", 900));

        AuthService.RefreshOutcome outcome = service.refresh(raw);

        assertThat(outcome.accessToken().token()).isEqualTo("jwt2");
        assertThat(outcome.rawRefreshToken()).startsWith(tenantId.toString() + ".");
    }

    @Test
    void logoutRevokesTheOneToken() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(ops.findUserById(userId)).thenReturn(Optional.of(new AppUser(userId, MOBILE, "Owner")));
        doNothing().when(ops).revokeOneToken(any(), any(), any(), any());

        service.logout(tenantId, userId, tenantId + ".raw-token");

        verify(ops).revokeOneToken(eq(tenantId), eq(userId), anyString(), eq(MOBILE));
    }

    @Test
    void logoutAllRevokesEveryTokenAndRecordsOneAudit() {
        UUID userId = UUID.randomUUID();

        service.logoutAll(userId);

        verify(ops).revokeAllForUserGlobally(userId);
        verify(ops).recordAudit(eq("SIGN_OUT_ALL"), eq("APP_USER"), eq(userId), eq(null));
    }
}
