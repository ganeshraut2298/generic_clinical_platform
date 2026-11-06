import {
  CHECK_AND_ORDER,
  ORDER_WITH_REASON,
  ShownCheck,
  allergyChip,
  drugOptionLabel,
  emrSubtitle,
  identityLine,
  orderButtonText,
  orderFieldErrors,
  orderSmallLine,
  orderedToast,
  reasonChangeNeedsNewKey,
  severityWord,
  stoppedToast,
} from './emr.model';
import { ORDER, PATIENT, checkOf, sheetOf } from './emr.fixtures';

describe('emr.model', () => {
  describe('allergy chip (by allergyStatus only)', () => {
    it('is red with the allergy text when RECORDED, whatever the keyword', () => {
      expect(allergyChip({ allergyStatus: 'RECORDED', allergyText: 'Latex' })).toEqual({
        tone: 'bad',
        icon: 'alert',
        text: 'Allergy: Latex',
      });
      expect(
        allergyChip({ allergyStatus: 'RECORDED', allergyText: 'Penicillin; none known otherwise' }).tone,
      ).toBe('bad');
    });
    it('is green when NONE_KNOWN', () => {
      expect(allergyChip({ allergyStatus: 'NONE_KNOWN', allergyText: 'None known' })).toEqual({
        tone: 'ok',
        icon: 'check',
        text: 'No known allergies',
      });
    });
    it('says "Allergies not recorded" when NOT_RECORDED, even for an empty text', () => {
      expect(allergyChip({ allergyStatus: 'NOT_RECORDED', allergyText: null })).toEqual({
        tone: 'nr',
        icon: 'alert',
        text: 'Allergies not recorded',
      });
    });
  });

  it('writes the identity line and skips what is not known', () => {
    expect(identityLine(PATIENT)).toBe('UH-0001 · 42 · Female · bed 101-A');
    expect(identityLine({ ...PATIENT, age: null, sex: null })).toBe('UH-0001 · bed 101-A');
    expect(identityLine({ ...PATIENT, sex: 'M' })).toBe('UH-0001 · 42 · Male · bed 101-A');
    expect(identityLine({ ...PATIENT, sex: 'O' })).toBe('UH-0001 · 42 · Other · bed 101-A');
  });

  it('writes the sheet subtitle with the admitting doctor', () => {
    expect(emrSubtitle(sheetOf())).toBe('UH-0001 · 42 · Female · Dr. Meera Kulkarni');
  });

  it('marks a high-alert medicine in the select', () => {
    expect(drugOptionLabel({ name: 'Inj. Heparin 5,000 IU', highAlert: 'Anticoagulant' })).toBe(
      'Inj. Heparin 5,000 IU · high-alert',
    );
    expect(drugOptionLabel({ name: 'Tab. Paracetamol 650 mg', highAlert: null })).toBe(
      'Tab. Paracetamol 650 mg',
    );
  });

  describe('small line under a medicine', () => {
    it('shows route, frequency and the person who ordered', () => {
      expect(orderSmallLine(ORDER)).toBe('Oral · Once daily · Dr. Meera Kulkarni');
    });
    it('adds the override reason', () => {
      const line = orderSmallLine({
        ...ORDER,
        overrideReason: { key: 'CARDIOLOGY_STENT', label: 'Recent coronary stent, cardiology advised' },
      });
      expect(line).toBe(
        'Oral · Once daily · Dr. Meera Kulkarni · override: Recent coronary stent, cardiology advised',
      );
    });
    it('adds the reject reason for a rejected order only', () => {
      const rejected = {
        ...ORDER,
        status: 'REJECTED' as const,
        rejectReason: { key: 'WRONG_DOSE', label: 'Wrong dose or frequency' },
      };
      expect(orderSmallLine(rejected)).toBe(
        'Oral · Once daily · Dr. Meera Kulkarni · rejected: Wrong dose or frequency',
      );
      expect(orderSmallLine({ ...rejected, status: 'STOPPED' })).toBe(
        'Oral · Once daily · Dr. Meera Kulkarni',
      );
    });
    it('puts the override before the rejection', () => {
      const both = {
        ...ORDER,
        status: 'REJECTED' as const,
        overrideReason: { key: 'SPECIALIST_ADVISED', label: 'Specialist advised this combination' },
        rejectReason: { key: 'INTERACTION_RISK', label: 'Interaction risk too high' },
      };
      expect(orderSmallLine(both)).toBe(
        'Oral · Once daily · Dr. Meera Kulkarni · override: Specialist advised this combination · rejected: Interaction risk too high',
      );
    });
  });

  it('words the toasts exactly as the contract', () => {
    expect(orderedToast('Tab. Aspirin 75 mg')).toBe(
      'Tab. Aspirin 75 mg ordered. It goes to the pharmacist before the first dose.',
    );
    expect(stoppedToast('Tab. Aspirin 75 mg')).toBe(
      'Tab. Aspirin 75 mg stopped. Remaining doses are removed from the MAR.',
    );
  });

  it('names the severities', () => {
    expect(severityWord('MAJOR')).toBe('Major');
    expect(severityWord('MODERATE')).toBe('Moderate');
    expect(severityWord('HARD_STOP')).toBe('Hard stop');
  });

  describe('client key and button', () => {
    it('keeps the key from no reason to a reason, and replaces it when a chosen reason changes', () => {
      expect(reasonChangeNeedsNewKey('', 'CARDIOLOGY_STENT')).toBe(false);
      expect(reasonChangeNeedsNewKey('CARDIOLOGY_STENT', 'SPECIALIST_ADVISED')).toBe(true);
      expect(reasonChangeNeedsNewKey('CARDIOLOGY_STENT', '')).toBe(true);
      expect(reasonChangeNeedsNewKey('CARDIOLOGY_STENT', 'CARDIOLOGY_STENT')).toBe(false);
    });
    it('reads "Order with this reason" only while the form asks for a reason', () => {
      const shown = (outcome: ShownCheck['outcome']): ShownCheck => ({
        drugKey: 'asp',
        frequencyKey: 'od',
        outcome,
        check: checkOf(),
      });
      expect(orderButtonText(null)).toBe(CHECK_AND_ORDER);
      expect(orderButtonText(shown('BLOCKED'))).toBe(CHECK_AND_ORDER);
      expect(orderButtonText(shown('NEEDS_REASON'))).toBe(ORDER_WITH_REASON);
    });
  });

  describe('field errors of a 400', () => {
    it('maps the form fields and keeps the first message of each', () => {
      const out = orderFieldErrors([
        { field: 'drugKey', message: 'Choose a medicine from the list' },
        { field: 'drugKey', message: 'second' },
        { field: 'frequencyKey', message: 'Choose how often' },
        { field: 'overrideReasonKey', message: 'Choose a reason from the list' },
      ]);
      expect(out.shown).toEqual({
        drugKey: 'Choose a medicine from the list',
        frequencyKey: 'Choose how often',
        overrideReasonKey: 'Choose a reason from the list',
      });
      expect(out.other).toBeNull();
      expect(out.newKey).toBe(false);
    });
    it('asks for a new key and a toast text when the client key is wrong', () => {
      const out = orderFieldErrors([{ field: 'clientKey', message: 'Reload the page and try again.' }]);
      expect(out.newKey).toBe(true);
      expect(out.other).toBe('Reload the page and try again.');
      expect(out.shown).toEqual({});
    });
  });
});
