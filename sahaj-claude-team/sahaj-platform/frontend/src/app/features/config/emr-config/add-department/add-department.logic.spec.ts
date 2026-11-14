import { section, sections } from '../../../emr/emr.fixtures';
import {
  createBody,
  previewSections,
  previewView,
  roomProblem,
  turnedOffLine,
  withPrintFlags,
} from './add-department.logic';

describe('add-department.logic', () => {
  it('writes the Turned off line as the mockup does', () => {
    expect(turnedOffLine([])).toBeNull();
    expect(turnedOffLine(['Prescription (Rx)'])).toBe('Turned off by default: Prescription (Rx). You can switch them back on.');
    expect(turnedOffLine(['Prescription (Rx)', 'Investigations', 'Systemic examination'])).toBe(
      'Turned off by default: Prescription (Rx), Investigations and Systemic examination. You can switch them back on.',
    );
  });

  it('lists only the Specialty sections on the preview', () => {
    const detail = { sections: [section({ key: 'a', name: 'Vitals' }), section({ key: 'b', name: 'ROM', kind: 'SPECIALTY' })] };
    expect(previewSections(detail)).toEqual(['ROM']);
  });

  it('adds the design brackets to four physiotherapy section names', () => {
    const detail = {
      sections: [
        section({ key: 'pain', name: 'Pain assessment', kind: 'SPECIALTY' }),
        section({ key: 'mmt', name: 'Manual muscle testing', kind: 'SPECIALTY' }),
        section({ key: 'func_scales', name: 'Outcome scales', kind: 'SPECIALTY' }),
        section({ key: 'modalities', name: 'Treatment modalities', kind: 'SPECIALTY' }),
      ],
    };
    expect(previewSections(detail)).toEqual([
      'Pain assessment (VAS / NPRS)',
      'Manual muscle testing (Oxford 0–5)',
      'Outcome scales (ODI, NDI, WOMAC)',
      'Treatment modalities (IFT, TENS, US)',
    ]);
  });

  it('clears every Print flag when the print template is not wanted', () => {
    const withPrint = sections().map((s) => ({ ...s, print: true }));
    expect(withPrintFlags(withPrint, true).every((s) => s.print)).toBe(true);
    expect(withPrintFlags(withPrint, false).every((s) => !s.print)).toBe(true);
  });

  it('builds a department that does not exist yet for the review step', () => {
    const view = previewView('Physio annexe', { initials: 'PT', colour: '#6D3FC0' }, 'physio', sections(), {
      kind: 'STARTER',
      starterKey: 'physio',
    });
    expect(view.name).toBe('Physio annexe');
    expect(view.status).toBe('DRAFT');
    expect(view.sectionsTotal).toBe(6);
    expect(view.sectionsOn).toBe(6);
    expect(view.requiredOn).toBe(2);
    expect(view.canEdit).toBe(true);
  });

  describe('create body (SAH-54-4)', () => {
    const base = { copyPrintFlags: true, changedSections: null, doctorMembershipIds: [], rooms: [] };

    it('sends the starter and trims the name', () => {
      expect(
        createBody({ ...base, name: '  Physio annexe ', source: { kind: 'STARTER', starterKey: 'physio' } }),
      ).toEqual({
        name: 'Physio annexe',
        source: { kind: 'STARTER', starterKey: 'physio' },
        copyPrintFlags: true,
        doctorMembershipIds: [],
        rooms: [],
      });
    });

    it('sends the copy source or nothing for blank', () => {
      expect(createBody({ ...base, name: 'B', source: { kind: 'BLANK' } }).source).toEqual({ kind: 'BLANK' });
      expect(
        createBody({ ...base, name: 'C', source: { kind: 'COPY', copyFromDepartmentKey: 'dental' } }).source,
      ).toEqual({ kind: 'COPY', copyFromDepartmentKey: 'dental' });
    });

    it('sends the sections only when the owner changed them, and never a clinic id', () => {
      const withSections = createBody({
        ...base,
        name: 'X',
        source: { kind: 'BLANK' },
        changedSections: [{ key: 'a', part: 'ASSESSMENT', shown: true, required: false, print: false, copy: false, collapsed: false, whoCanFill: ['DOCTOR'] }],
        doctorMembershipIds: ['m1'],
        rooms: [' Chair 1 '],
      });
      expect(withSections.sections).toHaveLength(1);
      expect(withSections.rooms).toEqual(['Chair 1']);
      expect(withSections.doctorMembershipIds).toEqual(['m1']);
      expect(Object.keys(withSections)).not.toContain('clinicId');
      expect(Object.keys(withSections)).not.toContain('tenantId');
    });
  });

  describe('rooms (OQ-45-19)', () => {
    it('refuses an empty name, a long one, a repeat in any letter case and an eleventh room', () => {
      expect(roomProblem([], '  ')).toBe('Type a room name first.');
      expect(roomProblem([], 'x'.repeat(41))).toContain('at most 40');
      expect(roomProblem(['Room 2'], 'room 2')).toBe('That room is already in the list.');
      expect(roomProblem(Array.from({ length: 10 }, (_, i) => `R${i}`), 'More')).toContain('at most 10');
      expect(roomProblem(['Room 2'], 'Chair 1')).toBeNull();
    });
  });
});
