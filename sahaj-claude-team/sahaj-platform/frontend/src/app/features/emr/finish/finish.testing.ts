import {
  EmrFinishDentalEffects,
  EmrFinishOgEffects,
  EmrFinishOncoEffects,
  EmrFinishPaedsEffects,
  EmrFinishPhysioEffects,
  EmrFinishSurgeryEffects,
} from '../emr-finish.model';

/** Profile effect blocks for tests, in the shape of opd.yaml 0.6.1 (nothing recorded unless a test says so). */
export const dentalBlock = (over: Partial<EmrFinishDentalEffects> = {}): EmrFinishDentalEffects => ({
  profile: 'dental',
  doneRows: [],
  estimateTotalPaise: 0,
  allRowsDone: false,
  carriedRowCount: 0,
  nextSittingInterval: 'D7',
  nextSittingDate: null,
  nextSittingSlot: null,
  ...over,
});

export const ogBlock = (over: Partial<EmrFinishOgEffects> = {}): EmrFinishOgEffects => ({
  profile: 'og',
  mode: 'ANC',
  ancVisitNo: null,
  gestationalAge: null,
  bpSystolic: null,
  bpDiastolic: null,
  hbGdl: null,
  orderedLabels: [],
  nextAncWeeks: null,
  ...over,
});

export const paedsBlock = (over: Partial<EmrFinishPaedsEffects> = {}): EmrFinishPaedsEffects => ({
  profile: 'paeds',
  weightKg: null,
  weightNote: null,
  vaccinesGivenToday: [],
  nextDue: null,
  ...over,
});

export const physioBlock = (over: Partial<EmrFinishPhysioEffects> = {}): EmrFinishPhysioEffects => ({
  profile: 'physio',
  courseId: null,
  sessionNo: null,
  packageSize: null,
  nextSessionNo: null,
  packageComplete: false,
  painScore: null,
  previousPainScore: null,
  ...over,
});

export const oncoBlock = (over: Partial<EmrFinishOncoEffects> = {}): EmrFinishOncoEffects => ({
  profile: 'onco',
  cycleNo: null,
  decision: null,
  orderStatus: 'NONE',
  nextCycleNo: null,
  nextCycleLabel: null,
  nextCycleDate: null,
  nextCycleSittingId: null,
  cycleChargePaise: null,
  followUpFromNextCycle: false,
  ...over,
});

export const surgeryBlock = (over: Partial<EmrFinishSurgeryEffects> = {}): EmrFinishSurgeryEffects => ({
  profile: 'surgery',
  bookState: 'NOT_BOOKED',
  procedureText: null,
  otDate: null,
  slotText: null,
  admission: null,
  admissionRequested: false,
  fitnessText: null,
  ...over,
});
