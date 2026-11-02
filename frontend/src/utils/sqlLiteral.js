/**
 * Shared SQL literal / identifier helpers used by both the single-cell edit feature (home/index.vue) and the
 * result-set data import wizard (ImportDataModal.vue). Keeping them here avoids duplicated dialect logic drifting apart.
 *
 * Values arriving from the result grid are already-rendered strings; binary columns are rendered as uppercase hex text
 * (backend HexadecimalUtils.bytes2hex), so binary literals are built from hex.
 */

// Types whose grid value is hex text (RowStorage/ResultFileReader render byte[] via bytes2hex).
// BIT/BOOLEAN/BOOL are excluded: they are displayed as True/False numbers, not hex.
export function isBinaryColumnType(type) {
  const t = String(type || '').toUpperCase();
  if (t === 'BIT' || t === 'BOOLEAN' || t === 'BOOL' || t.startsWith('BIT(') || /^BIT \d/.test(t)) {
    return false;
  }
  return /BLOB|BINARY|VARBINARY|BYTEA|IMAGE|ROWVERSION|HIERARCHYID|\bRAW\b/.test(t);
}

// Character/date-like target types: their values must always be written as quoted string literals (never bare numbers),
// so leading zeros and date text are preserved.
export function isCharLikeType(type) {
  const t = String(type || '').toUpperCase();
  return /CHAR|TEXT|CLOB|STRING|DATE|TIME|TIMESTAMP|ENUM|SET|JSON|UUID|NAME|XML|INTERVAL/.test(t);
}

export function isNumericLiteral(value) {
  return value != null && value !== '' && /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(String(value));
}

// Normalize the displayed hex text (accepts 0x prefix, spaces, newlines) to bare uppercase hex; null when not valid.
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

// Binary literal per dialect family; values come from the grid as hex text (RowStorage renders byte[] via bytes2hex).
export function binaryLiteral(hex, dsType) {
  const ds = String(dsType || '');
  // Pg-family bytea (hex format is accepted by default).
  if (/Postgre|Greenplum|Cloudberry|Redshift|Hologres|CockroachDB|GaussDB|^PolarDbX$|^pg$/i.test(ds)) {
    return `'\\x${hex}'::bytea`;
  }
  if (ds === 'SQLServer' || ds === 'KingbaseESSQLServer' || ds === 'Sybase') {
    return `0x${hex}`;
  }
  if (/^Db2/i.test(ds)) {
    return `CAST('${hex}' AS BINARY(${Math.max(hex.length / 2, 1)}))`;
  }
  if (ds === 'Hana') {
    return `CONVERT(VARBINARY, '${hex}', 16)`;
  }
  if (/Oracle/i.test(ds)) {
    return `HEXTORAW('${hex}')`;
  }
  if (/^ClickHouse$/i.test(ds)) {
    return `unhex('${hex}')`;
  }
  // MySQL-family and the MySQL-compatible defaults: standard x'' hex literal.
  return hex.length ? `x'${hex}'` : `x''`;
}

// Encode UTF-8 text to an uppercase hex string, used when a binary cell/value is written as text.
export function utf8TextToHex(text) {
  const bytes = new TextEncoder().encode(text == null ? '' : String(text));
  let hex = '';
  for (const b of bytes) {
    hex += b.toString(16).padStart(2, '0');
  }
  return hex.toUpperCase();
}

// Best-effort decode of a hex string back to UTF-8 text (invalid sequences become replacement chars).
export function hexToUtf8Text(hex) {
  const h = normalizeHex(hex);
  if (h === null || !h.length) {
    return '';
  }
  const bytes = new Uint8Array(h.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(h.substr(i * 2, 2), 16);
  }
  try {
    return new TextDecoder('utf-8').decode(bytes);
  } catch (e) {
    return '';
  }
}

// MySQL-family uses backticks; the rest use double quotes, matching getQualifier's default rule.
export function sqlQualifier(dsType) {
  const mysqlFamily = ['MySQL', 'PolarDB', 'TiDB', 'OceanBase', 'OBForMySQL', 'ObForMySQL', 'MariaDB', 'StarRocks', 'Doris', 'ADB'];
  return mysqlFamily.includes(dsType) ? { left: '`', right: '`' } : { left: '"', right: '"' };
}

// Quote a (possibly schema-qualified) identifier with the dialect qualifier.
export function quoteName(name, qualifier) {
  const q = qualifier || { left: '"', right: '"' };
  return `${q.left}${String(name)
    .split('.')
    .map((p) => p.replace(new RegExp(q.left, 'g'), ''))
    .join(`${q.right}.${q.left}`)}${q.right}`;
}

// Escape and quote a string literal (SQLServer doubles single quotes only; others also escape backslashes).
export function stringLiteral(value, dsType) {
  const q = dsType === 'SQLServer' ? String(value).replace(/'/g, "''") : String(value).replace(/\\/g, '\\\\').replace(/'/g, "''");
  return `'${q}'`;
}

// Build a SQL literal for a grid-rendered value. opts.invalidHexMessage lets callers supply a translated error message.
export function sqlLiteral(value, colType, dsType, opts = {}) {
  if (value === null) {
    return 'null';
  }
  if (isBinaryColumnType(colType)) {
    const hex = normalizeHex(value);
    if (hex === null) {
      throw new Error(opts.invalidHexMessage || 'Invalid hexadecimal text');
    }
    return binaryLiteral(hex, dsType);
  }
  if (isNumericLiteral(value)) {
    return String(value);
  }
  return stringLiteral(value, dsType);
}

// Format a Date to a 'YYYY-MM-DD HH:mm:ss' timestamp string (SheetJS cellDates builds Dates with local components).
function formatDateLiteral(d) {
  const p = (n) => String(n).padStart(2, '0');
  const date = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  const time = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  return `${date} ${time}`;
}

/**
 * Build a SQL literal for an imported raw cell value (from xlsx sheet_to_json with raw:true, cellDates:true).
 * Handles null/undefined, empty strings (optionally as NULL), Date, boolean, binary-target text, char/date-like columns
 * (always quoted) and numeric values. opts: { emptyAsNull = true, invalidHexMessage }.
 */
export function formatSqlValue(rawValue, colType, dsType, opts = {}) {
  const { emptyAsNull = true } = opts;
  if (rawValue === null || rawValue === undefined) {
    return 'null';
  }
  if (rawValue instanceof Date) {
    return Number.isNaN(rawValue.getTime()) ? 'null' : stringLiteral(formatDateLiteral(rawValue), dsType);
  }
  if (isBinaryColumnType(colType)) {
    const text = String(rawValue);
    if (!text) {
      return emptyAsNull ? 'null' : binaryLiteral('', dsType);
    }
    return binaryLiteral(utf8TextToHex(text), dsType);
  }
  if (typeof rawValue === 'boolean') {
    return rawValue ? '1' : '0';
  }
  const s = typeof rawValue === 'string' ? rawValue : String(rawValue);
  if (s === '') {
    return emptyAsNull ? 'null' : stringLiteral('', dsType);
  }
  // Character/date-like columns must stay quoted so leading zeros and text are preserved.
  if (isCharLikeType(colType)) {
    return stringLiteral(s, dsType);
  }
  if (isNumericLiteral(s)) {
    return s;
  }
  return stringLiteral(s, dsType);
}
