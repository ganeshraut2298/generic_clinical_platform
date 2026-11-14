import { ComponentValue } from '../components/component-values.logic';
import { ALLERGIES, CONDITIONS, MEDICINES } from '../specialty/dental/dental-medhistory.logic';
import { EmrSpecialtyReference, EmrSpecialtyView } from '../specialty/specialty.model';

/**
 * The lines the case paper prints for a specialty component (ADR-023 D3, ADR-025 D15): the server's
 * computed block and the profile's reference give the words, so every key prints as its label. Only the
 * components named by the contract are formatted (vaccines given today, the fitness line, the weight
 * and its note); every other specialty value prints nothing here, as before. Nothing is stored or logged.
 */

function humanise(key: string): string {
  const spaced = key.replace(/[_-]+/g, ' ').trim().toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function specialtyComponentLines(
  value: ComponentValue,
  specialty: EmrSpecialtyView | null | undefined,
  reference: EmrSpecialtyReference | null | undefined,
): string[] {
  switch (value.component) {
    case 'IMMUNIZATION': {
      const labels = new Map((reference?.lists?.iapVaccines ?? []).map((v) => [v.key, v.label]));
      return value.givenToday.map((g) => `Vaccine given: ${labels.get(g.vaccineKey) ?? humanise(g.vaccineKey)}`);
    }
    case 'SURGERY_FITNESS': {
      // A reprint for front desk or pharmacist carries only the keys the printed sections use (H32): any block may be missing.
      const text = specialty?.surgery?.fitness?.text;
      if (!text) {
        return [];
      }
      return [value.asa ? `Fitness: ASA ${value.asa} · ${text}` : `Fitness: ${text}`];
    }
    case 'GROWTH': {
      const parts = [
        value.weightKg != null ? `Weight ${value.weightKg} kg` : null,
        value.lengthCm != null ? `length ${value.lengthCm} cm` : null,
        value.headCircCm != null ? `head circumference ${value.headCircCm} cm` : null,
      ].filter((p): p is string => p !== null);
      if (parts.length === 0) {
        return [];
      }
      const note = value.weightKg != null ? specialty?.paeds?.growthNotes?.[0]?.label : null;
      return [parts.join(' · ') + (note ? ` (${note})` : '')];
    }
    case 'DENTAL_MED_HISTORY': {
      // "Medical history" heading (SAH-75-6): the chosen chips of each group, then the review.
      const group = (title: string, keys: readonly string[], chips: readonly { key: string; label: string }[]): string[] => {
        const labels = keys.map((k) => chips.find((c) => c.key === k)?.label ?? humanise(k));
        return labels.length > 0 ? [`${title}: ${labels.join(', ')}`] : [];
      };
      return [
        ...group('Conditions', value.conditions, CONDITIONS),
        ...group('Medicines', value.medicines, MEDICINES),
        ...group('Allergies', value.allergies, ALLERGIES),
        ...(value.reviewedWithPatient ? ['Reviewed with patient'] : []),
      ];
    }
    default:
      return [];
  }
}
