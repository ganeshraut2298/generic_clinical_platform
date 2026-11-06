/** Types for the SAH-17 endpoints in `docs/api/platform.yaml` and `docs/api/patient.yaml`. */

export interface PrivacySettings {
  consentRequired: boolean;
  promoConsent: boolean;
  recordCopy: boolean;
  hfrId?: string | null;
}

export type ConsentKind = 'RECORDS' | 'PROMOTIONAL';

export interface ConsentInput {
  kind: ConsentKind;
  given: boolean;
}

export interface ConsentEntry {
  kind: ConsentKind;
  given: boolean;
  /** ISO date-time */
  recordedAt: string;
  recordedByName: string;
}

export interface PatientConsentState {
  records: ConsentEntry | null;
  promotional: ConsentEntry | null;
}

export interface RecordAccess {
  /** ISO date-time */
  at: string;
  userName: string;
  patientId: string;
  patientName: string;
}

export const HFR_ID_MAX = 32;

/** The consent line the front desk reads, from the mockup, with the clinic's own name. */
export function consentLine(clinicName: string): string {
  return `I agree that ${clinicName} may keep my health records to treat me and send me visit reminders on WhatsApp.`;
}

/** The same line in Marathi, shown only when the clinic has a Marathi name. */
export function consentLineMarathi(localName: string): string {
  return `${localName} यांनी माझ्या उपचारासाठी माझी आरोग्य माहिती जपून ठेवावी आणि WhatsApp वर भेटीची आठवण पाठवावी, यास माझी संमती आहे.`;
}
