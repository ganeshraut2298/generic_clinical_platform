import { InjectionToken } from '@angular/core';

/**
 * Base path of the REST API, for example `/api/v1`. Services add the module and the route,
 * for example `${base}/platform/public/sales-enquiries`.
 * The default is a relative path, so the dev server proxy (proxy.conf.json) and a production
 * reverse proxy both work without code changes. Override it in `app.config.ts` if needed.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => '/api/v1',
});
