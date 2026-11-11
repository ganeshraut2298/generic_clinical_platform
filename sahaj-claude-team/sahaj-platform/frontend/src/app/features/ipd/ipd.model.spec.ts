import { formatInrPaise } from '../../shared/format/inr';
import {
  AdmissionRow,
  BoardBed,
  bedAriaLabel,
  AdmissionRulesInput,
  RunningBill,
  billNote,
  chargePricePaise,
  admissionSubline,
  depositHint,
  ipdLead,
  minimumDepositPaise,
  PACKAGE_INCLUDES_TEXT,
  packageChip,
  parseWholeRupees,
  statusPills,
  transferOptionLabel,
  transferToast,
} from './ipd.model';

const RULES: AdmissionRulesInput = {
  depositPaise: { planned: 1000000, emergency: 500000, daycare: 300000, maternity: 1500000 },
  categoryAddPaise: { gen: 0, semi: 200000, pvt: 500000, icu: 1000000 },
  emergencyWithoutDeposit: true,
  alertPercent: 80,
};

const ROW: AdmissionRow = {
  id: 'a1',
  ipNumber: 'IP/26-27/0042',
  patientId: 'p1',
  patientName: 'Asha Patil',
  age: 56,
  sex: 'M',
  doctorName: 'Dr. Meera',
  bedCode: '101-A',
  categoryName: 'General',
  wardName: 'General ward',
  admissionType: 'emergency',
  payer: 'Self',
  dayNumber: 3,
  depositPaise: 1000000,
  minimumDepositPaise: 500000,
  runningBillPaise: 300000,
  depositLow: false,
  depositState: 'OK',
  admittedAt: '2026-10-24T05:35:00Z',
};

describe('ipd model', () => {
  it('adds the base deposit of the type and the add-on of the bed category', () => {
    expect(minimumDepositPaise(RULES, 'planned', 'gen')).toBe(1000000);
    expect(minimumDepositPaise(RULES, 'planned', 'icu')).toBe(2000000);
    expect(minimumDepositPaise(RULES, 'maternity', 'semi')).toBe(1700000);
    expect(minimumDepositPaise(RULES, 'daycare', null)).toBe(300000);
  });

  it('builds the deposit hint from the rules', () => {
    const base = { type: 'planned', categoryName: 'General', emergencyWithoutDeposit: true, payer: 'Self', format: formatInrPaise } as const;
    expect(depositHint({ ...base, minimumPaise: 1000000 })).toBe('Minimum ₹10,000 · planned, General');
    expect(depositHint({ ...base, minimumPaise: 500000, type: 'emergency' })).toBe(
      'Minimum ₹5,000 · emergency, General · emergency can be admitted first',
    );
    expect(depositHint({ ...base, minimumPaise: 500000, type: 'emergency', emergencyWithoutDeposit: false })).toBe(
      'Minimum ₹5,000 · emergency, General',
    );
    expect(depositHint({ ...base, minimumPaise: 1000000, payer: 'Insurance' })).toBe(
      'Minimum ₹10,000 · planned, General · not needed for insurance',
    );
    expect(depositHint({ ...base, minimumPaise: 300000, type: 'daycare', payer: 'Corporate' })).toContain(
      'day-care, General · not needed for corporate',
    );
  });

  it('writes the line under the patient name', () => {
    expect(admissionSubline(ROW)).toBe('IP/26-27/0042 · 56 M · Emergency · day 3');
    expect(admissionSubline({ ...ROW, age: null, sex: null })).toBe('IP/26-27/0042 · Emergency · day 3');
  });

  it('chooses the status pill: payer, then Deposit low, then Deposit OK', () => {
    expect(statusPills(ROW)).toEqual([{ text: 'Deposit OK', css: 'p-good' }]);
    expect(statusPills({ ...ROW, depositLow: true })).toEqual([{ text: 'Deposit low', css: 'p-crit' }]);
    expect(statusPills({ ...ROW, depositState: 'DUE', depositPaise: 0 })).toEqual([
      { text: 'Deposit OK', css: 'p-good' },
    ]);
    expect(statusPills({ ...ROW, payer: 'Insurance', depositState: 'NOT_NEEDED', depositPaise: 0 })).toEqual([
      { text: 'Insurance', css: 'p-blue' },
    ]);
    expect(statusPills({ ...ROW, payer: 'Corporate', depositPaise: 200000, depositLow: true })).toEqual([
      { text: 'Corporate', css: 'p-blue' },
    ]);
  });

  it('prices an extra charge after the category multiplier, rounded half-up to whole paise', () => {
    expect(chargePricePaise(50000, 1)).toBe(50000);
    expect(chargePricePaise(50000, 1.5)).toBe(75000);
    expect(chargePricePaise(15001, 1.5)).toBe(22502);
    expect(chargePricePaise(35000, 1.05)).toBe(36750);
  });

  it('writes the note of the Running bill panel', () => {
    const bill: RunningBill = {
      billDays: 2,
      bedDayCut: 'midnight',
      categoryName: 'General',
      multiplier: 1,
      lines: [],
      totalPaise: 0,
      paidPaise: 0,
      depositPercent: null,
    };
    expect(billNote(bill)).toBe('Bed day counted at midnight');
    expect(billNote({ ...bill, bedDayCut: '24h', categoryName: 'ICU', multiplier: 2 })).toBe(
      'Bed day counted every 24 hours · ICU rate ×2 on services',
    );
  });

  it('reads whole rupees only', () => {
    expect(parseWholeRupees('10,000')).toBe(10000);
    expect(parseWholeRupees(' 0 ')).toBe(0);
    expect(parseWholeRupees('1000000')).toBe(1000000);
    expect(parseWholeRupees('1000001')).toBeNull();
    expect(parseWholeRupees('12.5')).toBeNull();
    expect(parseWholeRupees('abc')).toBeNull();
    expect(parseWholeRupees('')).toBeNull();
  });

  it('names a bed tile for screen readers, with the patient only when occupied', () => {
    const bed: BoardBed = { id: 'b1', code: '101-A', categoryKey: 'gen', categoryName: 'General', ratePaise: 80000, status: 'occ', patientName: 'Sunil Jadhav' };
    expect(bedAriaLabel(bed)).toBe('Bed 101-A, General, Occupied, Sunil Jadhav');
    expect(bedAriaLabel({ ...bed, depositLow: true })).toBe('Bed 101-A, General, Occupied, Sunil Jadhav, deposit low');
    expect(bedAriaLabel({ ...bed, status: 'free', patientName: null })).toBe('Bed 101-A, General, Available');
    expect(bedAriaLabel({ ...bed, status: 'blk' })).toBe('Bed 101-A, General, Blocked');
  });

  it('builds the lead and leaves out a part the server did not send', () => {
    expect(ipdLead('midnight', 80)).toBe(
      'Beds and admissions · Bed and nursing charges post at midnight · deposit alert at 80% of the deposit',
    );
    expect(ipdLead('24h', null)).toBe('Beds and admissions · Bed and nursing charges post every 24 hours');
    expect(ipdLead(undefined, undefined)).toBe('Beds and admissions');
    expect(ipdLead(undefined, Number.NaN)).not.toMatch(/undefined|NaN/);
  });

  it('labels a free bed in the Move select', () => {
    const bed = { id: '5', code: '101-B', wardName: 'W', categoryKey: 'gen' as const, categoryName: 'General', ratePaise: 80000 };
    expect(transferOptionLabel(bed, formatInrPaise)).toBe('101-B · General · ₹800');
  });

  it('words the transfer toast for both rate rules', () => {
    const args = { patientName: 'Asha Patil', fromCode: '101-A', toCode: '201-A', categoryName: 'Private' };
    expect(transferToast({ ...args, newRateFrom: 'same' })).toBe(
      'Asha Patil moved from 101-A to 201-A · Private rate from today · 101-A sent for cleaning',
    );
    expect(transferToast({ ...args, newRateFrom: 'next' })).toContain('Private rate from tomorrow');
  });

  it('shows only the teal Going home today pill when the patient goes home today', () => {
    const home = [{ text: 'Going home today', css: 'p-teal' }];
    expect(statusPills({ ...ROW, goingHomeToday: true })).toEqual(home);
    expect(statusPills({ ...ROW, goingHomeToday: true, depositLow: true })).toEqual(home);
    expect(statusPills({ ...ROW, goingHomeToday: true, payer: 'Corporate' })).toEqual(home);
    expect(statusPills({ ...ROW, goingHomeToday: false })).toEqual([{ text: 'Deposit OK', css: 'p-good' }]);
    expect(statusPills({ ...ROW, goingHomeToday: false, payer: 'Insurance' })).toEqual([
      { text: 'Insurance', css: 'p-blue' },
    ]);
  });

  it('puts going home today in the bed label instead of deposit low', () => {
    const bed: BoardBed = { id: 'b1', code: '101-A', categoryKey: 'gen', categoryName: 'General', ratePaise: 80000, status: 'occ', patientName: 'Sunil Jadhav', goingHomeToday: true, depositLow: true };
    expect(bedAriaLabel(bed)).toBe('Bed 101-A, General, Occupied, Sunil Jadhav, going home today');
  });
});

describe('package chip (SAH-29)', () => {
  it('writes the days included, and Day-care for none', () => {
    expect(packageChip(3)).toBe('3 days');
    expect(packageChip(60)).toBe('60 days');
    expect(packageChip(0)).toBe('Day-care');
  });

  it('keeps the mockup text for one day (OQ-29-4)', () => {
    expect(packageChip(1)).toBe('1 days');
  });

  it('has the fixed text of the mockup', () => {
    expect(PACKAGE_INCLUDES_TEXT).toBe('Bed, nursing and surgeon included · extra days and exclusions at normal rates');
  });
});
