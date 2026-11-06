import { VisitSex, VisitStage, VisitType } from '../../queue/queue.model';

/** Types for `GET /opd/today-summary` and `GET /billing/today-summary` (SAH-15). No field is invented. */

export interface TodayTask {
  visitId: string;
  tokenNo: number;
  patientName: string;
  stage: VisitStage;
  doctorName: string;
  waitMinutes: number;
}

export interface TodayNowWithDoctor {
  visitId: string;
  tokenNo: number;
  patientName: string;
  age: number;
  sex: VisitSex;
  visitType: VisitType;
}

export interface TodayDoctor {
  doctorMembershipId: string;
  doctorName: string;
  nowWithDoctor?: TodayNowWithDoctor | null;
  nextTokens: number[];
}

export interface TodaySummary {
  patientsTotal: number;
  newCount: number;
  followUpCount: number;
  waitingCount: number;
  inOpdCount: number;
  longestWaitMinutes: number;
  followUpsDue: number;
  followUpsReturned: number;
  followUpsMissed: number;
  doctors: TodayDoctor[];
  myTasks: TodayTask[];
}

export interface BillingTodaySummary {
  collectedPaise: number;
  billsPending: number;
}
