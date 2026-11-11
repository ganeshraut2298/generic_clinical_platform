/** RFC 9457 problem details, as defined by `Problem` in docs/api/platform.yaml. */
export interface FieldError {
  field: string;
  message: string;
}

export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail?: string;
  retryAfterSeconds?: number;
  /** Only on the dev-only bootstrap-pin-invalid problem (docs/api/admin.yaml, SAH-42). */
  triesLeft?: number;
  fieldErrors?: FieldError[];
}

/** Error thrown by every HttpClient call that fails. Carries the problem detail. */
export class ApiError extends Error {
  constructor(readonly problem: ProblemDetail) {
    super(problem.title);
    this.name = 'ApiError';
  }

  get status(): number {
    return this.problem.status;
  }

  get fieldErrors(): FieldError[] {
    return this.problem.fieldErrors ?? [];
  }
}
