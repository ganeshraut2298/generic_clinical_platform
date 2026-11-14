import { ff, footDefinition, formView, settings } from '../../emr/forms/form.fixtures';
import {
  NO_DEPARTMENT_TEXT,
  NO_FIELD_TEXT,
  PALETTE,
  addOption,
  chipText,
  deleteBlockedMessage,
  departmentsText,
  duplicate,
  fieldProblems,
  insertAt,
  insertionIndex,
  isDirty,
  move,
  moveTo,
  newField,
  publishProblem,
  removeField,
  scoreSourceCandidates,
  showIfCandidates,
  showIfFor,
  stepScore,
  uniqueKey,
  usersOf,
} from './form-builder.logic';

const fields = footDefinition().fields;

describe('form builder rules (SAH-59-1 to SAH-59-4)', () => {
  describe('palette', () => {
    it('offers 23 field types in three groups, in the mockup words', () => {
      expect(PALETTE.map((g) => [g.label, g.items.length])).toEqual([
        ['Basic fields', 9],
        ['Clinical', 8],
        ['Layout and sign-off', 6],
      ]);
      expect(PALETTE.flatMap((g) => g.items).length).toBe(23);
      expect(PALETTE[0].items.map((i) => i.label)).toEqual([
        'Short text',
        'Long text',
        'Number',
        'Date',
        'Time',
        'Yes / No',
        'Single choice',
        'Multiple choice',
        'Dropdown',
      ]);
      expect(PALETTE[1].items.map((i) => i.label)).toContain('Score (Σ)');
      expect(PALETTE[2].items.map((i) => i.label)).toEqual(['Section heading', 'Info note', 'Signature', 'Photo / image', 'File upload', 'Handwriting pad']);
    });
  });

  describe('new fields', () => {
    it('gets a unique key that matches the contract pattern', () => {
      const a = newField('SHORT_TEXT', []);
      expect(a.key).toBe('short_text');
      const b = newField('SHORT_TEXT', [a]);
      expect(b.key).toBe('short_text_2');
      expect(b.key).toMatch(/^[a-z][a-z0-9_]{0,39}$/);
      expect(uniqueKey('9 lives!', [])).toMatch(/^[a-z][a-z0-9_]*$/);
    });

    it('starts with the defaults: print on, copy off, Doctor fills, not required', () => {
      const f = newField('NUMBER', []);
      expect(f).toMatchObject({ type: 'NUMBER', label: 'Number', required: false, print: true, copyFromLast: false, whoCanFill: ['DOCTOR'], width: 3 });
    });

    it('is full width for long text, headings and clinical blocks', () => {
      for (const t of ['LONG_TEXT', 'SECTION_HEADING', 'BODY_MAP', 'MEDICINE_LIST'] as const) {
        expect(newField(t, []).width).toBe(6);
      }
    });

    it('gives a choice field three options and a note its instruction text', () => {
      expect(newField('SINGLE_CHOICE', []).options?.map((o) => o.label)).toEqual(['Option 1', 'Option 2', 'Option 3']);
      expect(newField('INFO_NOTE', []).label).toBe('Write an instruction for the person filling the form.');
    });

    it('starts a Score with the choice fields that already carry scores, and a band on the first Score', () => {
      const score = newField('SCORE', fields.slice(0, 4));
      expect(score.score?.sourceFieldKeys).toEqual(['ulcer', 'vib']);
      const band = newField('SCORE_BAND', fields);
      expect(band.band?.sourceFieldKey).toBe('risk');
      expect(band.band?.bands.map((b) => [b.from, b.label])).toEqual([[0, 'Low'], [3, 'Moderate'], [6, 'High']]);
    });
  });

  describe('editing the list', () => {
    it('adds below the selected field, or at the end', () => {
      expect(insertionIndex(fields, 'mono')).toBe(3);
      expect(insertionIndex(fields, null)).toBe(fields.length);
      expect(insertAt(fields, 1, newField('DATE', fields))[1].type).toBe('DATE');
    });

    it('duplicates below with a new key and "(copy)"', () => {
      const r = duplicate(fields, 'mono')!;
      expect(r.copy.label).toBe('Monofilament: sites felt (copy)');
      expect(r.copy.key).not.toBe('mono');
      expect(r.fields[3].key).toBe(r.copy.key);
      expect(duplicate(fields, 'nope')).toBeNull();
    });

    it('moves a field up or down, and stays put at the edge', () => {
      expect(move(fields, 'mono', -1).fields.map((f) => f.key).slice(0, 3)).toEqual(['history', 'mono', 'ulcer']);
      expect(move(fields, 'history', -1).fields[0].key).toBe('history');
    });

    it('refuses a move that puts a show-if source below its dependant', () => {
      expect(move(fields, 'ulcer', 1).message).toBeNull();
      expect(move(fields, 'now', 1).message).toContain('Ulcer site, size and depth');
      const swapped = move(fields, 'site', -1);
      expect(swapped.message).toContain('Ulcer site, size and depth');
      expect(swapped.fields.map((f) => f.key)).toEqual(fields.map((f) => f.key));
    });

    it('refuses a drop that breaks a rule, and accepts one that does not', () => {
      expect(moveTo(fields, 'site', 0).message).toContain('must stay below');
      expect(moveTo(fields, 'mono', 1).fields[1].key).toBe('mono');
    });
  });

  describe('deleting', () => {
    it('is refused while another field uses it, and names the users', () => {
      expect(usersOf(fields, 'now').map((f) => f.key)).toEqual(['site', 'risk']);
      const message = deleteBlockedMessage(fields, 'now')!;
      expect(message).toContain('“Ulcer site, size and depth”');
      expect(message).toContain('“Foot risk score”');
    });

    it('is allowed when nothing uses it', () => {
      expect(deleteBlockedMessage(fields, 'sig')).toBeNull();
      expect(removeField(fields, 'sig').map((f) => f.key)).not.toContain('sig');
    });

    it('sees a score source, a points rule and a band source as uses', () => {
      expect(usersOf(fields, 'mono').map((f) => f.key)).toEqual(['risk']);
      expect(usersOf(fields, 'risk').map((f) => f.key)).toEqual(['cat']);
    });
  });

  describe('sources', () => {
    it('lists the earlier choice fields a show-if can use', () => {
      const index = fields.findIndex((f) => f.key === 'site');
      expect(showIfCandidates(fields, index).map((f) => f.key)).toEqual(['ulcer', 'vib', 'pulses', 'now']);
      expect(showIfCandidates(fields, 0)).toEqual([]);
    });

    it('points a rule at the first option of the source', () => {
      expect(showIfFor(fields.find((f) => f.key === 'now'))).toEqual({ fieldKey: 'now', optionKey: 'yes' });
      expect(showIfFor(undefined)).toBeNull();
    });

    it('lists the fields whose option scores a Score can add', () => {
      expect(scoreSourceCandidates(fields).map((f) => f.key)).toEqual(['ulcer', 'vib', 'pulses', 'now']);
    });
  });

  describe('options and scores', () => {
    it('adds an option with a new key and a zero score', () => {
      const f = ff({ key: 'x', type: 'SINGLE_CHOICE', options: [{ key: 'option_1', label: 'A', score: 0 }, { key: 'option_3', label: 'C', score: 0 }] });
      const added = addOption(f);
      expect(added).toHaveLength(3);
      expect(added[2].score).toBe(0);
      expect(new Set(added.map((o) => o.key)).size).toBe(3);
    });

    it('keeps a score between -100 and 100', () => {
      expect(stepScore(100, 1)).toBe(100);
      expect(stepScore(-100, -1)).toBe(-100);
      expect(stepScore(0, 1)).toBe(1);
    });
  });

  describe('state chip and departments', () => {
    it('writes the chip words', () => {
      expect(chipText('DRAFT', 3)).toBe('v3 · draft');
      expect(chipText('WAITING_APPROVAL', 3)).toBe('v3 · waiting approval');
      expect(chipText('LIVE', 3)).toBe('v3 · live');
    });

    it('writes where a form is used', () => {
      const names = new Map([['gm', 'General Medicine']]);
      expect(departmentsText({ departmentKeys: ['gm', 'zz'], places: ['EMERGENCY', 'IPD_WARD'] }, names)).toBe('General Medicine, zz, Emergency, IPD wards');
      expect(departmentsText({ departmentKeys: [], places: [] }, names)).toBe('No department yet');
    });
  });

  describe('before sending for approval or publishing', () => {
    it('needs a field', () => {
      expect(publishProblem('Form', settings(), [])).toBe(NO_FIELD_TEXT);
    });

    it('needs a department or place', () => {
      const s = settings({ useIn: { departmentKeys: [], places: [] } });
      expect(publishProblem('Form', s, fields)).toBe(NO_DEPARTMENT_TEXT);
      expect(NO_DEPARTMENT_TEXT).toBe('Pick at least one department in Form settings first.');
      expect(publishProblem('Form', settings({ useIn: { departmentKeys: [], places: ['EMERGENCY'] } }), fields)).toBeNull();
    });

    it('needs bands in rising order, each with a label, from a Score', () => {
      const bad = fields.map((f) => (f.key === 'cat' ? { ...f, band: { sourceFieldKey: 'risk', bands: [{ from: 3, label: 'High' }, { from: 1, label: 'Mid' }] } } : f));
      expect(publishProblem('Form', settings(), bad)).toContain('“Risk category”');
      const noSource = fields.map((f) => (f.key === 'cat' ? { ...f, band: { sourceFieldKey: '', bands: [{ from: 0, label: 'Low' }] } } : f));
      expect(publishProblem('Form', settings(), noSource)).toContain('needs a score');
    });

    it('passes a good form', () => {
      expect(publishProblem('Diabetic foot examination', settings(), fields)).toBeNull();
    });

    it('needs a name', () => {
      expect(publishProblem('  ', settings(), fields)).toBe('Give the form a name.');
    });
  });

  describe('problems in the draft', () => {
    it('asks for a label and for an option on a choice field', () => {
      const bad = [ff({ key: 'a', type: 'SHORT_TEXT', label: ' ' }), ff({ key: 'b', type: 'DROPDOWN', label: 'B', options: [] })];
      expect(fieldProblems(bad)).toEqual({ a: 'Give the field a label.', b: 'Add at least one option.' });
      expect(fieldProblems(fields)).toEqual({});
    });
  });

  it('knows when the draft differs from the saved form', () => {
    const view = formView();
    expect(isDirty(view, view.definition.name, view.definition.settings, view.definition.fields)).toBe(false);
    expect(isDirty(view, 'Other', view.definition.settings, view.definition.fields)).toBe(true);
    expect(isDirty(view, view.definition.name, view.definition.settings, [])).toBe(true);
  });
});
