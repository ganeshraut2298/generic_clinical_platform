import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { ApiError, FieldError, ProblemDetail } from './problem-detail';

const NO_CONNECTION: ProblemDetail = {
  type: 'about:blank',
  title: 'Cannot reach the server',
  status: 0,
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function readFieldErrors(value: unknown): FieldError[] | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  const list = value.filter(
    (item): item is FieldError =>
      isRecord(item) && typeof item['field'] === 'string' && typeof item['message'] === 'string',
  );
  return list.length > 0 ? list : undefined;
}

/** Turns an HTTP failure into an ApiError that holds a ProblemDetail. Never logs the body. */
export function toApiError(error: HttpErrorResponse): ApiError {
  if (error.status === 0) {
    return new ApiError(NO_CONNECTION);
  }
  const body: unknown = error.error;
  const retryHeader = Number(error.headers?.get('Retry-After'));
  const retryFromHeader = Number.isFinite(retryHeader) && retryHeader > 0 ? retryHeader : undefined;

  if (isRecord(body) && typeof body['title'] === 'string') {
    return new ApiError({
      type: typeof body['type'] === 'string' ? body['type'] : 'about:blank',
      title: body['title'],
      status: typeof body['status'] === 'number' ? body['status'] : error.status,
      detail: typeof body['detail'] === 'string' ? body['detail'] : undefined,
      retryAfterSeconds:
        typeof body['retryAfterSeconds'] === 'number' ? body['retryAfterSeconds'] : retryFromHeader,
      triesLeft: typeof body['triesLeft'] === 'number' ? body['triesLeft'] : undefined,
      fieldErrors: readFieldErrors(body['fieldErrors']),
      holderName: typeof body['holderName'] === 'string' ? body['holderName'] : undefined,
      blockers: Array.isArray(body['blockers']) ? body['blockers'] : undefined,
      rows: Array.isArray(body['rows']) ? body['rows'] : undefined,
      quote: isRecord(body['quote']) ? body['quote'] : undefined,
    });
  }
  return new ApiError({
    type: 'about:blank',
    title: error.statusText || 'The request failed',
    status: error.status,
    retryAfterSeconds: retryFromHeader,
  });
}

/**
 * A CSV export is read as a blob, and the server answers its errors as application/problem+json (pharmacy contract
 * 0.3.6, export-too-large included). The blob is read as text and parsed as JSON so the problem is not lost.
 */
async function readBlobProblem(error: HttpErrorResponse): Promise<HttpErrorResponse> {
  const blob = error.error;
  if (!(blob instanceof Blob)) {
    return error;
  }
  try {
    const parsed: unknown = JSON.parse(await blob.text());
    return isRecord(parsed)
      ? new HttpErrorResponse({ error: parsed, headers: error.headers, status: error.status, statusText: error.statusText, url: error.url ?? undefined })
      : error;
  } catch {
    return error;
  }
}

export const problemDetailInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) {
        return throwError(() => error);
      }
      if (!(error.error instanceof Blob)) {
        return throwError(() => toApiError(error));
      }
      return from(readBlobProblem(error)).pipe(switchMap((read) => throwError(() => toApiError(read))));
    }),
  );
