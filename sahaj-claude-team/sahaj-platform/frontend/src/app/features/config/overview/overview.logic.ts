import { tileLine } from '../../ipd/card/card-setup.logic';
import type { CardSetupView } from '../../ipd/card/card-setup.model';
import { CasePaperSettings, FeeSettings, FeeTiming } from '../opd/opd.model';
import { ClinicProfile, Holiday, SetupCheck, Specialty } from '../platform/clinic.model';
import { TeamMember, isDoctorRole } from '../team/team.model';

/** Where a checklist item's Fix link goes: a Clinic setup section and the panel on that page. */
export interface FixTarget {
  section: string;
  fragment?: string;
}

/**
 * The contract does not list the check keys, so the link is chosen from the key and label text
 * (the mockup's checklist: profile, logo, registration numbers, PIN, holidays, price list, ...).
 * A check that matches nothing has no link.
 */
const FIX_RULES: readonly { match: RegExp; target: FixTarget }[] = [
  { match: /logo|letterhead/i, target: { section: 'profile', fragment: 'f-logo' } },
  { match: /holiday/i, target: { section: 'profile', fragment: 'f-hol' } },
  { match: /registration|reg_?no|council/i, target: { section: 'team', fragment: 'f-docs' } },
  { match: /\bpin\b|staff|team|login/i, target: { section: 'team', fragment: 'f-staff' } },
  { match: /service|price|fee/i, target: { section: 'fees', fragment: 'f-serv' } },
  { match: /case.?paper|paper|rx/i, target: { section: 'paper', fragment: 'f-paper' } },
  { match: /special|template/i, target: { section: 'specialty', fragment: 'f-spec' } },
  { match: /workflow/i, target: { section: 'workflow' } },
  { match: /whatsapp|message/i, target: { section: 'whatsapp' } },
  { match: /abdm|abha|privacy|consent|backup/i, target: { section: 'data' } },
  { match: /profile|clinic|name|address|phone/i, target: { section: 'profile', fragment: 'f-name' } },
];

export function fixTarget(check: Pick<SetupCheck, 'key' | 'label'>): FixTarget | null {
  const text = `${check.key} ${check.label}`;
  return FIX_RULES.find((rule) => rule.match.test(text))?.target ?? null;
}

/** Undone checks first, optional ones last, as the mockup sorts them. */
export function sortChecks(checks: readonly SetupCheck[]): SetupCheck[] {
  return [...checks].sort(
    (a, b) => Number(a.done) - Number(b.done) || Number(!!a.optional) - Number(!!b.optional),
  );
}

export interface OverviewSummary {
  required: number;
  done: number;
  left: number;
}

export function summarise(checks: readonly SetupCheck[]): OverviewSummary {
  const required = checks.filter((c) => !c.optional);
  const done = required.filter((c) => c.done).length;
  return { required: required.length, done, left: required.length - done };
}

/** Number of required checks not done, for each section (the "N to do" chip on a tile). */
export function todoBySection(checks: readonly SetupCheck[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const check of checks) {
    const target = fixTarget(check);
    if (target && !check.done && !check.optional) {
      out[target.section] = (out[target.section] ?? 0) + 1;
    }
  }
  return out;
}

const TIMING_TEXT: Record<FeeTiming, string> = {
  before: 'Fee at the token',
  after: 'Fee after consultation',
  split: 'Consultation fee at token, rest after',
};

export interface TileData {
  profile: ClinicProfile | null;
  holidays: Holiday[] | null;
  members: TeamMember[] | null;
  specialties: Specialty[] | null;
  fees: FeeSettings | null;
  paper: CasePaperSettings | null;
  /** SAH-37. Only read when IPD is available. */
  cardSetup?: CardSetupView | null;
}

const LANGUAGE_TEXT = { mr: 'English + मराठी', hi: 'English + हिंदी' } as const;

/** One short line for each built section's tile. Anything not loaded gives no line. */
export function tileSummaries(data: TileData): Record<string, string> {
  const out: Record<string, string> = {};
  if (data.profile) {
    const where = data.profile.area ?? data.profile.address ?? data.profile.name;
    const holidays = data.holidays?.length ?? 0;
    out['profile'] = `${where} · ${holidays} ${holidays === 1 ? 'holiday' : 'holidays'} set`;
  }
  if (data.members) {
    const live = data.members.filter((m) => m.status !== 'REMOVED');
    const doctors = live.filter((m) => isDoctorRole(m.role) || m.doctor != null).length;
    const staff = live.filter((m) => !isDoctorRole(m.role) && m.status === 'ACTIVE').length;
    out['team'] = `${doctors} ${doctors === 1 ? 'doctor' : 'doctors'} · ${staff} staff active`;
  }
  if (data.profile && data.specialties) {
    out['specialty'] =
      data.specialties.find((s) => s.code === data.profile?.specialtyCode)?.name ?? 'Not chosen yet';
  }
  if (data.paper) {
    const lang = data.paper.secondLanguage ? LANGUAGE_TEXT[data.paper.secondLanguage] : 'English only';
    out['paper'] = `${data.paper.size} · ${lang}`;
  }
  if (data.fees) {
    const free = data.fees.followUpFee === 'free' ? 'free follow-up' : 'follow-up';
    out['fees'] = `${TIMING_TEXT[data.fees.feeTiming]} · ${free} ${data.fees.followUpDays} days`;
  }
  if (data.cardSetup) {
    const { live, draft } = data.cardSetup;
    out['ipcard'] = tileLine({
      layout: live.setup.layout,
      isStandard: live.isStandard,
      version: live.version,
      hasDraft: !!draft,
    });
  }
  return out;
}
