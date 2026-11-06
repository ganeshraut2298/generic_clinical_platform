import { formatDisplayDate } from '../../shared/format/ist-date';
import { formatInr } from '../../shared/format/inr';
import {
  RevenueDay,
  RevenueDoctor,
  RevenueMode,
  RevenuePaymentMode,
  RevenueRange,
  RevenueService,
} from './revenue.model';

export const EXPORT_NOT_IN_PHASE = 'Shown for layout in this phase';
export const NON_OWNER_MESSAGE =
  'Revenue is visible to the owner only. The owner can give access in Clinic setup › Team and access.';

export const REVENUE_RANGES: { range: RevenueRange; label: string }[] = [
  { range: 'TODAY', label: 'Today' },
  { range: 'WEEK', label: 'Last 7 days' },
  { range: 'MONTH', label: 'Last 30 days' },
];

const LEAD_WORDS: Record<RevenueRange, string> = {
  TODAY: 'today',
  WEEK: 'in the last 7 days',
  MONTH: 'in the last 30 days',
};

export function revenueLead(range: RevenueRange): string {
  return `Everything collected, billed and still due ${LEAD_WORDS[range]}, in one place.`;
}

export const MODE_LABELS: Record<RevenuePaymentMode, string> = { CASH: 'Cash', UPI: 'UPI', CARD: 'Card' };

/** Paise to a rupee string through the shared formatter. */
export function rupees(paise: number): string {
  return formatInr(paise / 100);
}

/** `▲ 12%` / `▼ 5%` / `0%`; null when there is no previous period to compare with. */
export function changeText(percent: number | null | undefined): string | null {
  if (percent === null || percent === undefined) {
    return null;
  }
  if (percent > 0) {
    return `▲ ${percent}%`;
  }
  return percent < 0 ? `▼ ${Math.abs(percent)}%` : '0%';
}

export interface ModeRow {
  mode: RevenuePaymentMode;
  label: string;
  amountPaise: number;
  percent: number;
  widthPercent: number;
}

/** Cash, UPI and card in that order, always three rows; percent of the positive total. */
export function modeRows(modes: RevenueMode[]): ModeRow[] {
  const order: RevenuePaymentMode[] = ['CASH', 'UPI', 'CARD'];
  const amounts = order.map((mode) => modes.find((m) => m.mode === mode)?.amountPaise ?? 0);
  const total = amounts.reduce((sum, a) => sum + Math.max(0, a), 0) || 1;
  return order.map((mode, i) => ({
    mode,
    label: MODE_LABELS[mode],
    amountPaise: amounts[i],
    percent: Math.round((Math.max(0, amounts[i]) / total) * 100),
    widthPercent: (Math.max(0, amounts[i]) / total) * 100,
  }));
}

export interface DoctorRow extends RevenueDoctor {
  averagePaise: number;
  sharePercent: number;
}

export function doctorRows(doctors: RevenueDoctor[]): DoctorRow[] {
  const total = doctors.reduce((sum, d) => sum + Math.max(0, d.collectedPaise), 0) || 1;
  return doctors.map((d) => ({
    ...d,
    averagePaise: d.patients > 0 ? Math.round(d.collectedPaise / d.patients) : 0,
    sharePercent: Math.round((Math.max(0, d.collectedPaise) / total) * 100),
  }));
}

export interface ServiceRow extends RevenueService {
  widthPercent: number;
}

export function serviceRows(services: RevenueService[]): ServiceRow[] {
  const max = Math.max(0, ...services.map((s) => s.billedPaise)) || 1;
  return services.map((s) => ({ ...s, widthPercent: (Math.max(0, s.billedPaise) / max) * 100 }));
}

export const CHART_WIDTH = 600;
export const CHART_HEIGHT = 180;
const CHART_LABEL_SPACE = 24;

export interface DayBar {
  date: string;
  x: number;
  y: number;
  width: number;
  height: number;
  isToday: boolean;
  label: string;
  showLabel: boolean;
  title: string;
}

/** One bar per day as returned by the API (zero days included). */
export function dayBars(days: RevenueDay[], today: string): DayBar[] {
  const count = days.length;
  if (count === 0) {
    return [];
  }
  const max = Math.max(0, ...days.map((d) => d.collectedPaise)) || 1;
  const slot = CHART_WIDTH / count;
  const barWidth = slot * 0.62;
  const drawable = CHART_HEIGHT - CHART_LABEL_SPACE;
  const step = count > 10 ? 5 : 1;
  return days.map((d, i) => {
    const height = Math.max(0, d.collectedPaise) === 0 ? 2 : (Math.max(0, d.collectedPaise) / max) * drawable;
    const isToday = d.date === today;
    const short = formatDisplayDate(d.date).split(' ').slice(0, 2).join(' ');
    const when = isToday ? 'Today' : formatDisplayDate(d.date);
    return {
      date: d.date,
      x: i * slot + (slot - barWidth) / 2,
      y: drawable - height,
      width: barWidth,
      height,
      isToday,
      label: isToday ? 'Today' : short,
      showLabel: isToday || (count - 1 - i) % step === 0,
      title: `${when}: ${rupees(d.collectedPaise)} collected`,
    };
  });
}
