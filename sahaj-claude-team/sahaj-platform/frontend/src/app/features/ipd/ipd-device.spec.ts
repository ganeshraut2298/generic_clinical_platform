import { IPD_DEVICE_STORAGE_KEY, ipdDeviceId } from './ipd-device';

describe('ipdDeviceId', () => {
  beforeEach(() => localStorage.clear());

  it('makes an id once and keeps it', () => {
    const first = ipdDeviceId();
    expect(first.length).toBeGreaterThan(8);
    expect(localStorage.getItem(IPD_DEVICE_STORAGE_KEY)).toBe(first);
    expect(ipdDeviceId()).toBe(first);
  });

  it('reuses an id already in storage', () => {
    localStorage.setItem(IPD_DEVICE_STORAGE_KEY, 'device-abc');
    expect(ipdDeviceId()).toBe('device-abc');
  });
});
