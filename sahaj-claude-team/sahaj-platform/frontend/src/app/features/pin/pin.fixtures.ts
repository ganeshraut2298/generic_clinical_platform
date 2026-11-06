import { PinStatus } from './pin.model';

/** Test data in the shape of docs/api/platform.yaml. */
export const statusOf = (over: Partial<PinStatus> = {}): PinStatus => ({
  available: true,
  state: 'SET',
  setAt: '2026-11-06T05:35:00Z',
  lockedUntil: null,
  triesLeft: 5,
  notSetReason: null,
  mobileMasked: '••••••7410',
  ...over,
});

export const NOT_SET = statusOf({ state: 'NOT_SET', setAt: null, triesLeft: null });
export const LOCKED = statusOf({
  state: 'LOCKED',
  lockedUntil: '2026-11-06T05:50:00Z',
  triesLeft: 0,
});
