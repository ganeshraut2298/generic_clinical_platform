import { formatDate, formatWhen } from '../config/config-format';
import { formatInrPaise } from '../../shared/format/inr';
import {
  DischargePreview,
  balanceLabel,
  dischargeButtonText,
  dischargeNotice,
  dischargeSteps,
  dischargeToast,
  followUpRuleHint,
  ESCALATION_OPEN_CLOSES,
  ESCALATION_OPEN_NORMAL,
  ESCALATION_OPEN_SIGNED_EARLIER,
  followUpText,
  goingHomeCount,
  goingHomeText,
  openEscalationWarning,
  payModeLabel,
  signatureApplies,
  signedByText,
  stayText,
} from './discharge.model';
import { AdmissionDetail } from './ipd.model';

const PREVIEW: DischargePreview = {
  diagnosis: 'Chest pain',
  stayDays: 3,
  bedCode: '101-A',
  doctorName: 'Dr. Meera Kulkarni',
  doctorShortName: 'Dr. Meera Kulkarni',
  doctorSpecialty: 'General medicine',
};

function discharged(partial: Partial<AdmissionDetail> = {}): AdmissionDetail {
  return {
    patientName: 'Asha Patil',
    bedCode: '101-A',
    discharge: {
      type: 'normal',
      templateKey: 'general',
      signedByName: 'Dr. Meera Kulkarni',
      signedAt: '2026-10-24T06:00:00Z',
      dischargedByName: 'Pooja Gaikwad',
      dischargedAt: '2026-10-24T07:00:00Z',
      followUpDays: 7,
      followUpDate: '2026-10-31',
    },
    finalBill: {
      lines: [],
      billDays: 3,
      totalPaise: 500000,
      depositsPaise: 350000,
      balancePaise: 150000,
      outcome: 'collected',
      mode: 'UPI',
      settledByName: 'Pooja Gaikwad',
      settledAt: '2026-10-24T07:00:00Z',
    },
    ...partial,
  } as AdmissionDetail;
}

describe('discharge model', () => {
  it('builds the three steps with the approval chain on and two with it off', () => {
    expect(dischargeSteps(true, false)).toEqual([
      { label: 'Doctor signs the summary', number: 1, state: 'cur' },
      { label: 'Billing clearance', number: 2, state: 'todo' },
      { label: 'Nursing handover', number: 3, state: 'todo' },
    ]);
    expect(dischargeSteps(false, false)).toEqual([
      { label: 'Summary signed', number: 1, state: 'cur' },
      { label: 'Final bill', number: 2, state: 'todo' },
    ]);
  });

  it('marks the first step done and the next current once signed', () => {
    expect(dischargeSteps(true, true).map((s) => s.state)).toEqual(['done', 'cur', 'todo']);
    expect(dischargeSteps(false, true).map((s) => s.state)).toEqual(['done', 'cur']);
  });

  it('applies a signature only to the same type and template', () => {
    const sig = { type: 'normal', templateKey: 'general', signedByName: 'Dr. M', signedAt: 'x' } as const;
    expect(signatureApplies(sig, 'normal', 'general')).toBe(true);
    expect(signatureApplies(sig, 'lama', 'general')).toBe(false);
    expect(signatureApplies(sig, 'normal', 'surgical')).toBe(false);
    expect(signatureApplies(null, 'normal', 'general')).toBe(false);
    expect(signatureApplies(undefined, 'normal', 'general')).toBe(false);
  });

  it('words the signer line with the server name and the time in IST', () => {
    const sig = { type: 'normal', templateKey: 'general', signedByName: 'Dr. Anil Rao', signedAt: '2026-10-24T05:35:00Z' } as const;
    expect(signedByText(sig, formatWhen)).toBe('Signed by Dr. Anil Rao · 24 Oct 2026, 11:05 AM IST');
    expect(signedByText(null, formatWhen)).toBe('');
    expect(signedByText(undefined, formatWhen)).toBe('');
  });

  it('words the discharge button for collect, refund and settled', () => {
    expect(dischargeButtonText(150000, formatInrPaise)).toBe('Collect ₹1,500 and discharge');
    expect(dischargeButtonText(-250050, formatInrPaise)).toBe('Refund ₹2,500.50 and discharge');
    expect(dischargeButtonText(0, formatInrPaise)).toBe('Discharge');
  });

  it('labels the last bill row and the pay mode group', () => {
    expect(balanceLabel(1)).toBe('To collect now');
    expect(balanceLabel(-1)).toBe('To refund');
    expect(balanceLabel(0)).toBe('Settled');
    expect(payModeLabel(1)).toBe('Collect by');
    expect(payModeLabel(-1)).toBe('Refund by');
  });

  it('writes the Stay row: plural, singular and no specialty', () => {
    expect(stayText(PREVIEW)).toBe('3 days · bed 101-A · Dr. Meera Kulkarni · General medicine');
    expect(stayText({ ...PREVIEW, stayDays: 1 })).toBe('1 day · bed 101-A · Dr. Meera Kulkarni · General medicine');
    expect(stayText({ ...PREVIEW, doctorSpecialty: null })).toBe('3 days · bed 101-A · Dr. Meera Kulkarni');
  });

  it('writes the follow-up row, with a dash for Death', () => {
    expect(followUpText(PREVIEW, 'normal', '2026-10-31', formatDate)).toBe(
      'OPD review on 31 Oct 2026 with Dr. Meera Kulkarni',
    );
    expect(followUpText(PREVIEW, 'death', '2026-10-31', formatDate)).toBe('—');
  });

  it('writes the notice, with the Death text', () => {
    expect(dischargeNotice('lama', '2026-10-31', formatDate)).toBe(
      'The discharge summary and final bill are not sent yet (WhatsApp is not in this phase). An OPD follow-up is added for 31 Oct 2026.',
    );
    expect(dischargeNotice('death', '2026-10-31', formatDate)).toBe(
      'The death summary cannot be printed in this phase. No WhatsApp messages are sent. No follow-up is added.',
    );
  });

  it('builds the toast for a collection, a refund and a settled bill', () => {
    expect(dischargeToast(discharged(), formatDate)).toBe(
      'Asha Patil discharged (normal) · ₹1,500 collected by UPI · bed 101-A sent for cleaning · follow-up on 31 Oct 2026',
    );
    const refund = discharged();
    refund.finalBill = { ...refund.finalBill!, balancePaise: -50000, outcome: 'refunded', mode: 'Cash' };
    expect(dischargeToast(refund, formatDate)).toContain('₹500 refunded by Cash');
    const settled = discharged();
    settled.finalBill = { ...settled.finalBill!, balancePaise: 0, outcome: 'settled', mode: null };
    expect(dischargeToast(settled, formatDate)).toContain('discharged (normal) · bill settled · bed 101-A');
  });

  it('writes the type in lower case and leaves out the follow-up for Death', () => {
    const lama = discharged();
    lama.discharge = { ...lama.discharge!, type: 'lama' };
    expect(dischargeToast(lama, formatDate)).toContain('discharged (on request (lama))');
    const death = discharged();
    death.discharge = { ...death.discharge!, type: 'death', followUpDate: null };
    const toast = dischargeToast(death, formatDate);
    expect(toast).toContain('discharged (death)');
    expect(toast).not.toContain('follow-up');
  });

  it('words the escalation warning by type and by whether the sign-off covers it', () => {
    expect(openEscalationWarning(false, 'death', true)).toBe('');
    expect(openEscalationWarning(undefined, 'normal')).toBe('');
    expect(openEscalationWarning(true, 'normal')).toBe(ESCALATION_OPEN_NORMAL);
    expect(openEscalationWarning(true, 'normal', true)).toBe(ESCALATION_OPEN_NORMAL);
    for (const type of ['death', 'lama', 'referred'] as const) {
      expect(openEscalationWarning(true, type)).toBe(ESCALATION_OPEN_CLOSES);
      expect(openEscalationWarning(true, type, false)).toBe(ESCALATION_OPEN_CLOSES);
      expect(openEscalationWarning(true, type, true)).toBe(ESCALATION_OPEN_SIGNED_EARLIER);
    }
    expect(ESCALATION_OPEN_SIGNED_EARLIER).toBe(
      'This patient has an early warning escalation that started after the doctor signed the summary. A doctor must record the response or sign the summary again.',
    );
  });

  it('counts the Going home today tile', () => {
    expect(goingHomeCount({ dischargedToday: 2, goingHomeToday: 3 })).toBe(5);
    expect(goingHomeText({ dischargedToday: 2, goingHomeToday: 3 })).toBe('2 discharged · 3 planned');
    expect(goingHomeCount({})).toBe(0);
    expect(goingHomeText({})).toBe('0 discharged · 0 planned');
  });

  it('words the follow-up rule hint from the OPD fee rule', () => {
    expect(followUpRuleHint({ days: 6, fee: 'free' })).toBe(
      'Adds an OPD follow-up. The fee follows your follow-up rule (free within 6 days).',
    );
    expect(followUpRuleHint({ days: 10, fee: 'half' })).toContain('(charged within 10 days)');
    expect(followUpRuleHint({ days: 3, fee: 'full' })).toContain('(charged within 3 days)');
  });
});
