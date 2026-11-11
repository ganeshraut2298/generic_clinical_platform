import { safetyTabCount } from '../pharmacist/pharmacist.model';
import { eventText, otherWardsNotice, sameCounts, scopeNote, yourPatientsText } from './safety.model';

describe('safety.model: SAH-34 additions', () => {
  describe('scopeNote (AC 37)', () => {
    it('counts the nurse patients against the clinic, in the words of the story', () => {
      expect(scopeNote({ kind: 'WARDS', visible: 2, admitted: 12, hasWard: true })).toBe(
        'This board shows your wards only (2 of 12 patients).',
      );
    });

    it('says the board shows no patients when she has no ward', () => {
      expect(scopeNote({ kind: 'WARDS', visible: 0, admitted: 12, hasWard: false })).toBe(
        'No wards are assigned to you, so this board shows no patients.',
      );
    });

    it('has no note for the scope all or without a scope (OWNER, DOCTOR)', () => {
      expect(scopeNote({ kind: 'ALL', visible: 12, admitted: 12, hasWard: false })).toBeNull();
      expect(scopeNote(undefined)).toBeNull();
    });
  });

  describe('yourPatientsText (AC 16)', () => {
    it('is "In your wards" for the scope wards and "All admitted patients" for all', () => {
      expect(yourPatientsText({ kind: 'WARDS' })).toBe('In your wards');
      expect(yourPatientsText({ kind: 'ALL' })).toBe('All admitted patients');
      expect(yourPatientsText(undefined)).toBe('In your wards');
    });
  });

  describe('otherWardsNotice (AC 37, OQ-34-26)', () => {
    it('uses the singular and the plural and carries no name', () => {
      expect(otherWardsNotice(1).head).toBe('1 escalation past the response target in other wards.');
      expect(otherWardsNotice(3).head).toBe('3 escalations past the response target in other wards.');
      expect(otherWardsNotice(2).tail).toBe(' Tell the doctor now, or use Emergency access if you must act.');
    });
  });

  describe('eventText (AC 70)', () => {
    it('adds the grantee name to the review entry only', () => {
      expect(eventText({ text: 'Reviewed emergency access', grantStaffName: 'Meera Joshi' })).toBe(
        'Reviewed emergency access by Meera Joshi',
      );
      expect(eventText({ text: 'Emergency access opened · Covering for a colleague on break' })).toBe(
        'Emergency access opened · Covering for a colleague on break',
      );
    });
  });

  describe('counts', () => {
    const base = { open: 1, pastTarget: 0, dosesDue: 0, dosesLate: 0, changeToken: 'A'.repeat(22) };

    it('treats a different emergency access number as a change, and an absent one as 0', () => {
      expect(sameCounts({ ...base, emergencyAccessWaiting: 2 }, { ...base, emergencyAccessWaiting: 1 })).toBe(false);
      expect(sameCounts({ ...base }, { ...base, emergencyAccessWaiting: 0 })).toBe(true);
    });

    it('adds the emergency access waiting to the owner tab number (OQ-34-30)', () => {
      expect(safetyTabCount(1, 3, 0, 2)).toBe(6);
      expect(safetyTabCount(1, 3, 0)).toBe(4);
      expect(safetyTabCount(0, 0, 0, undefined)).toBe(0);
      expect(safetyTabCount(0, null, 0, 4)).toBe(4);
    });
  });
});
