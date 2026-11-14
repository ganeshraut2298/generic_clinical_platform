import { EmrVitalsReading } from '../emr-finish.model';

/** The Vitals library section (seed `vitals`): its field keys are the label in lower case with underscores. */
export const VITALS_SECTION = 'vitals';

/** One decimal, no trailing noise (98.6, not 98.60000000000001). */
function round1(n: number): number {
  return Math.round(n * 10) / 10;
}

/**
 * The nurse's reading as values for the library's Vitals fields (SAH-68-1). Only the numbers the nursing chart
 * holds are filled: BP, pulse, SpO₂ and temperature (the field is in °F, so a °C reading is converted). The
 * library has no respiratory rate, and weight, height or pain are not charted, so those stay for the doctor.
 */
export function vitalsFieldValues(reading: EmrVitalsReading): Record<string, number> {
  const out: Record<string, number> = {};
  if (reading.bpSystolic != null) {
    out['bp_systolic'] = reading.bpSystolic;
  }
  if (reading.bpDiastolic != null) {
    out['bp_diastolic'] = reading.bpDiastolic;
  }
  if (reading.pulse != null) {
    out['pulse'] = reading.pulse;
  }
  if (reading.spo2 != null) {
    out['spo2'] = reading.spo2;
  }
  if (reading.temperature) {
    out['temperature'] = reading.temperature.unit === '°C' ? round1((reading.temperature.value * 9) / 5 + 32) : reading.temperature.value;
  }
  return out;
}

/** "BP 146/92 · Pulse 82 · SpO₂ 98% · Temp 98.4 °F" for the offer. */
export function readingText(reading: EmrVitalsReading): string {
  const parts: string[] = [];
  if (reading.bpSystolic != null && reading.bpDiastolic != null) {
    parts.push(`BP ${reading.bpSystolic}/${reading.bpDiastolic}`);
  }
  if (reading.pulse != null) {
    parts.push(`Pulse ${reading.pulse}`);
  }
  if (reading.spo2 != null) {
    parts.push(`SpO₂ ${reading.spo2}%`);
  }
  if (reading.temperature) {
    parts.push(`Temp ${reading.temperature.value} ${reading.temperature.unit}`);
  }
  if (reading.respiratoryRate != null) {
    parts.push(`RR ${reading.respiratoryRate}`);
  }
  return parts.join(' · ');
}
