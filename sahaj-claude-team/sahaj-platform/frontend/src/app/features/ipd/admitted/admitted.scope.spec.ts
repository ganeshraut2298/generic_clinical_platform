import { AdmissionScope } from '../ipd.model';
import { AdmittedViewState } from './admitted-view.state';
import { ABILITIES_NURSE, CLINICAL_QUIET, WARD_A, listOf, rowOf } from './admitted.fixtures';
import {
  NO_WARDS_TEXT,
  NO_WARD_PATIENTS_TEXT,
  cardFlags,
  filterRows,
  foldFlags,
  isScoped,
  scopeChipText,
  scopeTooltip,
  scopedEmptyText,
  showMyPatients,
  showScopeChip,
} from './admitted.model';

const WARDS: AdmissionScope = { kind: 'WARDS', visible: 3, admitted: 12, hasWard: true, canOpenEmergencyAccess: true };
const ALL: AdmissionScope = { kind: 'ALL', visible: 12, admitted: 12, canOpenEmergencyAccess: false };

describe('SAH-34 ward scope helpers (admitted.model)', () => {
  describe('the chip (AC 26 to 28, 41)', () => {
    it('reads "Patients in their wards · N of M" from the server numbers', () => {
      expect(scopeChipText(WARDS)).toBe('Patients in their wards · 3 of 12');
      expect(scopeChipText({ visible: 0, admitted: 12 })).toBe('Patients in their wards · 0 of 12');
    });

    it('has the full sentence as tooltip, ending with the Clinic setup line and not the Roles and access pointer', () => {
      const tip = scopeTooltip(WARDS);
      expect(tip).toBe('As Nurse you see patients in their wards (3 of 12). The owner sets your wards in Clinic setup.');
      expect(tip).not.toContain('Roles and access');
    });

    it('shows for a scoped user when somebody is admitted, and never for scope all', () => {
      expect(showScopeChip(WARDS)).toBe(true);
      expect(showScopeChip(ALL)).toBe(false);
      expect(showScopeChip({ ...WARDS, visible: 0, admitted: 0 })).toBe(false);
      expect(showScopeChip(undefined)).toBe(false);
      expect(isScoped(WARDS)).toBe(true);
      expect(isScoped(ALL)).toBe(false);
    });
  });

  describe('"My patients" (AC 32)', () => {
    it('is for OWNER and DOCTOR, not for FRONT_DESK', () => {
      expect(showMyPatients('OWNER', ALL)).toBe(true);
      expect(showMyPatients('DOCTOR', ALL)).toBe(true);
      expect(showMyPatients('FRONT_DESK', ALL)).toBe(false);
    });

    it('is for a nurse only when she has a live ward, switch on or off', () => {
      expect(showMyPatients('NURSE', WARDS)).toBe(true);
      expect(showMyPatients('NURSE', { ...WARDS, hasWard: false })).toBe(false);
      expect(showMyPatients('NURSE', { ...ALL, hasWard: true })).toBe(true);
      expect(showMyPatients('NURSE', ALL)).toBe(false);
      expect(showMyPatients('NURSE', undefined)).toBe(false);
    });
  });

  describe('the empty texts (AC 29, 30)', () => {
    it('gives the zero-ward text when the nurse has no live ward', () => {
      const list = listOf({ items: [], scope: { ...WARDS, visible: 0, hasWard: false } });
      expect(scopedEmptyText(list)).toBe(NO_WARDS_TEXT);
      expect(NO_WARDS_TEXT).toBe(
        'No wards are assigned to you, so no patients are shown. Ask the owner to assign your ward. If a patient needs you now, use Emergency access.',
      );
    });

    it('gives "No patients in your wards right now." when she has wards but nobody is in them', () => {
      const list = listOf({ items: [], scope: { ...WARDS, visible: 0 } });
      expect(scopedEmptyText(list)).toBe(NO_WARD_PATIENTS_TEXT);
      expect(NO_WARD_PATIENTS_TEXT).toBe('No patients in your wards right now.');
    });

    it('gives nothing when there are rows, when the scope is all, or when nobody is admitted at all', () => {
      expect(scopedEmptyText(listOf({ scope: WARDS }))).toBeNull();
      expect(scopedEmptyText(listOf({ items: [], scope: { ...ALL, visible: 0, admitted: 0 } }))).toBeNull();
      expect(scopedEmptyText(listOf({ items: [], scope: { ...WARDS, visible: 0, admitted: 0 } }))).toBeNull();
    });
  });

  describe('filterRows with "My patients" (AC 33, 34)', () => {
    const rows = [
      rowOf({ id: 'a1', mine: true }),
      rowOf({ id: 'a2', mine: false, patientName: 'Ravi Shah', wardId: 'w-b' }),
      rowOf({ id: 'a3', patientName: 'Sunita More' }),
    ];

    it('keeps only the rows the server marked mine', () => {
      expect(filterRows(rows, '', 'all', true).map((r) => r.id)).toEqual(['a1']);
      expect(filterRows(rows, '', 'all', false).map((r) => r.id)).toEqual(['a1', 'a2', 'a3']);
    });

    it('combines with the search and the ward (all must match)', () => {
      expect(filterRows(rows, 'asha', 'all', true).map((r) => r.id)).toEqual(['a1']);
      expect(filterRows(rows, 'ravi', 'all', true)).toEqual([]);
      expect(filterRows(rows, '', 'w-b', true)).toEqual([]);
      expect(filterRows(rows, '', WARD_A, true).map((r) => r.id)).toEqual(['a1']);
    });
  });

  describe('the card flag Emergency access (AC 51, 52, 63)', () => {
    const grant = { expiresAt: '2026-10-24T06:05:00Z' }; // 11:35 AM IST

    it('is grey with the key icon, the accessible text and the end time from the server', () => {
      const flags = cardFlags(rowOf({ emergencyAccess: grant }), ABILITIES_NURSE);
      const f = flags.find((x) => x.kind === 'emergency-access')!;
      expect(f.text).toBe('Emergency access');
      expect(f.tone).toBe('grey');
      expect(f.icon).toBe('key');
      expect(f.title).toBe('Emergency access until 11:35 AM IST');
    });

    it('is absent on a row without a grant', () => {
      expect(cardFlags(rowOf(), ABILITIES_NURSE).some((x) => x.kind === 'emergency-access')).toBe(false);
    });

    it('comes after the escalation flag and before the allergy and EWS flags, and the safety flags still show', () => {
      const row = rowOf({
        emergencyAccess: grant,
        clinical: {
          ...CLINICAL_QUIET,
          escalation: { status: 'OPEN', waitingMinutes: 5, targetMinutes: 15, pastTarget: false },
          allergy: { status: 'RECORDED', text: 'Latex' },
          latestEws: { score: 8, risk: 'HIGH', recordedAt: '2026-10-24T06:00:00Z' },
        },
      });
      expect(cardFlags(row, ABILITIES_NURSE).map((f) => f.kind)).toEqual([
        'escalation',
        'emergency-access',
        'allergy',
        'ews',
      ]);
    });

    it('is never folded into the "+N" chip', () => {
      const row = rowOf({ emergencyAccess: grant, goingHomeToday: true });
      const folded = foldFlags(cardFlags(row, ABILITIES_NURSE));
      expect(folded.shown.map((f) => f.kind)).toContain('emergency-access');
      expect(folded.folded.map((f) => f.kind)).not.toContain('emergency-access');
    });
  });
});

describe('AdmittedViewState: My patients (AC 33)', () => {
  it('starts off, counts as a filter, and starts the list again at one page', () => {
    const state = new AdmittedViewState();
    expect(state.mine()).toBe(false);
    expect(state.filtering()).toBe(false);
    state.showAll();
    state.setMine(true);
    expect(state.mine()).toBe(true);
    expect(state.filtering()).toBe(true);
    expect(state.limit()).toBe(24);
    state.setMine(false);
    expect(state.filtering()).toBe(false);
  });
});
