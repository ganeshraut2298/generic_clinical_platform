import { EmrRecordHeader } from '../emr.model';

/**
 * The grey "none recorded" allergy chip (ADR-026 D12, OQ-45-23): Paediatrics "No known allergy" and Oncology
 * "No known drug allergy", drawn only when the patient record says none (NONE_RECORDED). Nothing is drawn for
 * RECORDED (the red tag shows the text), NOT_RECORDED, an older server without the field, or any other department.
 */
export function noAllergyText(profile: 'paeds' | 'onco', status: EmrRecordHeader['allergyStatus'] | undefined): string | null {
  if (status !== 'NONE_RECORDED') {
    return null;
  }
  return profile === 'paeds' ? 'No known allergy' : 'No known drug allergy';
}
