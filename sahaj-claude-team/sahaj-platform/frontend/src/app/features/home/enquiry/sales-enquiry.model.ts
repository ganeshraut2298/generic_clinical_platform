/** Types for POST /api/v1/platform/public/sales-enquiries, from docs/api/platform.yaml. */
export type BestTime = 'MORNING' | 'AFTERNOON' | 'AFTER_OPD';
export type EnquirySource = 'CONTACT_SALES' | 'BOOK_DEMO';

export interface SalesEnquiryRequest {
  /** 1 to 120 characters. */
  name: string;
  /** Up to 200 characters. */
  clinicCity?: string;
  /** 10 digits, starts with 6, 7, 8 or 9. */
  mobile: string;
  bestTime?: BestTime;
  source: EnquirySource;
}

export interface IdResult {
  id: string;
}

export const NAME_MAX_LENGTH = 120;
export const CLINIC_CITY_MAX_LENGTH = 200;
export const MOBILE_PATTERN = /^[6-9][0-9]{9}$/;

export const BEST_TIME_OPTIONS: readonly { value: BestTime; label: string }[] = [
  { value: 'MORNING', label: 'Morning' },
  { value: 'AFTERNOON', label: 'Afternoon' },
  { value: 'AFTER_OPD', label: 'After OPD hours' },
];

export const ENQUIRY_THANK_YOU = 'Request received. We will call you within one working day.';
