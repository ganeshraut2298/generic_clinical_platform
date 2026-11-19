import { Routes } from '@angular/router';

/**
 * `/app/pharmacy` (SAH-78, ADR-028 D11): one route with the module tabs in BRD order, Inventory setup,
 * Inventory, Pharmacy. Only tabs with a released screen exist: Inventory setup (SAH-78) and Inventory (SAH-79 work package D). Later work packages and phases add children here.
 */
export const PHARMACY_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pharmacy.page').then((m) => m.PharmacyPage),
    children: [
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () => import('./module-home.page').then((m) => m.ModuleHomePage),
      },
      {
        path: 'setup',
        title: 'Inventory setup · Sahaj',
        loadComponent: () => import('./setup/setup.page').then((m) => m.SetupPage),
      },
      {
        path: 'inventory',
        title: 'Inventory · Sahaj',
        loadComponent: () => import('./inventory/inventory.page').then((m) => m.InventoryPage),
      },
      { path: '**', redirectTo: '' },
    ],
  },
];
