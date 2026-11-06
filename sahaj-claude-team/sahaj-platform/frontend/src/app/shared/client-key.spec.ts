import { newClientKey } from './client-key';

describe('newClientKey', () => {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  it('makes version 4 UUIDs, also without crypto.randomUUID', () => {
    expect(newClientKey()).toMatch(uuid);
    const original = globalThis.crypto.randomUUID;
    Object.defineProperty(globalThis.crypto, 'randomUUID', { value: undefined, configurable: true });
    try {
      expect(newClientKey()).toMatch(uuid);
      expect(newClientKey()).not.toBe(newClientKey());
    } finally {
      Object.defineProperty(globalThis.crypto, 'randomUUID', { value: original, configurable: true });
    }
  });
});
