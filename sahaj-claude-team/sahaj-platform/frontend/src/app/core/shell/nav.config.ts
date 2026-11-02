import { IconName } from '../../shared/icon/icon.component';

/**
 * Menu keys the shell knows how to show, mapped from `docs/stories/SAH-5.md` §3's proposed
 * role table and the mockup's `NV_MODS`/`CS_GRP`. `/me.menu` (server) decides which of these a
 * signed-in user actually sees; this file only supplies the order, icon, group and route.
 */
export interface NavItem {
  readonly key: string;
  readonly icon: IconName;
  readonly route: string;
  readonly group: 'work' | 'pt' | 'money' | 'clinic';
  readonly labelKey: string;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { key: 'today', icon: 'home', route: 'today', group: 'work', labelKey: 'nav.today' },
  { key: 'queue', icon: 'users', route: 'queue', group: 'work', labelKey: 'nav.queue' },
  {
    key: 'appointments',
    icon: 'calendar',
    route: 'appointments',
    group: 'work',
    labelKey: 'nav.appointments',
  },
  { key: 'patients', icon: 'user', route: 'patients', group: 'pt', labelKey: 'nav.patients' },
  {
    key: 'followups',
    icon: 'bell',
    route: 'followups',
    group: 'pt',
    labelKey: 'nav.followups',
  },
  { key: 'billing', icon: 'receipt', route: 'billing', group: 'money', labelKey: 'nav.billing' },
  { key: 'revenue', icon: 'wallet', route: 'revenue', group: 'money', labelKey: 'nav.revenue' },
  { key: 'reports', icon: 'chart', route: 'reports', group: 'clinic', labelKey: 'nav.reports' },
  { key: 'config', icon: 'sliders', route: 'config', group: 'clinic', labelKey: 'nav.config' },
];

/** `S.mode==='simple'` in the mockup: a short flat list, filtered by role on top of this. */
export const SIMPLE_MENU_KEYS: readonly string[] = [
  'today',
  'queue',
  'appointments',
  'patients',
  'billing',
  'config',
];

export const NAV_GROUP_LABEL_KEYS: Record<NavItem['group'], string> = {
  work: 'navGroup.work',
  pt: 'navGroup.patients',
  money: 'navGroup.money',
  clinic: 'navGroup.clinic',
};

export function navItemByKey(key: string): NavItem | undefined {
  return NAV_ITEMS.find((item) => item.key === key);
}
