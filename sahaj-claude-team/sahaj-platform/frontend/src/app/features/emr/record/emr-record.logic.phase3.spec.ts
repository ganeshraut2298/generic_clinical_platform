import { field, section } from '../emr.fixtures';
import { EmrListValue } from '../emr-phase3.model';
import { ff, footDefinition } from '../forms/form.fixtures';
import { compact, fieldProblem, isEmptyValue, isFillable, isSectionDone, sectionProblems } from './emr-record.logic';

const list = (active: boolean[]): EmrListValue => ({
  component: 'EMR_LIST',
  kind: 'COMPLAINTS',
  schemaVersion: 1,
  items: active.map((a, i) => ({ id: `i${i}`, itemKey: null, text: `T${i}`, newToList: true, attr1: 'D3', attr2: 'MODERATE', active: a })),
});

const complaint = field({ key: 'complaint', valueKind: 'COMPONENT', type: 'SEARCH_LIST', required: true, component: { type: 'EMR_LIST', listKind: 'COMPLAINTS' } });
const duration = field({ key: 'duration', valueKind: 'COVERED', type: 'NUMBER_UNIT', required: true });

describe('emr-record.logic for the shared components (ADR-022 D2)', () => {
  it('never asks for a covered sibling', () => {
    expect(isFillable(duration)).toBe(false);
    expect(isFillable(complaint)).toBe(true);
    const s = section({ key: 'complaints', required: true, fields: [complaint, duration] });
    expect(isSectionDone(s, { complaint: list([true]) }, false)).toBe(true);
    expect(sectionProblems(s, { duration: 'oops' })).toEqual({});
  });

  it('is Done with one active item, even though the covered duration is marked required', () => {
    const s = section({ key: 'complaints', required: true, fields: [complaint, duration] });
    expect(isSectionDone(s, {}, false)).toBe(false);
    expect(isSectionDone(s, { complaint: list([false]) }, false)).toBe(false);
    expect(isSectionDone(s, { complaint: list([false, true]) }, false)).toBe(true);
  });

  it('treats a component value with items as stored, so switched-off items are kept on save', () => {
    expect(isEmptyValue(list([]))).toBe(true);
    expect(isEmptyValue(list([false]))).toBe(false);
    const sent = compact({ complaint: list([false]), empty: list([]) });
    expect(Object.keys(sent)).toEqual(['complaint']);
  });

  it('sends only the writable properties of a component value', () => {
    const v = list([true]);
    v.items[0].addedByName = 'Dr X';
    v.items[0].code = 'I10';
    const sent = compact({ complaint: v }) as { complaint: EmrListValue };
    expect(sent.complaint.items[0]).not.toHaveProperty('addedByName');
    expect(sent.complaint.items[0]).not.toHaveProperty('code');
  });

  it('refuses a component value over its limit', () => {
    const many = list(Array.from({ length: 51 }, () => true));
    expect(fieldProblem(complaint, many)).toBe('Add at most 50.');
    expect(fieldProblem(complaint, list([true]))).toBeNull();
  });

  describe('a clinic form as a section (SAH-59-5)', () => {
    const form = footDefinition();
    const formSection = section({ key: 'f_abcdefghij', name: form.name, source: 'FORM', form, fields: [] });

    it('is Done when its required shown fields are filled', () => {
      expect(isSectionDone(formSection, {}, false)).toBe(false);
      expect(isSectionDone(formSection, { ulcer: true, mono: 9 }, false)).toBe(true);
    });

    it('refuses a number outside its limits', () => {
      expect(sectionProblems(formSection, { mono: 14 })).toEqual({ mono: 'Outside 0–10' });
    });

    it('ignores a hidden required field', () => {
      const d = { ...form, fields: [ff({ key: 'a', type: 'YES_NO' }), ff({ key: 'b', type: 'SHORT_TEXT', required: true, showIf: { fieldKey: 'a', optionKey: 'yes' } })] };
      const s = section({ key: 'f_x', source: 'FORM', form: d, fields: [] });
      expect(isSectionDone(s, { a: false }, false)).toBe(true);
      expect(isSectionDone(s, { a: true }, false)).toBe(false);
    });
  });
});
