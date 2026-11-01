import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Sahaj · Clinic software for India',
    loadChildren: () => import('./features/home/home.routes').then((m) => m.HOME_ROUTES),
  },
  {
    path: 'login',
    title: 'Log in · Sahaj',
    loadComponent: () => import('./features/auth/login/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'setup',
    title: 'Set up your clinic · Sahaj',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/setup/setup-placeholder.page').then((m) => m.SetupPlaceholderPage),
  },
  {
    path: 'app',
    title: 'Today · Sahaj',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/app-shell/app-placeholder.page').then((m) => m.AppPlaceholderPage),
  },
  {
    path: 'admin',
    title: 'Admin portal · Sahaj',
    loadComponent: () =>
      import('./features/admin/admin-coming-soon/admin-coming-soon.page').then(
        (m) => m.AdminComingSoonPage,
      ),
  },
  { path: '**', redirectTo: '' },
];
