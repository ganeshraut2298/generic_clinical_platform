import { canEditBookingRules, canEditSlotSettings, canManageAppointments } from './appointments-role.util';

describe('appointments-role.util', () => {
  it('lets only owner and front desk manage appointments', () => {
    expect(canManageAppointments('OWNER')).toBe(true);
    expect(canManageAppointments('FRONT_DESK')).toBe(true);
    expect(canManageAppointments('NURSE')).toBe(false);
    expect(canManageAppointments('DOCTOR')).toBe(false);
    expect(canManageAppointments(undefined)).toBe(false);
  });

  it('lets only owner and front desk edit a doctor slot template', () => {
    expect(canEditSlotSettings('OWNER')).toBe(true);
    expect(canEditSlotSettings('FRONT_DESK')).toBe(true);
    expect(canEditSlotSettings('VISITING_DOCTOR')).toBe(false);
  });

  it('lets only the owner change booking rules, including overbook', () => {
    expect(canEditBookingRules('OWNER')).toBe(true);
    expect(canEditBookingRules('FRONT_DESK')).toBe(false);
  });
});
