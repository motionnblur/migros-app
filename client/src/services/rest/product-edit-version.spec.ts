import { HttpHeaders } from '@angular/common/http';

import {
  PRODUCT_VERSION_HEADER,
  readProductVersion,
} from './product-edit-version';

/**
 * The header name is a wire contract, not an internal constant.
 *
 * <p>It has to match `ProductEditVersionHeader.NAME` on the backend byte for
 * byte, and the backend has to list it in `Access-Control-Expose-Headers` or the
 * browser hides it from JavaScript entirely. A mismatch on either side produces
 * no error anywhere: the save succeeds, the editor simply never learns its own
 * version, and every following edit is refused. Nothing in the type system or a
 * normal save would catch that, so the literal is pinned here.
 */
describe('PRODUCT_VERSION_HEADER', () => {
  it('is the exact name the backend reads the version from', () => {
    expect(PRODUCT_VERSION_HEADER).toBe('X-Product-Version');
  });

  it('matches the header HttpHeaders is given case-insensitively, as HTTP requires', () => {
    const headers = new HttpHeaders({ 'x-product-version': '7' });

    // HttpHeaders normalises lookups, so a server that capitalises the name
    // differently still resolves. The constant itself is still pinned above.
    expect(readProductVersion(headers)).toBe(7);
  });
});

/**
 * `readProductVersion` is the only thing standing between a malformed header and
 * an unguarded absolute-stock write, because the version it reports is what the
 * next save is authorized against.
 *
 * <p>Every unusable input has to come back as `null`, never as a guess. A guess
 * is worse than unknown: null refuses the next save and asks the administrator
 * to reload deliberately, while a wrong number silently authorizes a write
 * against a state the form never saw. The cases below are the ones that actually
 * occur in practice - a proxy stripping the header, a proxy injecting a blank
 * one, a truncated integer, a fractional one from a bad gateway rewrite.
 */
describe('readProductVersion', () => {
  function headersWith(value: string): HttpHeaders {
    return new HttpHeaders({ [PRODUCT_VERSION_HEADER]: value });
  }

  it('returns the version when the header carries a plain integer', () => {
    expect(readProductVersion(headersWith('5'))).toBe(5);
  });

  it('returns a multi-digit version unchanged', () => {
    expect(readProductVersion(headersWith('1234'))).toBe(1234);
  });

  it('accepts zero, which is a legitimate first version and not a missing one', () => {
    expect(readProductVersion(headersWith('0'))).toBe(0);
  });

  it('tolerates surrounding whitespace, which a header value may legally carry', () => {
    expect(readProductVersion(headersWith(' 6 '))).toBe(6);
  });

  it('returns null when the header is absent from an otherwise complete response', () => {
    const headers = new HttpHeaders({ 'Content-Type': 'application/json' });

    expect(readProductVersion(headers)).toBeNull();
  });

  it('returns null for an empty header value', () => {
    expect(readProductVersion(headersWith(''))).toBeNull();
  });

  it('returns null for a whitespace-only header value', () => {
    expect(readProductVersion(headersWith('   '))).toBeNull();
  });

  it('returns null for null headers', () => {
    expect(readProductVersion(null)).toBeNull();
  });

  it('returns null for undefined headers', () => {
    expect(readProductVersion(undefined)).toBeNull();
  });

  it('returns null for a non-numeric value rather than parsing it loosely', () => {
    expect(readProductVersion(headersWith('five'))).toBeNull();
  });

  it('returns null for a value that is only partly a number', () => {
    expect(readProductVersion(headersWith('5abc'))).toBeNull();
  });

  it('returns null for a negative value, which no committed version can be', () => {
    expect(readProductVersion(headersWith('-1'))).toBeNull();
  });

  it('returns null for a fractional value, which would authorize a partial version match', () => {
    expect(readProductVersion(headersWith('4.5'))).toBeNull();
  });

  it('returns null for a value beyond the safe integer range, which would silently round', () => {
    // 2^53 + 1 is not representable: the number parsed from it is not the
    // number that was on the wire, so returning it would authorize a write
    // against a version that does not exist.
    expect(Number.isSafeInteger(Number('9007199254740993'))).toBeFalse();
    expect(readProductVersion(headersWith('9007199254740993'))).toBeNull();
  });

  it('accepts the largest safe integer, which is the boundary, not a failure', () => {
    expect(readProductVersion(headersWith('9007199254740991'))).toBe(9007199254740991);
  });

  it('returns null for Infinity and for a bare exponent, which are not versions', () => {
    expect(readProductVersion(headersWith('Infinity'))).toBeNull();
    expect(readProductVersion(headersWith('-Infinity'))).toBeNull();
    expect(readProductVersion(headersWith('NaN'))).toBeNull();
  });
});
