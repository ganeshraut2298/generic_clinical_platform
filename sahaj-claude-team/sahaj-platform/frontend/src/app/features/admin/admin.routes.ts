import { Routes } from '@angular/router';
import { adminGuard, adminGuestGuard } from './auth/admin.guard';

/**
 * The Sahaj admin portal (ADR-003). Mounted at `/admin`, outside the clinic shell and the clinic
 * guards. `login` is kept as an alias of the documented `sign-in` path.
 */
export const ADMIN_ROUTES: Routes = [
  {
    path: 'sign-in',
    title: 'Admin sign in · Sahaj',
    canActivate: [adminGuestGuard],
    loadComponent: () => import('./sign-in/admin-sign-in.page').then((m) => m.AdminSignInPage),
  },
  { path: 'login', redirectTo: 'sign-in' },
  {
    path: '',
    canActivate: [adminGuard],
    loadComponent: () => import('./shell/admin-shell.component').then((m) => m.AdminShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'clinics' },
      {
        path: 'clinics',
        title: 'Organizations · Sahaj admin',
        data: { crumb: 'Organizations' },
        loadComponent: () =>
          import('./organizations/organizations-placeholder.page').then(
            (m) => m.OrganizationsPlaceholderPage,
          ),
      },
      { path: '**', redirectTo: 'clinics' },
    ],
  },
];
