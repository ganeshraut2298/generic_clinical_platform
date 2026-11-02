import { Brand, Language, Role } from '../platform/platform.model';

/** Types for `GET /me` and `PATCH /me/preferences` (SAH-5), from `docs/api/platform.yaml`. */

export type Theme = 'light' | 'dark';
export type MenuMode = 'simple' | 'advanced';

export interface Preferences {
  language?: Language;
  theme?: Theme;
  menuMode?: MenuMode;
  sidebarCollapsed?: boolean;
}

export interface MeClinic {
  name: string;
  area?: string;
  brand: Brand;
  brandHex?: string;
  plan: string;
  planDaysLeft?: number;
  setupComplete?: boolean;
  setupPercent?: number;
}

export interface Me {
  userId: string;
  /** The signed-in user's membership id; matches `TeamMember.id`. Optional until the API returns it. */
  membershipId?: string;
  name: string;
  role: Role;
  clinic: MeClinic;
  menu: string[];
  permissions?: string[];
  preferences: Preferences;
}
