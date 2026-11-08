import { sqlLiteral, sqlQualifier, stringLiteral, binaryLiteral } from '@/utils/sqlLiteral';

describe('sqlQualifier', () => {
  it('uses backticks for the MySQL-compatible family', () => {
    ['MySQL', 'GoldenDBMySQL', 'YashanDBMySQL', 'KingbaseESMySQL', 'PolarDbMySQL', 'AdbForMySQL', 'MariaDB'].forEach((dsType) => {
      expect(sqlQualifier(dsType)).toEqual({ left: '`', right: '`' });
    });
  });

  it('uses double quotes for Oracle-family and the remaining dialects', () => {
    ['Oracle', 'ObForOracle', 'GoldenDBOracle', 'YashanDBOracle', 'PostgreSQL', 'PolarDbX'].forEach((dsType) => {
      expect(sqlQualifier(dsType)).toEqual({ left: '"', right: '"' });
    });
  });
});

describe('Oracle-family literals', () => {
  it('keeps backslashes literal and doubles single quotes', () => {
    expect(stringLiteral('a\\b', 'Oracle')).toBe("'a\\b'");
    expect(stringLiteral("a'b", 'GoldenDBOracle')).toBe("'a''b'");
    expect(stringLiteral("a'b", 'MySQL')).toBe("'a''b'");
    expect(stringLiteral('a\\b', 'MySQL')).toBe("'a\\\\b'");
  });

  it('maps empty values to NULL or an empty LOB', () => {
    expect(sqlLiteral('', 'CLOB', 'Oracle')).toBe('empty_clob()');
    expect(sqlLiteral('', 'VARCHAR2(20)', 'Oracle')).toBe('null');
    expect(sqlLiteral(null, 'VARCHAR2(20)', 'Oracle')).toBe('null');
  });

  it('chunks text above the 4000-byte literal limit with to_clob()', () => {
    const sql = sqlLiteral('x'.repeat(5000), 'CLOB', 'Oracle');
    expect(sql.startsWith('to_clob(')).toBe(true);
    expect(sql.split('to_clob(').length - 1).toBe(2);
    expect(sql).toContain("to_clob('" + 'x'.repeat(4000) + "')");
    expect(sql).toContain("to_clob('" + 'x'.repeat(1000) + "')");
  });

  it('never cuts a multi-byte character while chunking', () => {
    const sql = sqlLiteral('中'.repeat(2000), 'CLOB', 'Oracle');
    const chunks = sql.split(' || ');
    expect(chunks.length).toBe(2);
    expect(chunks[0]).toBe(`to_clob('${'中'.repeat(1333)}')`);
    expect(chunks[1]).toBe(`to_clob('${'中'.repeat(667)}')`);
  });

  it('refuses over-long text for non-LOB columns', () => {
    expect(() => sqlLiteral('x'.repeat(4001), 'VARCHAR2(4000)', 'Oracle')).toThrow();
  });

  it('builds ANSI temporal literals that ignore NLS_DATE_FORMAT', () => {
    expect(sqlLiteral('2026-11-03 10:20:30', 'DATE', 'Oracle')).toBe("to_date('2026-11-03 10:20:30', 'YYYY-MM-DD HH24:MI:SS')");
    expect(sqlLiteral('2026-11-03', 'DATE', 'Oracle')).toBe("date '2026-11-03'");
    expect(sqlLiteral('2026-11-03 10:20:30.123', 'TIMESTAMP(3)', 'Oracle')).toBe("timestamp '2026-11-03 10:20:30.123'");
  });

  it('keeps numbers unquoted but quotes numeric text in char columns', () => {
    expect(sqlLiteral('123', 'NUMBER(10)', 'Oracle')).toBe('123');
    expect(sqlLiteral('00123', 'VARCHAR2(10)', 'Oracle')).toBe("'00123'");
  });

  it('uses hextoraw() for small binary values', () => {
    expect(binaryLiteral('AB', 'Oracle', 'RAW(16)')).toBe("hextoraw('AB')");
    expect(sqlLiteral('AB', 'RAW(16)', 'Oracle')).toBe("hextoraw('AB')");
  });

  it('assembles large BLOBs from 2000-byte RAW chunks', () => {
    const hex = 'AB'.repeat(3000);
    const sql = binaryLiteral(hex, 'Oracle', 'BLOB');
    expect(sql).toBe(`to_blob(utl_raw.concat(hextoraw('${'AB'.repeat(2000)}'), hextoraw('${'AB'.repeat(1000)}')))`);
  });

  it('refuses binary values a RAW column cannot hold', () => {
    expect(() => binaryLiteral('AB'.repeat(3000), 'Oracle', 'RAW(2000)')).toThrow();
  });
});

describe('non Oracle dialects', () => {
  it('leaves plain values untouched', () => {
    expect(sqlLiteral('5', 'INT', 'MySQL')).toBe('5');
    expect(sqlLiteral('2026-11-03 10:20:30', 'DATETIME', 'MySQL')).toBe("'2026-11-03 10:20:30'");
    expect(binaryLiteral('AB', 'MySQL', 'BLOB')).toBe("x'AB'");
    expect(binaryLiteral('AB', 'PostgreSQL', 'BYTEA')).toBe("'\\xAB'::bytea");
  });
});
