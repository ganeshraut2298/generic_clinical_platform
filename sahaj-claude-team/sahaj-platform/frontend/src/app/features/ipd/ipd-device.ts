/** Name of the request header that carries the device id on IPD calls (SAH-76, ADR-027). */
export const IPD_DEVICE_HEADER = 'X-Sahaj-Device';
export const IPD_DEVICE_STORAGE_KEY = 'sahaj.ipd.device';

function newId(): string {
  const c = globalThis.crypto;
  if (c?.randomUUID) {
    return c.randomUUID();
  }
  return `dev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

let memoryId: string | null = null;

/**
 * Returns the id of this browser, made once and kept in local storage. It names a device, never a
 * person. When storage is blocked the id lives only for this page load.
 */
export function ipdDeviceId(): string {
  try {
    const stored = globalThis.localStorage?.getItem(IPD_DEVICE_STORAGE_KEY);
    if (stored) {
      return stored;
    }
    const made = memoryId ?? newId();
    globalThis.localStorage?.setItem(IPD_DEVICE_STORAGE_KEY, made);
    memoryId = made;
    return made;
  } catch {
    memoryId ??= newId();
    return memoryId;
  }
}
