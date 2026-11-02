import { computeSlots, SlotTemplate } from './slot.util';

describe('computeSlots', () => {
  const template: SlotTemplate = {
    slotMinutes: 15,
    workingDays: [1, 2, 3, 4, 5, 6, 0],
    walkInEveryNth: 4,
    sessions: [
      { startTime: '09:00', endTime: '09:45' },
      { startTime: '17:00', endTime: '17:30' },
    ],
  };

  it('returns null on a day the doctor does not work', () => {
    const closed: SlotTemplate = { ...template, workingDays: [1, 2, 3, 4, 5] };
    // 24 Oct 2026 is a Saturday (6).
    expect(computeSlots(closed, '2026-10-24')).toBeNull();
  });

  it('builds slots per session at the configured length, marking every Nth as a walk-in slot', () => {
    const slots = computeSlots(template, '2026-10-24');
    expect(slots).toEqual([
      { time: '09:00', sessionIndex: 0, walkIn: false },
      { time: '09:15', sessionIndex: 0, walkIn: false },
      { time: '09:30', sessionIndex: 0, walkIn: false },
      { time: '17:00', sessionIndex: 1, walkIn: false },
      { time: '17:15', sessionIndex: 1, walkIn: false },
    ]);
  });

  it('marks the 4th, 8th... slot in each session as kept for walk-ins when walkInEveryNth is 4', () => {
    const longSession: SlotTemplate = {
      slotMinutes: 10,
      workingDays: [0, 1, 2, 3, 4, 5, 6],
      walkInEveryNth: 4,
      sessions: [{ startTime: '09:00', endTime: '10:00' }],
    };
    const slots = computeSlots(longSession, '2026-10-24')!;
    expect(slots.map((s) => s.walkIn)).toEqual([false, false, false, true, false, false]);
  });

  it('reserves no slots for walk-ins when walkInEveryNth is 0', () => {
    const noWalkIn: SlotTemplate = { ...template, walkInEveryNth: 0 };
    const slots = computeSlots(noWalkIn, '2026-10-24')!;
    expect(slots.every((s) => !s.walkIn)).toBe(true);
  });

  it('drops a trailing slot that would run past the session end time', () => {
    const uneven: SlotTemplate = {
      slotMinutes: 20,
      workingDays: [0, 1, 2, 3, 4, 5, 6],
      walkInEveryNth: 0,
      sessions: [{ startTime: '09:00', endTime: '09:50' }],
    };
    const slots = computeSlots(uneven, '2026-10-24')!;
    expect(slots.map((s) => s.time)).toEqual(['09:00', '09:20']);
  });
});
