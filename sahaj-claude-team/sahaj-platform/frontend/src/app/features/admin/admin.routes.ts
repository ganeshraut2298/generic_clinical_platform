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
    // SAH-42, dev-only bootstrap registration of the first admin. No link leads here. The server
    // answers an empty 404 when it is off or an admin exists, and the page then shows not found.
    path: 'register',
    title: 'Create the first admin · Sahaj',
    loadComponent: () => import('./register/admin-register.page').then((m) => m.AdminRegisterPage),
  },
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
