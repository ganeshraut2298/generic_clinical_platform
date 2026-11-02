import { Routes } from '@angular/router';

/** Patients (SAH-8): the list and the record page, both behind the `patients` menu guard in
 * `shell.routes.ts`. The backend still checks the role on every request. */
export const PATIENTS_ROUTES: Routes = [
  {
    path: '',
    title: 'Patients · Sahaj',
    loadComponent: () => import('./list/patients-list.page').then((m) => m.PatientsListPage),
  },
  {
    path: ':id',
    title: 'Patient · Sahaj',
    loadComponent: () => import('./record/patient-record.page').then((m) => m.PatientRecordPage),
  },
];
