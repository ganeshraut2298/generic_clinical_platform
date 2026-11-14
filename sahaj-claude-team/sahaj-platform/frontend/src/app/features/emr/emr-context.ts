import { InjectionToken, Signal } from '@angular/core';
import { Observable } from 'rxjs';
import { EmrAllergyClassKey, EmrCopyItemsInput, EmrCopyItemsResult, PrintLanguage } from './emr-phase3.model';

/** A drug-class clash worked out on screen from the rows and the allergy classes picked in this visit. */
export interface LiveAllergyAlert {
  rowId: string;
  medicineName: string;
  allergyClass: EmrAllergyClassKey;
  reviewed: boolean;
}

/**
 * What the shared components (EMR list, prescription) need from the open EMR record. The record
 * store provides it; outside a record (the form builder's Preview) nothing provides it and the
 * components work without these features: no copy, no print language, no live allergy alerts.
 */
export interface EmrComponentContext {
  /** The patient's recorded sex, for the body drawing (Male, Female; Other when not recorded). */
  readonly patientSex: Signal<'F' | 'M' | 'O' | null>;
  /** The open record, or null when none. */
  readonly recordId: Signal<string | null>;
  /** The language new Advice items start with: the visit's choice, else the clinic's Case paper language. */
  readonly printLanguage: Signal<PrintLanguage>;
  setPrintLanguage(language: PrintLanguage): void;
  /** "Repeat last Rx" is possible only when the patient has an earlier signed prescription. */
  readonly lastSignedPrescription: Signal<{ recordId: string; date: string } | null>;
  readonly allergyAlerts: Signal<readonly LiveAllergyAlert[]>;
  /** The drug class of a medicine added from the master, kept for the live check. */
  noteMedicineClass(medicineId: string, allergyClass: EmrAllergyClassKey | null): void;
  /** Saves unsaved entries first, then copies on the server and adopts the answer. Null when it could not. */
  copyItems(input: EmrCopyItemsInput): Observable<EmrCopyItemsResult | null>;
}

export const EMR_COMPONENT_CONTEXT = new InjectionToken<EmrComponentContext>('EMR_COMPONENT_CONTEXT');
