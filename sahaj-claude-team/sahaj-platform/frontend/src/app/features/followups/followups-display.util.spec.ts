import {
  doctorShortName,
  followUpPillClass,
  followUpPillText,
  followUpSeverity,
} from './followups-display.util';
import { FollowUp } from './followups.model';

function item(over: Partial<FollowUp>): FollowUp {
  return {
    id: 'f1',
    patientId: 'p1',
    patientName: 'Rekha Shinde',
    doctorMembershipId: 'm1',
    doctorName: 'Dr. Asha Kulkarni',
    dueDate: '2026-10-24',
    reason: 'BP review',
    status: 'DUE',
    sourceVisitId: 'v1',
    sourceAdmissionId: null,
    ...over,
  };
}

describe('follow-up display helpers', () => {
  it('builds the pill text for each status', () => {
    expect(followUpPillText(item({ status: 'DUE' }))).toBe('Today');
    expect(followUpPillText(item({ status: 'MISSED', daysLate: 3 }))).toBe('3 days late');
    expect(followUpPillText(item({ status: 'MISSED', daysLate: 1 }))).toBe('1 day late');
    expect(followUpPillText(item({ status: 'UPCOMING' }))).toBe('24 Oct 2026');
    expect(followUpPillText(item({ status: 'RETURNED' }))).toBe('Came back');
  });

  it('maps severity and pill class', () => {
    expect(followUpSeverity('MISSED')).toBe('crit');
    expect(followUpSeverity('DUE')).toBe('warn');
    expect(followUpSeverity('UPCOMING')).toBe('info');
    expect(followUpPillClass('MISSED')).toBe('p-crit');
  });

  it('shortens the doctor name', () => {
    expect(doctorShortName('Dr. Asha Kulkarni')).toBe('Dr. Asha');
  });
});
