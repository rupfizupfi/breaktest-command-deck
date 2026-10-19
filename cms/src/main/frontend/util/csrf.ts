/**
 * The Spring Security CSRF header for a request the Hilla client does not send itself, such as a
 * `<vaadin-upload>` post. Mirrors Hilla's lookup (`extractCsrfInfoFromMeta` in
 * `@vaadin/hilla-frontend/CsrfInfoSource.js`, which the package does not export): the
 * `XSRF-TOKEN` cookie wins over the `_csrf` meta tag, and the header name comes only from the
 * `_csrf_header` meta tag.
 *
 * This app keeps the token in the session, so there is no cookie and the meta tag supplies it.
 * Vaadin writes that tag from the request's `CsrfToken`, which under Spring's default
 * `XorCsrfTokenRequestAttributeHandler` is already masked, and Hilla's `login()` rewrites it from
 * the login response. Sending it unchanged is what the handler expects.
 */

/** The parts of a `Document` the lookup reads; a real `document` satisfies it. */
export interface CsrfSource {
  cookie: string;
  head: { querySelector(selector: string): { getAttribute(name: string): string | null } | null };
}

const SPRING_CSRF_COOKIE_NAME = 'XSRF-TOKEN';

function metaContent(doc: CsrfSource, name: string): string | undefined {
  const value = doc.head.querySelector(`meta[name="${name}"]`)?.getAttribute('content');
  // Hilla treats the literal string "undefined" as absent, as a server template can render one.
  return value && value.toLowerCase() !== 'undefined' ? value : undefined;
}

function cookieValue(doc: CsrfSource, name: string): string | undefined {
  for (const entry of doc.cookie.split(';')) {
    const separator = entry.indexOf('=');
    if (separator > 0 && entry.slice(0, separator).trim() === name) {
      const value = entry.slice(separator + 1).trim();
      try {
        return decodeURIComponent(value);
      } catch {
        return value;
      }
    }
  }
  return undefined;
}

/** `{[headerName]: token}`, or `{}` when the page carries no token or no header name. */
export function csrfHeaders(doc: CsrfSource = document): Record<string, string> {
  const token = cookieValue(doc, SPRING_CSRF_COOKIE_NAME) || metaContent(doc, '_csrf');
  const header = metaContent(doc, '_csrf_header');
  return token && header ? {[header]: token} : {};
}
