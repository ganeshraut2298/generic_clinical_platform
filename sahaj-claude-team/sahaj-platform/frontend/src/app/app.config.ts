import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
} from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { authInterceptor } from './core/auth/auth.interceptor';
import { problemDetailInterceptor } from './core/http/problem-detail.interceptor';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),
    // problemDetailInterceptor is outermost so every caller still sees an ApiError;
    // authInterceptor sits closer to the backend so it sees the raw 401 to retry on.
    provideHttpClient(withInterceptors([problemDetailInterceptor, authInterceptor])),
    // Silent refresh on app load, from the HttpOnly refresh cookie. Never blocks on failure.
    provideAppInitializer(() => {
      const auth = inject(AuthService);
      return auth.initialLoad();
    }),
  ],
};
