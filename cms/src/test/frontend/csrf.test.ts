/**
 * `<vaadin-upload>` posts outside the Hilla client, so it must find the CSRF token the way Hilla
 * does: the XSRF-TOKEN cookie before the `_csrf` meta tag, the header name from `_csrf_header`.
 */
import { describe, expect, it } from 'vitest';
import { type CsrfSource, csrfHeaders } from '../../main/frontend/util/csrf.js';

function page(cookie: string, meta: Record<string, string>): CsrfSource {
  return {
    cookie,
    head: {
      querySelector(selector: string) {
        const name = /^meta\[name="([^"]+)"]$/.exec(selector)?.[1];
        const content = name === undefined ? undefined : meta[name];
        return content === undefined ? null : {getAttribute: (attribute: string) => attribute === 'content' ? content : null};
      },
    },
  };
}

describe('csrfHeaders', () => {
  it('sends the meta token under the meta header name', () => {
    expect(csrfHeaders(page('', {_csrf: 'meta-token', _csrf_header: 'X-CSRF-TOKEN'})))
      .toEqual({'X-CSRF-TOKEN': 'meta-token'});
  });

  it('prefers the XSRF-TOKEN cookie over the meta token', () => {
    expect(csrfHeaders(page('other=1; XSRF-TOKEN=cookie%2Btoken', {_csrf: 'meta-token', _csrf_header: 'X-CSRF-TOKEN'})))
      .toEqual({'X-CSRF-TOKEN': 'cookie+token'});
  });

  it('is empty when neither cookie nor meta carries a token', () => {
    expect(csrfHeaders(page('other=1', {_csrf_header: 'X-CSRF-TOKEN'}))).toEqual({});
  });

  it('is empty without a header name', () => {
    expect(csrfHeaders(page('XSRF-TOKEN=cookie-token', {_csrf: 'meta-token'}))).toEqual({});
  });

  it('treats a literal "undefined" meta token as absent', () => {
    expect(csrfHeaders(page('', {_csrf: 'undefined', _csrf_header: 'X-CSRF-TOKEN'}))).toEqual({});
  });
});
