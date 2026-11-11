import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import {
  AdmissionDetail,
  AdmissionInput,
  AdmissionList,
  AdmissionOptions,
  AdmissionRules,
  AdmissionRulesInput,
  BedBoard,
  ChargeService,
  ChargeServiceKey,
  PackageList,
  PayMode,
  TransferRules,
} from './ipd.model';
import {
  DischargeInput,
  DischargeSettings,
  DischargeSettingsInput,
  DischargeSheet,
  DischargeSignoffInput,
} from './discharge.model';

/** The only place that calls the `/ipd` endpoints of SAH-23 to SAH-27 and SAH-29 (rules, packages, options, admissions, deposits, charges, transfer, board, discharge). */
@Injectable({ providedIn: 'root' })
export class AdmissionsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  rules(): Observable<AdmissionRules> {
    return this.http.get<AdmissionRules>(`${this.baseUrl}/admission-rules`);
  }

  saveRules(body: AdmissionRulesInput): Observable<AdmissionRules> {
    return this.http.put<AdmissionRules>(`${this.baseUrl}/admission-rules`, body);
  }

  confirmRules(): Observable<AdmissionRules> {
    return this.http.post<AdmissionRules>(`${this.baseUrl}/admission-rules/confirm`, null);
  }

  /** With `emergencyCaseId` (SAH-28) the answer also carries `emergencyCase`, the hand-off data. */
  options(emergencyCaseId?: string): Observable<AdmissionOptions> {
    return this.http.get<AdmissionOptions>(`${this.baseUrl}/admission-options`, {
      params: emergencyCaseId ? new HttpParams().set('emergencyCaseId', emergencyCaseId) : undefined,
    });
  }

  /**
   * `cardFacts: 'all'` (SAH-37, OWNER only) sends every card fact whatever the published setup says,
   * for the live preview of Clinic setup → Patient card. Read when that page opens, never polled.
   */
  list(options: { cardFacts?: 'all' } = {}): Observable<AdmissionList> {
    let params = new HttpParams().set('status', 'ADMITTED');
    if (options.cardFacts) {
      params = params.set('cardFacts', options.cardFacts);
    }
    return this.http.get<AdmissionList>(`${this.baseUrl}/admissions`, { params });
  }

  admit(body: AdmissionInput): Observable<AdmissionDetail> {
    return this.http.post<AdmissionDetail>(`${this.baseUrl}/admissions`, body);
  }

  get(id: string): Observable<AdmissionDetail> {
    return this.http.get<AdmissionDetail>(`${this.baseUrl}/admissions/${id}`);
  }

  collectDeposit(id: string, amountPaise: number, mode: PayMode): Observable<AdmissionDetail> {
    return this.http.post<AdmissionDetail>(`${this.baseUrl}/admissions/${id}/deposits`, {
      amountPaise,
      mode,
    });
  }

  chargeServices(): Observable<ChargeService[]> {
    return this.http.get<ChargeService[]>(`${this.baseUrl}/charge-services`);
  }

  addCharge(id: string, serviceKey: ChargeServiceKey): Observable<AdmissionDetail> {
    return this.http.post<AdmissionDetail>(`${this.baseUrl}/admissions/${id}/charges`, { serviceKey });
  }

  transferRules(): Observable<TransferRules> {
    return this.http.get<TransferRules>(`${this.baseUrl}/transfer-rules`);
  }

  saveTransferRules(body: TransferRules): Observable<TransferRules> {
    return this.http.put<TransferRules>(`${this.baseUrl}/transfer-rules`, body);
  }

  transfer(id: string, toBedId: string): Observable<AdmissionDetail> {
    return this.http.post<AdmissionDetail>(`${this.baseUrl}/admissions/${id}/transfer`, { toBedId });
  }

  /** SAH-29. Read-only: the clinic's active packages. There is no create, change or attach call in this slice. */
  packages(): Observable<PackageList> {
    return this.http.get<PackageList>(`${this.baseUrl}/packages`);
  }

  board(): Observable<BedBoard> {
    return this.http.get<BedBoard>(`${this.baseUrl}/board`);
  }

  ready(bedId: string): Observable<BedBoard> {
    return this.http.post<BedBoard>(`${this.baseUrl}/beds/${bedId}/ready`, null);
  }

  block(bedId: string): Observable<BedBoard> {
    return this.http.post<BedBoard>(`${this.baseUrl}/beds/${bedId}/block`, null);
  }

  dischargeSettings(): Observable<DischargeSettings> {
    return this.http.get<DischargeSettings>(`${this.baseUrl}/discharge-settings`);
  }

  /** Sends the whole setting each time (SAH-27). */
  saveDischargeSettings(body: DischargeSettingsInput): Observable<DischargeSettings> {
    return this.http.put<DischargeSettings>(`${this.baseUrl}/discharge-settings`, body);
  }

  dischargeSheet(id: string): Observable<DischargeSheet> {
    return this.http.get<DischargeSheet>(`${this.baseUrl}/admissions/${id}/discharge`);
  }

  signDischarge(id: string, body: DischargeSignoffInput): Observable<DischargeSheet> {
    return this.http.put<DischargeSheet>(`${this.baseUrl}/admissions/${id}/discharge/signoff`, body);
  }

  discharge(id: string, body: DischargeInput): Observable<AdmissionDetail> {
    return this.http.post<AdmissionDetail>(`${this.baseUrl}/admissions/${id}/discharge`, body);
  }
}
