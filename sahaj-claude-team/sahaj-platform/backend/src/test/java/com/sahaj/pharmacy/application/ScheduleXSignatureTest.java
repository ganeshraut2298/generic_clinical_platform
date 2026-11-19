package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-029 P7: the second signature is checked before the transaction (pharmacist first, then owner) and rechecked inside it. */
class ScheduleXSignatureTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CALLER_USER = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();

    private final StaffPinPort pins = mock(StaffPinPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final ScheduleXSignature signature = new ScheduleXSignature(pins, team);

    @Test
    void aRightPinOfAPharmacistIsAcceptedWithoutTryingTheOwnerRole() {
        when(pins.checkColleague(TENANT, CALLER_USER, SIGNER, "PHARMACIST", "1234")).thenReturn(ColleaguePinCheck.ok(UUID.randomUUID()));

        assertThat(signature.verify(TENANT, CALLER_USER, SIGNER, "1234")).isEqualTo(SIGNER);

        verify(pins, never()).checkColleague(any(), any(), any(), eq("OWNER"), any());
    }

    @Test
    void anOwnerMayCountersignWhenThePersonIsNotAPharmacist() {
        when(pins.checkColleague(TENANT, CALLER_USER, SIGNER, "PHARMACIST", "1234")).thenReturn(ColleaguePinCheck.notEligible());
        when(pins.checkColleague(TENANT, CALLER_USER, SIGNER, "OWNER", "1234")).thenReturn(ColleaguePinCheck.ok(UUID.randomUUID()));

        assertThat(signature.verify(TENANT, CALLER_USER, SIGNER, "1234")).isEqualTo(SIGNER);
    }

    @Test
    void aPersonWhoIsNeitherIs403SecondSignerInvalidAndNoPinIsCounted() {
        when(pins.checkColleague(any(), any(), any(), any(), any())).thenReturn(ColleaguePinCheck.notEligible());

        assertThatThrownBy(() -> signature.verify(TENANT, CALLER_USER, SIGNER, "1234"))
                .isInstanceOfSatisfying(ForbiddenException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("second-signer-invalid");
                    assertThat(e.getMessage()).isEqualTo("The second signer must be another active pharmacist or owner.");
                });
    }

    @Test
    void aMissingSignerOrAPinThatIsNotFourDigitsIs400OnSecondSignerPinAndNoPinCheckIsMade() {
        for (String pin : new String[] {null, "", "123", "12345", "12a4", " 123", "１２３４", "1234\n"}) {
            assertThatThrownBy(() -> signature.verify(TENANT, CALLER_USER, SIGNER, pin))
                    .as("pin " + pin).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                            .extracting(f -> f.field() + ":" + f.message()).containsExactly("secondSigner.pin:Enter a 4-digit PIN."));
        }
        assertThatThrownBy(() -> signature.verify(TENANT, CALLER_USER, null, "1234"))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(InvalidFieldsException.FieldMessage::field).containsExactly("secondSigner.pin"));
        verify(pins, never()).checkColleague(any(), any(), any(), any(), any());
    }

    @Test
    void aWrongPinIsThePlatformsWrongPinProblemAndTheOwnerRoleIsNotTried() {
        when(pins.checkColleague(TENANT, CALLER_USER, SIGNER, "PHARMACIST", "0000"))
                .thenReturn(ColleaguePinCheck.failed(new PinCheck(PinCheck.Outcome.WRONG, 4, null, 0)));

        assertThatThrownBy(() -> signature.verify(TENANT, CALLER_USER, SIGNER, "0000")).isInstanceOf(StaffPinException.class);
        verify(pins, never()).checkColleague(any(), any(), any(), eq("OWNER"), any());
    }

    @Test
    void insideTheTransactionTheSignerMustStillBeActiveAPharmacistOrOwnerAndNotTheCaller() {
        when(team.find(TENANT, SIGNER)).thenReturn(Optional.of(new TeamMember(SIGNER, "Test Signer", "PHARMACIST", true, 0)));
        signature.recheck(TENANT, CALLER, SIGNER);

        when(team.find(TENANT, SIGNER)).thenReturn(Optional.of(new TeamMember(SIGNER, "Test Signer", "OWNER", true, 0)));
        signature.recheck(TENANT, CALLER, SIGNER);

        for (TeamMember bad : new TeamMember[] {new TeamMember(SIGNER, "Test Signer", "PHARMACIST", false, 0),
                new TeamMember(SIGNER, "Test Signer", "NURSE", true, 0), new TeamMember(SIGNER, "Test Signer", "STORE_STAFF", true, 0)}) {
            when(team.find(TENANT, SIGNER)).thenReturn(Optional.of(bad));
            assertThatThrownBy(() -> signature.recheck(TENANT, CALLER, SIGNER)).isInstanceOf(ForbiddenException.class);
        }
        when(team.find(TENANT, SIGNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> signature.recheck(TENANT, CALLER, SIGNER)).isInstanceOf(ForbiddenException.class);
        when(team.find(TENANT, CALLER)).thenReturn(Optional.of(new TeamMember(CALLER, "Test Caller", "PHARMACIST", true, 0)));
        assertThatThrownBy(() -> signature.recheck(TENANT, CALLER, CALLER)).as("the caller").isInstanceOf(ForbiddenException.class);
    }

    @Test
    void thePinIsNeverInTheSecondSignerInputsText() {
        PharmacyInputs.SecondSignerInput in = new PharmacyInputs.SecondSignerInput(SIGNER, "987654");

        assertThat(in.toString()).doesNotContain("987654").contains("***");
    }
}
