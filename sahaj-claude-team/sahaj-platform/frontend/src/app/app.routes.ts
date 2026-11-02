import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { shellGuard } from './core/shell/shell.guard';

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
    loadChildren: () => import('./features/setup/setup.routes').then((m) => m.SETUP_ROUTES),
  },
  {
    path: 'app',
    canActivate: [authGuard, shellGuard],
    loadChildren: () => import('./features/shell/shell.routes').then((m) => m.SHELL_ROUTES),
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
