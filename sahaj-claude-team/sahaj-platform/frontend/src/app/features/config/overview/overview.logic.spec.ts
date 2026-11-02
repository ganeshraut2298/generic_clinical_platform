import { SetupCheck } from '../platform/clinic.model';
import { fixTarget, sortChecks, summarise, tileSummaries, todoBySection } from './overview.logic';

const CHECKS: SetupCheck[] = [
  { key: 'abdm', label: 'ABDM facility ID', done: false, optional: true },
  { key: 'profile', label: 'Clinic name, address and phone', done: true },
  { key: 'reg', label: "Doctors' registration numbers", done: false },
  { key: 'services', label: 'Service price list', done: false },
  { key: 'holidays', label: 'Holidays for the coming month', done: false },
];

describe('overview logic', () => {
  it('sorts undone first and optional last', () => {
    expect(sortChecks(CHECKS).map((c) => c.key)).toEqual(['reg', 'services', 'holidays', 'abdm', 'profile']);
  });

  it('counts essentials, leaving out optional ones', () => {
    expect(summarise(CHECKS)).toEqual({ required: 4, done: 1, left: 3 });
  });

  it('points each check to its section and panel', () => {
    expect(fixTarget(CHECKS[2])).toEqual({ section: 'team', fragment: 'f-docs' });
    expect(fixTarget(CHECKS[3])).toEqual({ section: 'fees', fragment: 'f-serv' });
    expect(fixTarget(CHECKS[4])).toEqual({ section: 'profile', fragment: 'f-hol' });
    expect(fixTarget({ key: 'zzz', label: 'Something else' })).toBeNull();
  });

  it('counts what is left to do for each section tile', () => {
    expect(todoBySection(CHECKS)).toEqual({ team: 1, fees: 1, profile: 1 });
  });

  it('writes one line for each built tile that loaded', () => {
    const lines = tileSummaries({
      profile: { name: 'Shree Sai', area: 'Ravet, Pune', specialtyCode: 'gp' },
      holidays: [{ date: '2026-11-09', name: 'Diwali', type: 'CLOSED' }],
      members: null,
      specialties: [{ code: 'gp', name: 'General practice', phase: 1 }],
      fees: { feeTiming: 'after', followUpDays: 6, followUpFee: 'free', discountLimits: {}, upiOnBill: true },
      paper: { size: 'A5', secondLanguage: 'mr', preprinted: false, caps: false, sections: { rx: true } },
    });
    expect(lines['profile']).toBe('Ravet, Pune · 1 holiday set');
    expect(lines['specialty']).toBe('General practice');
    expect(lines['fees']).toBe('Fee after consultation · free follow-up 6 days');
    expect(lines['paper']).toBe('A5 · English + मराठी');
    expect(lines['team']).toBeUndefined();
  });
});
