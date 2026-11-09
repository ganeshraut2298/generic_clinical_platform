import {
  LABEL_CONTROL_TEXT,
  LABEL_LONG_TEXT,
  LABEL_USED_TEXT,
  canonicalLabel,
  codePointLength,
  hasControlOrBidi,
  labelProblems,
  labelShapeProblem,
  nameKey,
  normalLabel,
  stripEdges,
} from './card-label.rules';
import { CATALOGUE, standardSetup } from './card-setup.fixtures';
import { CardFieldKey, CardSetup } from './card-setup.model';

/** The setup with these labels typed, by field key. */
function withLabels(
  labels: Partial<Record<CardFieldKey, string>>,
  base = standardSetup(),
): CardSetup {
  for (const section of base.sections) {
    for (const f of section.fields) {
      if (labels[f.key] !== undefined) {
        f.label = labels[f.key]!;
      }
    }
  }
  return base;
}

const problems = (labels: Partial<Record<CardFieldKey, string>>, base?: CardSetup) =>
  Object.fromEntries(labelProblems(withLabels(labels, base), CATALOGUE));

const CODE_POINTS_40 = 'a'.repeat(40);
const EMOJI_40 = '\u{1F600}'.repeat(40);

describe('card-label.rules', () => {
  describe('hasControlOrBidi (mirror of IpdText.hasControlOrBidi)', () => {
    const refused: [string, string][] = [
      ['right-to-left override U+202E', 'Doc\u202Etor'],
      ['left-to-right mark U+200E', 'Doc\u200Etor'],
      ['isolate U+2066', '\u2066Doctor'],
      ['zero-width space U+200B', 'Doc\u200Btor'],
      ['byte order mark U+FEFF inside', 'Doc\uFEFFtor'],
      ['soft hyphen U+00AD', 'Doc\u00ADtor'],
      ['tab U+0009', 'Doc\ttor'],
      ['tab at the end', 'Doctor\t'],
      ['line feed U+000A', 'Doc\ntor'],
      ['delete U+007F', 'Doc\u007Ftor'],
      ['C1 control U+0085', 'Doc\u0085tor'],
      ['line separator U+2028', 'Doc\u2028tor'],
      ['paragraph separator U+2029', 'Doc\u2029tor'],
      ['private use U+E000', 'Doc\uE000tor'],
      ['unassigned U+0378', 'Doc\u0378tor'],
      ['lone surrogate', 'Doc\uD800tor'],
      ['tag character U+E0041', 'Doc\u{E0041}tor'],
      ['Hangul filler U+3164', 'Doc\u3164tor'],
      ['Hangul filler U+115F', 'Doc\u115Ftor'],
      ['braille blank U+2800', 'Doc\u2800tor'],
      ['Mongolian vowel separator U+180E', 'Doc\u180Etor'],
      ['combining grapheme joiner U+034F', 'Doc\u034Ftor'],
      ['variation selector U+FE0F', 'Doc\uFE0Ftor'],
      ['joiner first', '\u200DDoctor'],
      ['joiner after a plain letter', 'Do\u200Dctor'],
      ['joiner last', 'Doctor\u200D'],
      ['non-joiner after a space', 'Do \u200Cctor'],
      ['doubled joiner after a virama', '\u0915\u094D\u200D\u200D\u0937'],
      ['joiner after a virama but before a space', '\u0915\u094D\u200D '],
    ];
    it.each(refused)('refuses %s', (_name, text) => {
      expect(hasControlOrBidi(text)).toBe(true);
    });

    const allowed: [string, string][] = [
      ['plain letters', 'Consultant'],
      ['inner and no-break spaces', 'Date\u00A0in  now'],
      ['Devanagari with a joiner after the virama', '\u0915\u094D\u200D\u0937'],
      ['Devanagari with a non-joiner after the virama', '\u0915\u094D\u200C\u0937'],
      ['an emoji', 'Ward \u{1F600}'],
      ['digits and punctuation', 'Bed 2/B - west'],
      ['empty', ''],
    ];
    it.each(allowed)('allows %s', (_name, text) => {
      expect(hasControlOrBidi(text)).toBe(false);
    });
  });

  describe('stripping and the canonical label', () => {
    it.each([
      ['  Doctor  ', 'Doctor'],
      ['\u00A0\u3000Doctor\u2003', 'Doctor'],
      ['Doc  tor', 'Doc  tor'],
      ['\u00A0\u00A0', ''],
      ['', ''],
    ])('strips %j to %j (also no-break and ideographic spaces)', (raw, expected) => {
      expect(stripEdges(raw)).toBe(expected);
    });

    it('puts the label into NFC before it is stripped', () => {
      expect(normalLabel(' Cafe\u0301 ')).toBe('Caf\u00E9');
    });

    it.each([
      ['Bed', 'Bed', ''],
      ['  Bed ', 'Bed', ''],
      ['\u00A0Bed', 'Bed', ''],
      ['bed', 'Bed', 'bed'],
      ['BED', 'Bed', 'BED'],
      ['Bed  ward', 'Bed', 'Bed  ward'],
      ['', 'Bed', ''],
      ['Cot', 'Bed', 'Cot'],
    ])(
      'canonical label of %j for the field %j is %j (own name is empty, case-sensitive)',
      (raw, name, expected) => {
        expect(canonicalLabel(raw, name)).toBe(expected);
      },
    );

    it('counts code points, not UTF-16 units', () => {
      expect(codePointLength(EMOJI_40)).toBe(40);
      expect(EMOJI_40.length).toBe(80);
      expect(codePointLength('')).toBe(0);
    });
  });

  describe('nameKey (design note 4.1)', () => {
    it.each([
      ['Allergies', 'allergies'],
      ['  ALLERGIES ', 'allergies'],
      ['Admitted   on', 'admitted on'],
      ['Admitted\u00A0on', 'admitted on'],
      ['Admitted\u3000\u2003on', 'admitted on'],
      ['\u00A0UHID\u00A0', 'uhid'],
      ['Cafe\u0301', 'caf\u00E9'],
      ['CAF\u00C9', 'caf\u00E9'],
      ['Stra\u00DFe', 'strasse'],
      ['STRASSE', 'strasse'],
      ['\u03A3\u03A3', '\u03C3\u03C2'],
      ['', ''],
    ])('key of %j is %j', (text, key) => {
      expect(nameKey(text)).toBe(key);
    });

    it('does not match SpO2 with SpO\u2082 and does not map look-alike letters (accepted limit)', () => {
      expect(nameKey('SpO2')).not.toBe(nameKey('SpO\u2082'));
      expect(nameKey('\u0410')).not.toBe(nameKey('A'));
    });
  });

  describe('labelShapeProblem (checks 1 and 2, in this order)', () => {
    it.each([
      ['a bidi character', 'Doc\u202Etor', LABEL_CONTROL_TEXT],
      ['a zero-width space', 'Doc\u200Btor', LABEL_CONTROL_TEXT],
      ['40 code points', CODE_POINTS_40, null],
      ['41 code points', CODE_POINTS_40 + 'a', LABEL_LONG_TEXT],
      ['40 emoji (80 UTF-16 units)', EMOJI_40, null],
      ['41 emoji', EMOJI_40 + '\u{1F600}', LABEL_LONG_TEXT],
      ['40 code points inside spaces', `  ${CODE_POINTS_40}  `, null],
      ['41 code points inside spaces', `  ${CODE_POINTS_40}a  `, LABEL_LONG_TEXT],
      ['40 decomposed letters (40 code points after NFC)', 'e\u0301'.repeat(40), null],
      ['a raw label over 400 units', ' '.repeat(401) + 'a', LABEL_LONG_TEXT],
      [
        'too long and a bidi character: the character wins',
        CODE_POINTS_40 + 'a\u202E',
        LABEL_CONTROL_TEXT,
      ],
      ['empty', '', null],
    ])('%s', (_name, raw, expected) => {
      expect(labelShapeProblem(raw, 40)).toBe(expected);
    });
  });

  describe('labelProblems (V15 and the order of the checks)', () => {
    it('is clean for the standard setup and for sensible labels', () => {
      expect(labelProblems(standardSetup(), CATALOGUE).size).toBe(0);
      expect(problems({ doctor: 'Consultant', room: 'Cot', ward: 'Block' })).toEqual({});
    });

    it('allows a label equal to the field\u2019s own standard name, in any case or spacing', () => {
      expect(problems({ bed: 'Bed' })).toEqual({});
      expect(problems({ bed: 'bed' })).toEqual({});
      expect(problems({ bed: '  BED ' })).toEqual({});
      expect(problems({ admittedOn: 'ADMITTED   ON' })).toEqual({});
      expect(problems({ admittedOn: 'admitted\u00A0on' })).toEqual({});
    });

    it.each([
      ['the standard name of another field', { diagnosis: 'Allergies' }, 'diagnosis'],
      ['a safety field name in lower case', { ipNumber: 'uhid' }, 'ipNumber'],
      ['a name with extra and odd spaces', { room: ' Admitted\u00A0\u00A0On ' }, 'room'],
      ['the name of the patient name field', { doctor: 'PATIENT NAME' }, 'doctor'],
    ])('refuses %s', (_name, labels, field) => {
      expect(problems(labels as Partial<Record<CardFieldKey, string>>)).toEqual({
        [field]: LABEL_USED_TEXT,
      });
    });

    it('reserves the standard names of all 25 catalogue fields', () => {
      for (const target of CATALOGUE.fields) {
        const other = CATALOGUE.fields.find(
          (f) => f.key !== target.key && !f.safety && !f.required,
        )!;
        const found = problems({ [other.key]: target.name } as Partial<
          Record<CardFieldKey, string>
        >);
        expect(found, `${target.name} as the label of ${other.name}`).toEqual({
          [other.key]: LABEL_USED_TEXT,
        });
      }
      expect(CATALOGUE.fields).toHaveLength(25);
    });

    it('reserves the name of a field that is switched off, or in a section that is off', () => {
      const setup = standardSetup();
      for (const section of setup.sections) {
        for (const f of section.fields) {
          if (f.key === 'payer') {
            f.on = false;
          }
        }
      }
      expect(
        Object.fromEntries(labelProblems(withLabels({ ward: 'Payer' }, setup), CATALOGUE)),
      ).toEqual({
        ward: LABEL_USED_TEXT,
      });
      // "Billing" is a section that is off in the standard card; its fields are reserved the same way.
      expect(problems({ ward: 'Running bill' })).toEqual({ ward: LABEL_USED_TEXT });
    });

    it('gives the error to the later field only when two fields share a new label', () => {
      const found = problems({ ward: 'Block', room: 'block' });
      expect(found).toEqual({ room: LABEL_USED_TEXT });
      expect(Object.keys(found)).toHaveLength(1);
    });

    it('follows the editor\u2019s current order, so the field moved up keeps its label', () => {
      const setup = withLabels({ ward: 'Block', room: 'BLOCK' });
      const stay = setup.sections.find((s) => s.key === 'stay')!;
      stay.fields.reverse();
      const found = Object.fromEntries(labelProblems(setup, CATALOGUE));
      const first = stay.fields.findIndex((f) => f.key === 'ward');
      const second = stay.fields.findIndex((f) => f.key === 'room');
      expect(found).toEqual(first < second ? { room: LABEL_USED_TEXT } : { ward: LABEL_USED_TEXT });
    });

    it('lets the label of a field be reused after the first field is renamed', () => {
      expect(problems({ ward: 'Block', room: 'Block' })).toEqual({ room: LABEL_USED_TEXT });
      expect(problems({ ward: 'Wing', room: 'Block' })).toEqual({});
    });

    it('shows one message per box and takes the checks in order: character, length, then V15', () => {
      expect(problems({ diagnosis: 'Allergies\u202E' })).toEqual({ diagnosis: LABEL_CONTROL_TEXT });
      expect(problems({ diagnosis: 'Allergies' + 'x'.repeat(40) })).toEqual({
        diagnosis: LABEL_LONG_TEXT,
      });
      expect(problems({ diagnosis: 'Allergies' })).toEqual({ diagnosis: LABEL_USED_TEXT });
      // a field that failed a shape check is not remembered as an earlier label (as the server)
      expect(problems({ ward: 'Block\u200B', room: 'Block' })).toEqual({
        ward: LABEL_CONTROL_TEXT,
      });
    });

    it('does not check the patient name or the safety fields (their boxes are fixed)', () => {
      expect(
        problems({ name: 'Anything\u202E', allergies: 'Diagnosis', uhid: 'x'.repeat(60) }),
      ).toEqual({});
    });

    it('accepts exactly 40 code points and refuses 41', () => {
      expect(problems({ doctor: CODE_POINTS_40 })).toEqual({});
      expect(problems({ doctor: CODE_POINTS_40 + 'a' })).toEqual({ doctor: LABEL_LONG_TEXT });
      expect(problems({ doctor: EMOJI_40 })).toEqual({});
    });

    it('lists the problems in body order', () => {
      const keys = [
        ...labelProblems(
          withLabels({ room: 'Doctor', ward: 'Doctor', bed: 'a\u202E' }),
          CATALOGUE,
        ).keys(),
      ];
      const order = standardSetup().sections.flatMap((s) => s.fields.map((f) => f.key));
      expect(keys).toEqual([...keys].sort((a, b) => order.indexOf(a) - order.indexOf(b)));
      expect(keys).toHaveLength(3);
    });
  });
});
