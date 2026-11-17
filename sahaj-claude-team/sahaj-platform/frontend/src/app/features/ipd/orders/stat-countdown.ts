import { StatLine } from './order.model';

/**
 * The STAT line of an order notice (SAH-76-3, BR-3): "STAT · give within 15 min · 9 min left", or, red,
 * "STAT · overdue by 6 min". The server computes both from its own clock and sends `asOf` with the answer.
 * Between answers the screen counts on from that `asOf` by the time that passed since the answer arrived
 * (a stopwatch, `elapsedMs`; the device's wall clock is never compared with the server's). A STAT line is
 * never removed by a timer: it goes only when the next answer has no `stat` (dose recorded, order stopped).
 */
export interface StatText {
  text: string;
  overdue: boolean;
}

const MINUTE = 60_000;

export function statText(stat: StatLine, asOf: string, elapsedMs: number): StatText {
  const due = Date.parse(stat.dueBy);
  const answered = Date.parse(asOf);
  if (Number.isNaN(due) || Number.isNaN(answered)) {
    return stat.minutesOverdue !== null
      ? { text: `STAT · overdue by ${stat.minutesOverdue} min`, overdue: true }
      : { text: `STAT · give within ${stat.windowMinutes} min · ${stat.minutesLeft ?? 0} min left`, overdue: false };
  }
  const remaining = due - (answered + Math.max(0, elapsedMs));
  if (remaining > 0) {
    return {
      text: `STAT · give within ${stat.windowMinutes} min · ${Math.ceil(remaining / MINUTE)} min left`,
      overdue: false,
    };
  }
  return { text: `STAT · overdue by ${Math.max(1, Math.floor(-remaining / MINUTE))} min`, overdue: true };
}

/** "Not acknowledged for 35 min" (red, shown when the server marks the notice overdue). */
export const unacknowledgedText = (waitingMinutes: number | null): string =>
  `Not acknowledged for ${waitingMinutes ?? 0} min`;
