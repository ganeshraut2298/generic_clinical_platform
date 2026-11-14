import { EmrBodyMapValue, EmrListValue, EmrPrescriptionValue } from '../emr-phase3.model';
import { isComponentEmpty, isComponentFilled, isComponentValue, sanitizeComponentValue } from './component-values.logic';

const list = (active: boolean[]): EmrListValue => ({
  component: 'EMR_LIST',
  kind: 'COMPLAINTS',
  schemaVersion: 1,
  items: active.map((a, i) => ({ id: `i${i}`, itemKey: null, text: `T${i}`, newToList: true, attr1: 'D3', attr2: 'MILD', active: a })),
});

describe('component values (ADR-022 D2)', () => {
  it('knows a component value by its component name', () => {
    expect(isComponentValue(list([true]))).toBe(true);
    expect(isComponentValue([])).toBe(false);
    expect(isComponentValue(null)).toBe(false);
    expect(isComponentValue({ text: 'x' })).toBe(false);
  });

  describe('stored or empty', () => {
    it('is empty with no item, no row, and no pin or confirmation', () => {
      expect(isComponentEmpty(list([]))).toBe(true);
      expect(isComponentEmpty({ component: 'PRESCRIPTION', schemaVersion: 1, rows: [] })).toBe(true);
      expect(isComponentEmpty({ component: 'BODY_MAP', schemaVersion: 1, pins: [], noFindingsConfirmed: false })).toBe(true);
      expect(isComponentEmpty(null)).toBe(true);
    });

    it('keeps a list whose items are all switched off, so they are not lost', () => {
      expect(isComponentEmpty(list([false]))).toBe(false);
    });

    it('keeps a body map that only holds "No local findings"', () => {
      expect(isComponentEmpty({ component: 'BODY_MAP', schemaVersion: 1, pins: [], noFindingsConfirmed: true })).toBe(false);
    });
  });

  describe('Done rule', () => {
    it('needs one active item in a list', () => {
      expect(isComponentFilled(list([true]))).toBe(true);
      expect(isComponentFilled(list([false, false]))).toBe(false);
      expect(isComponentFilled(list([false, true]))).toBe(true);
    });

    it('needs one row in the prescription', () => {
      const row = { id: 'r', medicineId: null, name: 'X', form: null, frequency: '1-0-1' as const, days: 5, when: 'AFTER_FOOD' as const, source: 'TYPED' as const };
      expect(isComponentFilled({ component: 'PRESCRIPTION', schemaVersion: 1, rows: [row] })).toBe(true);
      expect(isComponentFilled({ component: 'PRESCRIPTION', schemaVersion: 1, rows: [] })).toBe(false);
    });

    it('needs a pin or the confirmation in a body map', () => {
      const pin = { no: 1, side: 'FRONT' as const, x: 0.5, y: 0.5, region: 'head', where: 'Head', finding: 'WOUND' as const, cause: 'FALL' as const, severity: 'MILD' as const, sizeCm: 2, note: '', mlc: false };
      expect(isComponentFilled({ component: 'BODY_MAP', schemaVersion: 1, pins: [pin], noFindingsConfirmed: false })).toBe(true);
      expect(isComponentFilled({ component: 'BODY_MAP', schemaVersion: 1, pins: [], noFindingsConfirmed: true })).toBe(true);
      expect(isComponentFilled({ component: 'BODY_MAP', schemaVersion: 1, pins: [], noFindingsConfirmed: false })).toBe(false);
    });
  });

  describe('what is sent', () => {
    it('drops the response-only properties of a list item', () => {
      const v: EmrListValue = list([true]);
      v.items[0] = { ...v.items[0], code: 'I10', addedAt: '2026-11-12T05:00:00Z', addedByName: 'Dr X', addedByMembershipId: 'm', unitPricePaise: 150000, billed: 'ON_BILL' };
      const out = sanitizeComponentValue(v) as EmrListValue;
      expect(Object.keys(out.items[0]).sort()).toEqual(['active', 'attr1', 'attr2', 'id', 'itemKey', 'newToList', 'text']);
    });

    it('keeps only the review the doctor made on a prescription row', () => {
      const v: EmrPrescriptionValue = {
        component: 'PRESCRIPTION',
        schemaVersion: 1,
        rows: [
          {
            id: 'r',
            medicineId: 'm1',
            name: 'Amoxicillin 500 mg',
            form: 'Capsule',
            frequency: '1-0-1',
            days: 5,
            when: 'AFTER_FOOD',
            source: 'SEARCH',
            allergyReview: { allergyClass: 'PENICILLIN', confirmed: true, reviewedAt: '2026-11-12T05:00:00Z', reviewedByMembershipId: 'm' },
            addedAt: '2026-11-12T05:00:00Z',
          },
        ],
      };
      const out = sanitizeComponentValue(v) as EmrPrescriptionValue;
      expect(out.rows[0].allergyReview).toEqual({ allergyClass: 'PENICILLIN', confirmed: true });
      expect('addedAt' in out.rows[0]).toBe(false);
    });

    it('does not send the pin number counter or the phase 2 text of a body map', () => {
      const v: EmrBodyMapValue = { component: 'BODY_MAP', schemaVersion: 1, pins: [], nextPinNo: 7, noFindingsConfirmed: true, legacyText: 'old' };
      expect(sanitizeComponentValue(v)).toEqual({ component: 'BODY_MAP', schemaVersion: 1, pins: [], noFindingsConfirmed: true });
    });

    it('leaves any other value as it is', () => {
      expect(sanitizeComponentValue('abc')).toBe('abc');
      expect(sanitizeComponentValue(null)).toBeNull();
    });
  });
});
