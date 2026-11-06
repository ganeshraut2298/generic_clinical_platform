import { Routes } from '@angular/router';
import { CONFIG_SECTIONS } from './config-sections';

/**
 * Clinic setup (menu key `config`) inside the app shell. Team and access is SAH-6; Overview,
 * Clinic profile, Specialty template, Case paper and Rx, and Fees and billing are SAH-7;
 * Appointments and slots is SAH-11. Other sections open a plain placeholder until their story
 * lands.
 */
export const CONFIG_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./config-layout.component').then((m) => m.ConfigLayoutComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'home' },
      {
        path: 'home',
        title: 'Overview · Sahaj',
        loadComponent: () => import('./overview/overview.page').then((m) => m.OverviewPage),
      },
      {
        path: 'modules',
        title: 'Plan and modules · Sahaj',
        loadComponent: () => import('./modules/modules.page').then((m) => m.ModulesPage),
      },
      {
        path: 'profile',
        title: 'Clinic profile · Sahaj',
        loadComponent: () => import('./profile/profile.page').then((m) => m.ProfilePage),
      },
      {
        path: 'team',
        title: 'Team and access · Sahaj',
        loadComponent: () => import('./team/team.page').then((m) => m.TeamPage),
      },
      {
        path: 'specialty',
        title: 'Specialty template · Sahaj',
        loadComponent: () => import('./specialty/specialty.page').then((m) => m.SpecialtyPage),
      },
      {
        path: 'paper',
        title: 'Case paper and Rx · Sahaj',
        loadComponent: () => import('./paper/paper.page').then((m) => m.PaperPage),
      },
      {
        path: 'fees',
        title: 'Fees and billing · Sahaj',
        loadComponent: () => import('./fees/fees.page').then((m) => m.FeesPage),
      },
      {
        path: 'slots',
        title: 'Appointments and slots · Sahaj',
        loadComponent: () => import('./slots/slots.page').then((m) => m.SlotsPage),
      },
      {
        path: 'workflow',
        title: 'OPD workflow · Sahaj',
        loadComponent: () => import('./workflow/workflow.page').then((m) => m.WorkflowPage),
      },
      {
        path: 'rules',
        title: 'Alerts and escalation · Sahaj',
        loadComponent: () => import('./rules/rules.page').then((m) => m.RulesPage),
      },
      {
        path: 'data',
        title: 'Privacy, ABDM and backup · Sahaj',
        loadComponent: () => import('./data/data.page').then((m) => m.DataPage),
      },
      {
        path: 'ipbeds',
        title: 'Wards, rooms and beds · Sahaj',
        loadComponent: () => import('./ipd/ipbeds.page').then((m) => m.IpBedsPage),
      },
      {
        path: 'iprates',
        title: 'Bed categories and charges · Sahaj',
        loadComponent: () => import('./ipd/iprates.page').then((m) => m.IpRatesPage),
      },
      {
        path: 'ipadm',
        title: 'Admission and deposits · Sahaj',
        loadComponent: () => import('./ipd/ipadm.page').then((m) => m.IpAdmPage),
      },
      {
        path: 'iper',
        title: 'Emergency and triage · Sahaj',
        loadComponent: () => import('./ipd/iper.page').then((m) => m.IpErPage),
      },
      {
        path: 'ipdis',
        title: 'Discharge · Sahaj',
        loadComponent: () => import('./ipd/ipdis.page').then((m) => m.IpDisPage),
      },
      ...CONFIG_SECTIONS.filter((s) => !s.built).map((s) => ({
        path: s.key,
        title: `${s.label} · Sahaj`,
        loadComponent: () => import('./config-soon.page').then((m) => m.ConfigSoonPage),
      })),
      { path: '**', redirectTo: 'home' },
    ],
  },
];
