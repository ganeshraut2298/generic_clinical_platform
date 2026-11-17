import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { API_BASE_URL } from '../../core/config/api-config';
import { IPD_DEVICE_HEADER, ipdDeviceId } from './ipd-device';

function isIpdCall(url: string, apiBase: string): boolean {
  try {
    const origin = globalThis.location?.origin ?? 'http://localhost';
    const base = new URL(`${apiBase}/ipd`, origin);
    const target = new URL(url, origin);
    return (
      base.origin === target.origin &&
      (target.pathname === base.pathname || target.pathname.startsWith(`${base.pathname}/`))
    );
  } catch {
    return false;
  }
}

/** Adds X-Sahaj-Device to `/api/v1/ipd/**` calls only, so the audit trail can name the device. */
export const ipdDeviceInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isIpdCall(req.url, inject(API_BASE_URL))) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { [IPD_DEVICE_HEADER]: ipdDeviceId() } }));
};
