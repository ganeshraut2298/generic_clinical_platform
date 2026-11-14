import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  EmrAllergyClass,
  EmrCasePaperContext,
  EmrCopyItemsInput,
  EmrCopyItemsResult,
  EmrKeyFacts,
  EmrMedicineOption,
  EmrMedicinePage,
  EmrPastVisitDetail,
  EmrPastVisitPage,
  EmrPrintPreferences,
  PastVisitFilter,
  PastVisitKind,
} from './emr-phase3.model';
import { API_BASE_URL } from '../../core/config/api-config';
import { Visit } from '../queue/queue.model';
import {
  EmrAddendum,
  EmrAddendumInput,
  EmrAdmissionPrefill,
  EmrAdmissionRequestInput,
  EmrAdmissionRequestView,
  EmrEditHold,
  EmrEncounterVitals,
  EmrFinishChoices,
  EmrFinishCheck,
  EmrFreeSlot,
  EmrIpdOrderOptions,
  EmrReprint,
  EmrSignInput,
  EmrSignResult,
  EmrTriageScale,
} from './emr-finish.model';
import {
  DepartmentKey,
  EmrAdmissionAdviceInput,
  EmrDepartmentCreateInput,
  EmrDepartmentList,
  EmrDepartmentView,
  EmrDoctorContext,
  EmrDoctorOption,
  EmrDraftSaveResult,
  EmrLayoutSettingsInput,
  EmrMaster,
  EmrMasterPage,
  EmrOpenInput,
  EmrOpenResult,
  EmrPublishResult,
  EmrQuickOptionList,
  EmrRecordDraftInput,
  EmrRecordView,
  EmrResetTarget,
  EmrStarterDetail,
  EmrStarterSummary,
  EmrSwitchDepartmentInput,
  EmrVisitType,
  StarterKey,
} from './emr.model';

/** A diagnosis from `GET /diagnoses` (SAH-7 contract, unchanged). */
export interface DiagnosisHit {
  code: string;
  name: string;
  specialty?: string;
}

/** A medicine from `GET /medicines` (SAH-7 contract, unchanged). */
export interface MedicineHit {
  id: string;
  name: string;
  form?: string;
}

/** A service from `GET /services?active=true` (SAH-7 contract, unchanged). */
export interface ServiceHit {
  id?: string;
  name: string;
  category: string;
  priceRupees: number;
  active?: boolean;
}

/**
 * The only place that calls the Doctor EMR endpoints (`/api/v1/opd/emr/**`, opd.yaml 0.3.0) and the
 * lookups the Search list field uses. Visit type change (`PATCH /visits/{id}/visit-type`) lives here
 * too, because only the EMR header uses it.
 */
@Injectable({ providedIn: 'root' })
export class EmrApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/opd`;
  private readonly emr = `${this.baseUrl}/emr`;

  // ---- Clinic setup: configuration by department (SAH-45, SAH-53) ----

  listDepartments(): Observable<EmrDepartmentList> {
    return this.http.get<EmrDepartmentList>(`${this.emr}/departments`);
  }

  getDepartment(key: DepartmentKey): Observable<EmrDepartmentView> {
    return this.http.get<EmrDepartmentView>(`${this.emr}/departments/${key}`);
  }

  saveDraft(key: DepartmentKey, body: EmrLayoutSettingsInput): Observable<EmrDraftSaveResult> {
    return this.http.put<EmrDraftSaveResult>(`${this.emr}/departments/${key}/draft`, body);
  }

  publish(key: DepartmentKey, body: EmrLayoutSettingsInput): Observable<EmrPublishResult> {
    return this.http.post<EmrPublishResult>(`${this.emr}/departments/${key}/publish`, body);
  }

  getResetTarget(key: DepartmentKey): Observable<EmrResetTarget> {
    return this.http.get<EmrResetTarget>(`${this.emr}/departments/${key}/reset-target`);
  }

  // ---- Add department wizard (SAH-54) ----

  listStarters(): Observable<EmrStarterSummary[]> {
    return this.http.get<EmrStarterSummary[]>(`${this.emr}/starter-departments`);
  }

  getStarter(key: StarterKey): Observable<EmrStarterDetail> {
    return this.http.get<EmrStarterDetail>(`${this.emr}/starter-departments/${key}`);
  }

  createDepartment(body: EmrDepartmentCreateInput): Observable<EmrDepartmentView> {
    return this.http.post<EmrDepartmentView>(`${this.emr}/departments`, body);
  }

  listDoctorOptions(): Observable<EmrDoctorOption[]> {
    return this.http.get<EmrDoctorOption[]>(`${this.emr}/department-doctor-options`);
  }

  // ---- Opening the EMR and the record (SAH-46, SAH-47, SAH-52) ----

  getDoctorContext(): Observable<EmrDoctorContext> {
    return this.http.get<EmrDoctorContext>(`${this.emr}/me`);
  }

  openRecord(body: EmrOpenInput): Observable<EmrOpenResult> {
    return this.http.post<EmrOpenResult>(`${this.emr}/records/open`, body);
  }

  getRecord(recordId: string): Observable<EmrRecordView> {
    return this.http.get<EmrRecordView>(`${this.emr}/records/${recordId}`);
  }

  saveRecordDraft(recordId: string, body: EmrRecordDraftInput): Observable<EmrRecordView> {
    return this.http.put<EmrRecordView>(`${this.emr}/records/${recordId}/draft`, body);
  }

  switchDepartment(recordId: string, body: EmrSwitchDepartmentInput): Observable<EmrRecordView> {
    return this.http.put<EmrRecordView>(`${this.emr}/records/${recordId}/department`, body);
  }

  adviseAdmission(recordId: string, body: EmrAdmissionAdviceInput): Observable<EmrRecordView> {
    return this.http.post<EmrRecordView>(`${this.emr}/records/${recordId}/admission-advice`, body);
  }

  /**
   * SAH-52-2, SAH-67-4. The doctor changes the OPD visit type; the answer is the whole visit. EMERGENCY needs
   * `triageLevel` (a level of {@link listTriageLevels}); every other type sends none.
   */
  changeVisitType(visitId: string, visitType: EmrVisitType, triageLevel?: number): Observable<Visit> {
    const body = triageLevel === undefined ? { visitType } : { visitType, triageLevel };
    return this.http.patch<Visit>(`${this.baseUrl}/visits/${visitId}/visit-type`, body);
  }

  // ---- Phase 5: finish, sign, hold, addenda, admission request (ADR-025) ----

  /** SAH-67-4. The clinic's triage levels for the Emergency visit type, most urgent first. */
  listTriageLevels(): Observable<EmrTriageScale> {
    return this.http.get<EmrTriageScale>(`${this.emr}/triage-levels`);
  }

  /** SAH-51-1. "Check before completing": readiness and the effects of the sign. */
  finishCheck(recordId: string, choices: EmrFinishChoices): Observable<EmrFinishCheck> {
    return this.http.post<EmrFinishCheck>(`${this.emr}/records/${recordId}/finish-check`, choices);
  }

  /** SAH-51-2. One action; the same Idempotency-Key with the same body answers the first result. */
  sign(recordId: string, body: EmrSignInput, idempotencyKey: string): Observable<EmrSignResult> {
    return this.http.post<EmrSignResult>(`${this.emr}/records/${recordId}/sign`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  takeEditHold(recordId: string, takeOver: boolean): Observable<EmrEditHold> {
    return this.http.put<EmrEditHold>(`${this.emr}/records/${recordId}/edit-hold`, takeOver ? { takeOver: true } : {});
  }

  releaseEditHold(recordId: string): Observable<void> {
    return this.http.delete<void>(`${this.emr}/records/${recordId}/edit-hold`);
  }

  addAddendum(recordId: string, body: EmrAddendumInput, idempotencyKey: string): Observable<EmrAddendum> {
    return this.http.post<EmrAddendum>(`${this.emr}/records/${recordId}/addenda`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  getEncounterVitals(recordId: string): Observable<EmrEncounterVitals> {
    return this.http.get<EmrEncounterVitals>(`${this.emr}/records/${recordId}/encounter-vitals`);
  }

  getIpdOrderOptions(recordId: string): Observable<EmrIpdOrderOptions> {
    return this.http.get<EmrIpdOrderOptions>(`${this.emr}/records/${recordId}/ipd-order-options`);
  }

  getAdmissionPrefill(recordId: string): Observable<EmrAdmissionPrefill> {
    return this.http.get<EmrAdmissionPrefill>(`${this.emr}/records/${recordId}/admission-prefill`);
  }

  requestAdmission(recordId: string, body: EmrAdmissionRequestInput, idempotencyKey: string): Observable<EmrAdmissionRequestView> {
    return this.http.post<EmrAdmissionRequestView>(`${this.emr}/records/${recordId}/admission-request`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  /** SAH-63-3, SAH-69-3, SAH-51-6. The record doctor's next free slots (up to 7 days from `from`). */
  listFreeSlots(recordId: string, from?: string, limit = 4): Observable<EmrFreeSlot[]> {
    let params = new HttpParams().set('limit', limit);
    if (from) {
      params = params.set('from', from);
    }
    return this.http.get<EmrFreeSlot[]>(`${this.emr}/records/${recordId}/free-slots`, { params });
  }

  /** SAH-51-6. The signed prescription of a visit, read-only ("Reprint"). */
  reprintPrescription(visitId: string): Observable<EmrReprint> {
    return this.http.get<EmrReprint>(`${this.baseUrl}/visits/${visitId}/prescription-print`);
  }

  // ---- Phase 3: prescription, copy, print, case paper, history (ADR-022) ----

  listAllergyClasses(): Observable<EmrAllergyClass[]> {
    return this.http.get<EmrAllergyClass[]>(`${this.emr}/allergy-classes`);
  }

  searchEmrMedicines(q: string, page = 0): Observable<EmrMedicinePage> {
    let params = new HttpParams().set('q', q);
    if (page > 0) {
      params = params.set('page', page);
    }
    return this.http.get<EmrMedicinePage>(`${this.emr}/medicines`, { params });
  }

  listMedicineFavourites(): Observable<EmrMedicineOption[]> {
    return this.http.get<EmrMedicineOption[]>(`${this.emr}/medicine-favourites`);
  }

  copyItems(recordId: string, body: EmrCopyItemsInput): Observable<EmrCopyItemsResult> {
    return this.http.post<EmrCopyItemsResult>(`${this.emr}/records/${recordId}/copy-items`, body);
  }

  setPrintPreferences(recordId: string, body: EmrPrintPreferences): Observable<EmrPrintPreferences> {
    return this.http.put<EmrPrintPreferences>(`${this.emr}/records/${recordId}/print-preferences`, body);
  }

  getCasePaperContext(recordId: string): Observable<EmrCasePaperContext> {
    return this.http.get<EmrCasePaperContext>(`${this.emr}/records/${recordId}/case-paper`);
  }

  listPastVisits(recordId: string, type: PastVisitFilter, q: string, page: number): Observable<EmrPastVisitPage> {
    let params = new HttpParams().set('type', type).set('page', page);
    if (q.trim()) {
      params = params.set('q', q.trim());
    }
    return this.http.get<EmrPastVisitPage>(`${this.emr}/records/${recordId}/past-visits`, { params });
  }

  getPastVisitDetail(recordId: string, kind: PastVisitKind, pastId: string): Observable<EmrPastVisitDetail> {
    return this.http.get<EmrPastVisitDetail>(`${this.emr}/records/${recordId}/past-visits/${kind}/${pastId}`);
  }

  getPastRecord(recordId: string, pastRecordId: string): Observable<EmrRecordView> {
    return this.http.get<EmrRecordView>(`${this.emr}/records/${recordId}/past-records/${pastRecordId}`);
  }

  getKeyFacts(recordId: string): Observable<EmrKeyFacts> {
    return this.http.get<EmrKeyFacts>(`${this.emr}/records/${recordId}/key-facts`);
  }

  // ---- Lookups for the Search list field ----

  searchMaster(master: 'complaints' | 'investigations' | 'advice', q: string, specialty: StarterKey | null): Observable<EmrMasterPage> {
    let params = new HttpParams().set('q', q);
    if (specialty) {
      params = params.set('specialty', specialty);
    }
    return this.http.get<EmrMasterPage>(`${this.emr}/masters/${master}`, { params });
  }

  quickOptions(master: EmrMaster, specialty: StarterKey | null, limit = 10): Observable<EmrQuickOptionList> {
    let params = new HttpParams().set('limit', limit);
    if (specialty) {
      params = params.set('specialty', specialty);
    }
    return this.http.get<EmrQuickOptionList>(`${this.emr}/quick-options/${master}`, { params });
  }

  searchDiagnoses(q: string, specialty: StarterKey | null): Observable<DiagnosisHit[]> {
    let params = new HttpParams().set('q', q);
    if (specialty) {
      params = params.set('specialty', specialty);
    }
    return this.http.get<DiagnosisHit[]>(`${this.baseUrl}/diagnoses`, { params });
  }

  searchMedicines(q: string): Observable<{ items: MedicineHit[]; total: number }> {
    return this.http.get<{ items: MedicineHit[]; total: number }>(`${this.baseUrl}/medicines`, {
      params: new HttpParams().set('q', q),
    });
  }

  activeServices(): Observable<ServiceHit[]> {
    return this.http.get<ServiceHit[]>(`${this.baseUrl}/services`, {
      params: new HttpParams().set('active', true),
    });
  }
}
