import { Routes } from '@angular/router';

/** `/app/billing` (SAH-10), behind the shell's `billing` menu guard. */
export const BILLING_ROUTES: Routes = [
  {
    path: '',
    title: 'Billing · Sahaj',
    loadComponent: () => import('./counter/billing-counter.page').then((m) => m.BillingCounterPage),
  },
];
