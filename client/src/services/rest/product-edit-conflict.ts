import { HttpErrorResponse } from '@angular/common/http';

/**
 * The stable code the API returns when a product edit is rejected because the
 * submitted version is no longer the product's.
 */
export const PRODUCT_EDIT_CONFLICT_CODE = 'PRODUCT_EDIT_CONFLICT';

/**
 * Identifies a rejected product edit.
 *
 * <p>Both the status and the code are required. A bare 409 would also match the
 * checkout/payment conflict family, and a bare code match would match an
 * unrelated failure that happens to carry the same word. Requiring both keeps
 * this from swallowing a different domain error into the draft-preserving
 * conflict path.
 *
 * <p>The update request uses `responseType: 'text'`, so Angular hands back the
 * conflict body as a string; string bodies are parsed defensively and a
 * malformed one simply is not a conflict.
 */
export function isProductEditConflict(error: unknown): error is HttpErrorResponse {
  if (!(error instanceof HttpErrorResponse) || error.status !== 409) {
    return false;
  }
  return readCode(error.error) === PRODUCT_EDIT_CONFLICT_CODE;
}

function readCode(body: unknown): unknown {
  if (typeof body === 'string') {
    try {
      const parsed = JSON.parse(body) as { code?: unknown } | null;
      return parsed?.code;
    } catch {
      return undefined;
    }
  }
  if (body && typeof body === 'object') {
    return (body as { code?: unknown }).code;
  }
  return undefined;
}
