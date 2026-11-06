import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { API_BASE_URL } from './core/config/api-config';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
} from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { authInterceptor } from './core/auth/auth.interceptor';
import { problemDetailInterceptor } from './core/http/problem-detail.interceptor';
import { adminAuthInterceptor } from './features/admin/auth/admin-auth.interceptor';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // In development the page calls the backend directly on port 8080 (the backend allows the
    // dev origin through CORS). A production build keeps the relative path, for a reverse proxy.
    { provide: API_BASE_URL, useValue: isDevMode() ? 'http://localhost:8080/api/v1' : '/api/v1' },
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' }),
    ),
    // problemDetailInterceptor is outermost so every caller still sees an ApiError;
    // authInterceptor sits closer to the backend so it sees the raw 401 to retry on.
    // adminAuthInterceptor acts only on /api/v1/admin/**; authInterceptor never touches those URLs.
    provideHttpClient(
      withInterceptors([problemDetailInterceptor, adminAuthInterceptor, authInterceptor]),
    ),
    // Silent refresh on app load, from the HttpOnly refresh cookie. Never blocks on failure.
    provideAppInitializer(() => {
      const auth = inject(AuthService);
      return auth.initialLoad();
    }),
  ],
};
