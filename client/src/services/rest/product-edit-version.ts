import { HttpHeaders } from '@angular/common/http';

/**
 * The response header carrying the product version an admin edit actually
 * produced.
 *
 * <p>Must stay in step with `ProductEditVersionHeader.NAME` on the backend. The
 * server also has to list it in `Access-Control-Expose-Headers`, otherwise the
 * browser hides it from JavaScript on a cross-origin request and the editor
 * never learns its own version.
 */
export const PRODUCT_VERSION_HEADER = 'X-Product-Version';

/**
 * The outcome of a version-checked product save.
 *
 * <p>`productVersion` is the version the server's write produced, and the only
 * one that may be paired with the field values that write submitted. It is null
 * when the response carried no usable version, which is deliberately treated as
 * "unknown" rather than as a reason to keep the previous one: the previous
 * version is the one this save just superseded, so reusing it would make the
 * next save conflict against this editor's own successful write, and adopting a
 * separately fetched version instead would vouch for a stock count this form
 * never saw.
 */
export interface IProductUpdateResult {
  saved: boolean;
  productVersion: number | null;
}

/**
 * Reads the save-produced product version off a response.
 *
 * <p>Anything that is not a finite, non-negative integer is reported as null
 * rather than guessed at: a malformed header must leave the editor with no
 * version, which forces a deliberate reload, instead of producing a number the
 * next absolute-stock write would be authorized against.
 */
export function readProductVersion(headers: HttpHeaders | null | undefined): number | null {
  const raw = headers?.get(PRODUCT_VERSION_HEADER);
  if (raw === null || raw === undefined || raw.trim() === '') {
    return null;
  }
  const parsed = Number(raw);
  if (!Number.isSafeInteger(parsed) || parsed < 0) {
    return null;
  }
  return parsed;
}
