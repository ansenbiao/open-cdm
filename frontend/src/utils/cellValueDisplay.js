/**
 * Shared helpers for displaying binary (BLOB/BINARY/BYTEA/...) cell values.
 *
 * The backend renders byte[] as an uppercase hex string (HexadecimalUtils.bytes2hex), so both the result grid and the
 * cell-detail popup receive hex text for binary columns. These helpers detect whether such a cell actually holds readable
 * UTF-8 text and, if so, decode it so it can be shown as a document/text instead of raw hex. Real binary (images, archives,
 * or text with control bytes) is left as hex.
 */

// Binary column types whose grid value is hex text. BIT/BOOLEAN/BOOL are intentionally excluded: they display as True/False, not hex.
export function isBinaryType(type) {
  const t = String(type || '').toUpperCase();
  if (t === 'BIT' || t === 'BOOLEAN' || t === 'BOOL' || t.startsWith('BIT(') || /^BIT \d/.test(t)) {
    return false;
  }
  return /BLOB|BINARY|VARBINARY|BYTEA|IMAGE|ROWVERSION|HIERARCHYID|\bRAW\b/.test(t);
}

// Normalize hex text (accepts an optional 0x prefix and any whitespace) to bare uppercase hex; returns null when it is not valid hex.
export function normalizeHex(value) {
  let s = String(value == null ? '' : value).trim();
  if (/^0[xX]/.test(s)) {
    s = s.slice(2);
  }
  s = s.replace(/\s+/g, '');
  if (s.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(s)) {
    return null;
  }
  return s.toUpperCase();
}

// Reject strings that contain control characters other than tab/newline/carriage-return, which indicates non-text binary.
function isPrintableText(text) {
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    // Allow tab (9), LF (10), CR (13); reject other C0 controls, NUL and DEL (127).
    if (c < 32 && c !== 9 && c !== 10 && c !== 13) {
      return false;
    }
    if (c === 127) {
      return false;
    }
  }
  return true;
}

/**
 * Decode a hex string to UTF-8 text when it represents readable text; otherwise return null (caller keeps the hex view).
 * Truncated samples (a large BLOB previewed by the backend) may cut a multi-byte sequence at the end, so a few trailing
 * bytes are trimmed before the strict decode succeeds.
 */
export function decodeHexToText(hexInput) {
  const hex = normalizeHex(hexInput);
  if (hex === null || hex.length === 0) {
    return null;
  }
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  for (let trim = 0; trim <= 3 && bytes.length - trim >= 0; trim++) {
    const view = trim ? bytes.subarray(0, bytes.length - trim) : bytes;
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(view);
      return isPrintableText(text) ? text : null;
    } catch (e) {
      // Invalid UTF-8 sequence: drop one more trailing byte (handles a truncated tail) and retry.
    }
  }
  return null;
}
