import { Routes } from '@angular/router';

export const SETUP_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./setup-wizard.page').then((m) => m.SetupWizardPage),
  },
];
