import { hardStop, warning } from '../emr/emr.fixtures';
import { ROW, rowOf } from './pharmacist.fixtures';
import {
  LOCK_HINT,
  allergyLabel,
  rejectSubtitle,
  rejectTitle,
  rejectedToast,
  rowLine,
  rowTitle,
  safetyTabCount,
  summaryChanged,
  verifiedToast,
  verifySubtitle,
  verifyTitle,
  warningLine,
} from './pharmacist.model';

describe('pharmacist.model', () => {
  it('shows the allergy text, or "Allergies not recorded" for null, empty or blank', () => {
    expect(allergyLabel({ allergyText: 'Penicillin (rash)' })).toBe('Penicillin (rash)');
    expect(allergyLabel({ allergyText: null })).toBe('Allergies not recorded');
    expect(allergyLabel({ allergyText: '' })).toBe('Allergies not recorded');
    expect(allergyLabel({ allergyText: '   ' })).toBe('Allergies not recorded');
  });

  describe('row line (mockup pendRow)', () => {
    it('has the patient, bed, allergy and who ordered at what time', () => {
      expect(rowLine(ROW, '2026-11-06T06:00:00Z')).toBe(
        'Asha Patil · bed 101-A · Penicillin (rash) · ordered by Dr. Meera Kulkarni at 11:05 AM',
      );
    });
    it('adds the day when the order is from another day', () => {
      expect(rowLine(ROW, '2026-11-07T06:00:00Z')).toBe(
        'Asha Patil · bed 101-A · Penicillin (rash) · ordered by Dr. Meera Kulkarni at 6 Nov, 11:05 AM',
      );
    });
    it('says "Allergies not recorded" for an empty allergy field', () => {
      const row = rowOf({ patient: { ...ROW.patient, allergyText: null, allergyStatus: 'NOT_RECORDED' } });
      expect(rowLine(row, '2026-11-06T06:00:00Z')).toContain('· Allergies not recorded ·');
    });
  });

  it('writes titles, subtitles and toasts as the contract', () => {
    expect(rowTitle(ROW)).toBe('Tab. Paracetamol 650 mg · Once daily');
    expect(verifyTitle(ROW)).toBe('Pharmacist check · Tab. Paracetamol 650 mg');
    expect(verifySubtitle(ROW)).toBe('Asha Patil · UH-0001 · ordered by Dr. Meera Kulkarni');
    expect(rejectTitle(ROW)).toBe('Reject · Tab. Paracetamol 650 mg');
    expect(rejectSubtitle(ROW)).toBe('Asha Patil · ordered by Dr. Meera Kulkarni');
    expect(verifiedToast('Tab. Paracetamol 650 mg', 'Rahul Deshmukh')).toBe(
      'Tab. Paracetamol 650 mg verified by Rahul Deshmukh. It is now on the MAR.',
    );
    expect(rejectedToast('Interaction risk too high')).toBe('Order rejected · Interaction risk too high.');
    expect(warningLine(warning('Bleeding risk: heparin with aspirin'))).toBe('Major: Bleeding risk: heparin with aspirin');
    expect(warningLine(hardStop('x'))).toBe('Hard stop: x');
  });

  it('gives the hint for each reason Verify is off', () => {
    expect(LOCK_HINT.HARD_STOP).toBe('Hard stop: reject this order');
    expect(LOCK_HINT.SELF).toBe('The doctor who ordered this cannot verify it. Ask the pharmacist.');
    expect(LOCK_HINT.IPD_READONLY).toBe('IPD is read-only. Nothing can be changed.');
  });

  it('adds the open escalations and the orders waiting for the tab number', () => {
    expect(safetyTabCount(2, 3)).toBe(5);
    expect(safetyTabCount(0, 0)).toBe(0);
    expect(safetyTabCount(undefined, 4)).toBe(4);
    expect(safetyTabCount(1, null)).toBe(1);
  });

  it('adds the late doses (SAH-35), not the doses that are only due', () => {
    expect(safetyTabCount(2, 3, 4)).toBe(9);
    expect(safetyTabCount(0, 0, 0)).toBe(0);
    expect(safetyTabCount(0, null, 2)).toBe(2);
    expect(safetyTabCount(2, 1)).toBe(3);
    expect(safetyTabCount(2, 1, undefined)).toBe(3);
  });

  describe('summaryChanged', () => {
    it('is a different count, or the same count with another token', () => {
      const last = { waiting: 2, changeToken: 'A'.repeat(22) };
      expect(summaryChanged(last, { waiting: 2, changeToken: 'A'.repeat(22) }, 2)).toBe(false);
      expect(summaryChanged(last, { waiting: 3, changeToken: 'A'.repeat(22) }, 2)).toBe(true);
      expect(summaryChanged(last, { waiting: 2, changeToken: 'B'.repeat(22) }, 2)).toBe(true);
    });
    it('with nothing seen yet, compares only the count with the one on screen', () => {
      const next = { waiting: 2, changeToken: 'A'.repeat(22) };
      expect(summaryChanged(null, next, 2)).toBe(false);
      expect(summaryChanged(null, next, 1)).toBe(true);
      expect(summaryChanged(null, next, null)).toBe(false);
    });
  });
});
