/**
 * Shared SQL literal / identifier helpers used by both the single-cell edit feature (home/index.vue) and the
 * result-set data import wizard (ImportDataModal.vue). Keeping them here avoids duplicated dialect logic drifting apart.
 *
 * Values arriving from the result grid are already-rendered strings; binary columns are rendered as uppercase hex text
 * (backend HexadecimalUtils.bytes2hex), so binary literals are built from hex.
 *
 * Oracle-family types (Oracle / ObForOracle / GoldenDBOracle / YashanDBOracle / KingbaseESOracle) share '' == NULL,
 * a 4000-byte SQL string literal limit and no backslash escaping, so their literals are built separately below.
 */
import { MySQL as MYSQL_TYPES } from '@/const/dataSource';

// Oracle-family SQL string literals stay capped at 4000 bytes on 12c/18c/19c: MAX_STRING_SIZE=EXTENDED only widens
// VARCHAR2 columns, longer text must be concatenated from to_clob() chunks.
const ORACLE_LITERAL_MAX_BYTES = 4000;
// HEXTORAW() returns RAW, which SQL limits to 2000 bytes per call.
const ORACLE_HEXTORAW_MAX_BYTES = 2000;
// UTL_RAW.CONCAT() ceiling used to assemble larger binary values for BLOB columns.
const ORACLE_RAW_CONCAT_MAX_BYTES = 32767;

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

// Oracle and its compatibility modes (ObForOracle / GoldenDBOracle / YashanDBOracle / KingbaseESOracle).
export function isOracleFamily(dsType) {
  return /Oracle/i.test(String(dsType || ''));
}

// Text LOB columns accept values above the SQL literal limit through to_clob()/empty_clob().
export function isTextLobType(type) {
  const t = String(type || '').toUpperCase();
  return t.includes('CLOB') || t.startsWith('LONG');
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

// Oracle binary literal: HEXTORAW() is capped at 2000 bytes, so larger values for BLOB columns are assembled from
// 2000-byte RAW chunks (UTL_RAW.CONCAT takes up to three at a time and returns at most 32767 bytes).
function oracleBinaryLiteral(hex, colType, opts = {}) {
  const isBlob = /^BLOB/i.test(String(colType || '').toUpperCase());
  if (!hex.length) {
    return isBlob ? 'empty_blob()' : `hextoraw('')`;
  }
  const bytes = Math.ceil(hex.length / 2);
  if (bytes <= ORACLE_HEXTORAW_MAX_BYTES) {
    return `hextoraw('${hex}')`;
  }
  if (!isBlob || bytes > ORACLE_RAW_CONCAT_MAX_BYTES) {
    throw new Error(opts.tooLongMessage || `Binary value exceeds the Oracle write limit of ${ORACLE_RAW_CONCAT_MAX_BYTES} bytes`);
  }
  const chunkChars = ORACLE_HEXTORAW_MAX_BYTES * 2;
  const parts = [];
  for (let i = 0; i < hex.length; i += chunkChars) {
    parts.push(`hextoraw('${hex.slice(i, i + chunkChars)}')`);
  }
  return `to_blob(${concatRawParts(parts)})`;
}

function concatRawParts(parts) {
  if (parts.length <= 3) {
    return `utl_raw.concat(${parts.join(', ')})`;
  }
  return `utl_raw.concat(${parts[0]}, ${parts[1]}, ${concatRawParts(parts.slice(2))})`;
}

// Binary literal per dialect family; values come from the grid as hex text (RowStorage renders byte[] via bytes2hex).
export function binaryLiteral(hex, dsType, colType, opts) {
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
    return oracleBinaryLiteral(hex, colType, opts);
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
// MYSQL_TYPES comes from const/dataSource.js so MySQL-compatible types (GoldenDBMySQL, YashanDBMySQL) stay in sync.
const MYSQL_QUALIFIER_TYPES = [
  ...MYSQL_TYPES,
  'PolarDbMySQL',
  'AdbForMySQL',
  'KingbaseESMySQL',
  'SelectDB',
  'PolarDB',
  'TiDB',
  'OceanBase',
  'OBForMySQL',
  'ObForMySQL',
  'MariaDB',
  'StarRocks',
  'Doris',
  'ADB'
];

export function sqlQualifier(dsType) {
  return MYSQL_QUALIFIER_TYPES.includes(dsType) ? { left: '`', right: '`' } : { left: '"', right: '"' };
}

// Quote a (possibly schema-qualified) identifier with the dialect qualifier.
export function quoteName(name, qualifier) {
  const q = qualifier || { left: '"', right: '"' };
  return `${q.left}${String(name)
    .split('.')
    .map((p) => p.replace(new RegExp(q.left, 'g'), ''))
    .join(`${q.right}.${q.left}`)}${q.right}`;
}

// Escape and quote a string literal. SQLServer and Oracle-family treat the backslash literally (only single quotes are
// doubled); the remaining dialects use backslash as an escape character.
export function stringLiteral(value, dsType) {
  const text = String(value);
  if (dsType === 'SQLServer' || isOracleFamily(dsType)) {
    return `'${text.replace(/'/g, "''")}'`;
  }
  return `'${text.replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
}

function charByteLength(codePoint) {
  if (codePoint < 0x80) {
    return 1;
  }
  if (codePoint < 0x800) {
    return 2;
  }
  if (codePoint < 0x10000) {
    return 3;
  }
  return 4;
}

function utf8ByteLength(text) {
  let bytes = 0;
  for (const ch of text) {
    bytes += charByteLength(ch.codePointAt(0));
  }
  return bytes;
}

// Split text into chunks whose UTF-8 size fits maxBytes without cutting a character in half.
function splitByUtf8Bytes(text, maxBytes) {
  const chunks = [];
  let current = '';
  let size = 0;
  for (const ch of text) {
    const len = charByteLength(ch.codePointAt(0));
    if (current && size + len > maxBytes) {
      chunks.push(current);
      current = '';
      size = 0;
    }
    current += ch;
    size += len;
  }
  if (current) {
    chunks.push(current);
  }
  return chunks;
}

// DATE/TIMESTAMP columns: ANSI literals are independent of the session NLS_DATE_FORMAT, which a plain quoted string is not.
function oracleTemporalLiteral(text, colType) {
  const t = String(colType || '').toUpperCase();
  if (!/DATE|TIME/.test(t)) {
    return null;
  }
  const dateTime = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(\.\d{1,9})?$/.exec(text);
  if (dateTime) {
    if (/TIMESTAMP|DATETIME/.test(t)) {
      return `timestamp '${dateTime[1]} ${dateTime[2]}${dateTime[3] || ''}'`;
    }
    // DATE has no sub-second precision, so any fractional part is dropped.
    return `to_date('${dateTime[1]} ${dateTime[2]}', 'YYYY-MM-DD HH24:MI:SS')`;
  }
  const dateOnly = /^(\d{4}-\d{2}-\d{2})$/.exec(text);
  if (dateOnly) {
    if (/TIMESTAMP|DATETIME/.test(t)) {
      return `timestamp '${dateOnly[1]} 00:00:00'`;
    }
    return `date '${dateOnly[1]}'`;
  }
  return null;
}

// Oracle-family text literal: '' is NULL for plain columns (empty_clob() for text LOBs), values beyond the 4000-byte
// literal limit are concatenated from to_clob() chunks, and numeric-looking text in numeric columns stays unquoted.
function oracleTextLiteral(value, colType, dsType, opts = {}) {
  const text = String(value == null ? '' : value);
  if (text === '') {
    return isTextLobType(colType) ? 'empty_clob()' : 'null';
  }
  const temporal = oracleTemporalLiteral(text, colType);
  if (temporal) {
    return temporal;
  }
  if (isNumericLiteral(text) && !isCharLikeType(colType)) {
    return text;
  }
  if (utf8ByteLength(text) <= ORACLE_LITERAL_MAX_BYTES) {
    return stringLiteral(text, dsType);
  }
  if (!isTextLobType(colType)) {
    throw new Error(opts.tooLongMessage || `Text exceeds the Oracle literal limit of ${ORACLE_LITERAL_MAX_BYTES} bytes`);
  }
  return splitByUtf8Bytes(text, ORACLE_LITERAL_MAX_BYTES)
    .map((chunk) => `to_clob(${stringLiteral(chunk, dsType)})`)
    .join(' || ');
}

// Build a SQL literal for a grid-rendered value. opts.invalidHexMessage / opts.tooLongMessage let callers supply
// translated error messages.
export function sqlLiteral(value, colType, dsType, opts = {}) {
  if (value === null) {
    return 'null';
  }
  if (isBinaryColumnType(colType)) {
    const hex = normalizeHex(value);
    if (hex === null) {
      throw new Error(opts.invalidHexMessage || 'Invalid hexadecimal text');
    }
    return binaryLiteral(hex, dsType, colType, opts);
  }
  if (isOracleFamily(dsType)) {
    return oracleTextLiteral(value, colType, dsType, opts);
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
 * (always quoted) and numeric values. opts: { emptyAsNull = true, invalidHexMessage, tooLongMessage }.
 */
export function formatSqlValue(rawValue, colType, dsType, opts = {}) {
  const { emptyAsNull = true } = opts;
  if (rawValue === null || rawValue === undefined) {
    return 'null';
  }
  if (rawValue instanceof Date) {
    if (Number.isNaN(rawValue.getTime())) {
      return 'null';
    }
    const text = formatDateLiteral(rawValue);
    const temporal = isOracleFamily(dsType) ? oracleTemporalLiteral(text, colType) : null;
    return temporal || stringLiteral(text, dsType);
  }
  if (isBinaryColumnType(colType)) {
    const text = String(rawValue);
    if (!text) {
      return emptyAsNull ? 'null' : binaryLiteral('', dsType, colType, opts);
    }
    return binaryLiteral(utf8TextToHex(text), dsType, colType, opts);
  }
  if (typeof rawValue === 'boolean') {
    return rawValue ? '1' : '0';
  }
  const s = typeof rawValue === 'string' ? rawValue : String(rawValue);
  if (s === '') {
    return emptyAsNull ? 'null' : stringLiteral('', dsType);
  }
  // Oracle-family keeps its own quoting/chunking rules (no backslash escape, to_clob() for long text).
  const textLiteral = (v) => (isOracleFamily(dsType) ? oracleTextLiteral(v, colType, dsType, opts) : stringLiteral(v, dsType));
  // Character/date-like columns must stay quoted so leading zeros and text are preserved.
  if (isCharLikeType(colType)) {
    return textLiteral(s);
  }
  if (isNumericLiteral(s)) {
    return s;
  }
  return textLiteral(s);
}
