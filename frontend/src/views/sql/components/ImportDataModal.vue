<script>
import { mapGetters } from 'vuex';
import appLogger from '@/utils/logger';
import ReadOnlyEditor from '@/components/editor/ReadOnlyEditor.vue';
import { EVENT_BUS_NAME_LIST } from '@/utils/eventBusName';
import { sqlQualifier, quoteName, formatSqlValue } from '@/utils/sqlLiteral';

const ROW_LIMIT = 200000;
const DELIMITER_OPTIONS = [
  { label: ',', value: ',' },
  { label: ';', value: ';' },
  { label: 'Tab', value: '\t' },
  { label: '|', value: '|' },
  { label: 'custom', value: '__custom__' }
];
const BATCH_OPTIONS = [50, 100, 200, 500, 1000].map((n) => ({ label: String(n), value: n }));
// The target-table dropdown only surfaces the closest matches instead of the whole schema.
const TABLE_SUGGESTION_LIMIT = 20;

// Prefix matches rank before substring matches, so typing `user` surfaces users/user_archive first.
function rankTableNames(names, keyword) {
  const lower = keyword.toLowerCase();
  const prefix = [];
  const rest = [];
  for (const name of names) {
    const value = name.toLowerCase();
    if (value.startsWith(lower)) {
      prefix.push(name);
    } else if (value.includes(lower)) {
      rest.push(name);
    }
  }
  return prefix.concat(rest);
}

export default {
  name: 'ImportDataModal',
  components: { ReadOnlyEditor },
  data() {
    return {
      visible: false,
      step: 0,
      context: { levels: null, sessionId: '', dsType: '', dataSourceType: '', defaultTable: '', node: null },
      // Step 1: source & parse
      fileName: '',
      fileType: '',
      delimiter: ',',
      customDelimiter: '',
      encoding: 'utf-8',
      firstRowHeader: true,
      sheetNames: [],
      activeSheet: '',
      headers: [],
      previewRows: [],
      totalRows: 0,
      parsing: false,
      parseError: '',
      rowLimit: ROW_LIMIT,
      delimiterOptions: DELIMITER_OPTIONS,
      // Step 2: target table
      targetTable: '',
      // The table prefilled from the current result set, kept so the UI can mark it as an editable default.
      presetTable: '',
      structureLoading: false,
      structureError: '',
      targetColumns: [],
      // Target-table dropdown: the schema's table names, loaded once and filtered locally.
      tableOptions: [],
      tableOptionsLoading: false,
      tableKeyword: '',
      // Table whose structure is currently loaded, used to detect a real target change.
      loadedTable: '',
      // Step 3: mapping
      mapping: [],
      batchSize: 200,
      emptyAsNull: true,
      batchOptions: BATCH_OPTIONS,
      // Step 4: execute
      running: false,
      progress: { done: 0, total: 0, rows: 0 },
      outcome: null,
      txSupported: true,
      acknowledgeNoTx: false,
      batches: []
    };
  },
  computed: {
    ...mapGetters(['supportRdbTx']),
    dsType() {
      return this.context.dsType || '';
    },
    stepLabels() {
      return [this.$t('shu-ju-yuan'), this.$t('mu-biao-biao'), this.$t('zi-duan-ying-she'), this.$t('zhi-xing')];
    },
    effectiveDelimiter() {
      return this.delimiter === '__custom__' ? this.customDelimiter || ',' : this.delimiter;
    },
    previewColumns() {
      return this.headers.map((h, i) => ({ title: h, dataIndex: `c${i}`, key: `c${i}`, ellipsis: true, width: 140 }));
    },
    previewData() {
      return this.previewRows.map((row, ri) => {
        const obj = { __key: ri };
        this.headers.forEach((h, i) => {
          const v = Array.isArray(row) ? row[i] : null;
          obj[`c${i}`] = v == null ? '' : v instanceof Date ? v.toLocaleString() : String(v);
        });
        return obj;
      });
    },
    // Top matches for the target-table dropdown; the full schema list is filtered as the user types.
    tableSuggestions() {
      const keyword = String(this.tableKeyword || '').trim();
      const names = keyword ? rankTableNames(this.tableOptions, keyword) : this.tableOptions;
      return names.slice(0, TABLE_SUGGESTION_LIMIT).map((name) => ({ value: name }));
    },
    targetFieldOptions() {
      return this.targetColumns.map((c) => ({
        label: `${c.name}${c.dbType ? ' (' + c.dbType + ')' : ''}${c.isKey ? ' *' : ''}`,
        value: c.name
      }));
    },
    mappedCount() {
      return this.mapping.filter((m) => m.targetField).length;
    },
    estimatedBatchCount() {
      if (!this.totalRows || !this.mappedCount) {
        return 0;
      }
      const size = Math.max(1, Math.min(1000, Number(this.batchSize) || 200));
      return this.isOracleFamily(this.dsType) ? this.totalRows : Math.ceil(this.totalRows / size);
    },
    previewSql() {
      try {
        const rows = this._dataRows || [];
        const mapped = this.mapping.filter((m) => m.targetField);
        if (!rows.length || !mapped.length || !this.targetTable) {
          return '';
        }
        const qualifier = sqlQualifier(this.dsType);
        const table = quoteName(this.targetTable, qualifier);
        const cols = mapped.map((m) => quoteName(m.targetField, qualifier)).join(', ');
        const metas = mapped.map((m) => this.targetColumns.find((c) => c.name === m.targetField));
        const opts = {
          emptyAsNull: this.emptyAsNull,
          tooLongMessage: this.$t('nei-rong-chao-guo-oracle-dan-tiao-yu-ju-xie-ru-shang-xian')
        };
        const r = rows[0];
        const vals = mapped
          .map((m, i) => formatSqlValue(Array.isArray(r) ? r[m.dataIndex] : null, metas[i] ? metas[i].dbType : undefined, this.dsType, opts))
          .join(', ');
        return `insert into ${table} (${cols}) values (${vals})`;
      } catch (e) {
        return '';
      }
    },
    canNext() {
      if (this.step === 0) {
        return !!this.headers.length && !this.parseError && this.totalRows > 0 && this.totalRows <= this.rowLimit;
      }
      if (this.step === 1) {
        return !!this.targetColumns.length;
      }
      if (this.step === 2) {
        return this.mappedCount > 0;
      }
      return false;
    }
  },
  methods: {
    isOracleFamily(dsType) {
      return /Oracle/i.test(String(dsType || ''));
    },
    handleShow(ctx = {}) {
      this.reset();
      this.context = {
        levels: ctx.levels || null,
        sessionId: ctx.sessionId || '',
        dsType: ctx.dsType || '',
        dataSourceType: ctx.dataSourceType || ctx.dsType || '',
        defaultTable: ctx.defaultTable || '',
        node: ctx.node || null
      };
      this.presetTable = ctx.defaultTable || '';
      this.targetTable = this.presetTable;
      // The tx feature flag is only present for data sources that register it; treat unknown as transactional
      // so imports keep the single-transaction (rollback-on-failure) behavior by default.
      const txFlag = this.supportRdbTx ? this.supportRdbTx(this.context.dsType) : undefined;
      this.txSupported = txFlag === undefined || txFlag === null ? true : !!txFlag;
      this.visible = true;
      this.loadTableOptions();
    },
    reset() {
      this.step = 0;
      this.fileName = '';
      this.fileType = '';
      this.delimiter = ',';
      this.customDelimiter = '';
      this.encoding = 'utf-8';
      this.firstRowHeader = true;
      this.sheetNames = [];
      this.activeSheet = '';
      this.headers = [];
      this.previewRows = [];
      this.totalRows = 0;
      this.parsing = false;
      this.parseError = '';
      this.targetTable = '';
      this.presetTable = '';
      this.structureLoading = false;
      this.structureError = '';
      this.targetColumns = [];
      this.tableOptions = [];
      this.tableKeyword = '';
      this.loadedTable = '';
      this.mapping = [];
      this.batchSize = 200;
      this.emptyAsNull = true;
      this.running = false;
      this.progress = { done: 0, total: 0, rows: 0 };
      this.outcome = null;
      this.acknowledgeNoTx = false;
      this.batches = [];
      this._file = null;
      this._wb = null;
      this._XLSX = null;
      this._allRows = [];
      this._dataRows = [];
    },
    handleClose() {
      if (this.running) {
        return;
      }
      this.visible = false;
      this.reset();
    },
    beforeUpload(file) {
      this.handleFile(file);
      return false;
    },
    async handleFile(file) {
      this._file = file;
      this.fileName = file.name || '';
      const ext = String(this.fileName.split('.').pop() || '').toLowerCase();
      this.fileType = ext === 'csv' || ext === 'txt' ? 'csv' : 'excel';
      this.parseError = '';
      this.parsing = true;
      try {
        await this.parseCurrent();
      } catch (e) {
        appLogger.error('import parse failed', e);
        this.parseError = (e && e.message) || this.$t('jie-xi-shi-bai');
      } finally {
        this.parsing = false;
      }
    },
    async parseCurrent() {
      if (!this._file) {
        return;
      }
      const XLSX = await import('xlsx');
      this._XLSX = XLSX;
      const buf = await this._file.arrayBuffer();
      let wb;
      if (this.fileType === 'csv') {
        const text = new TextDecoder(this.encoding).decode(buf);
        wb = XLSX.read(text, { type: 'string', cellDates: true, raw: true, FS: this.effectiveDelimiter });
      } else {
        wb = XLSX.read(new Uint8Array(buf), { type: 'array', cellDates: true });
      }
      this._wb = wb;
      this.sheetNames = wb.SheetNames || [];
      if (!this.sheetNames.includes(this.activeSheet)) {
        this.activeSheet = this.sheetNames[0] || '';
      }
      this.parseActiveSheet();
    },
    parseActiveSheet() {
      if (!this._wb || !this._XLSX || !this.activeSheet) {
        return;
      }
      const ws = this._wb.Sheets[this.activeSheet];
      const rows = this._XLSX.utils.sheet_to_json(ws, { header: 1, raw: true, defval: null, blankrows: false });
      this._allRows = rows;
      this.applyHeaderAndRows();
      this.buildAutoMapping();
    },
    applyHeaderAndRows() {
      const rows = this._allRows || [];
      this.parseError = '';
      if (!rows.length) {
        this.headers = [];
        this._dataRows = [];
        this.previewRows = [];
        this.totalRows = 0;
        return;
      }
      let width = 0;
      for (const r of rows) {
        if (Array.isArray(r) && r.length > width) {
          width = r.length;
        }
      }
      let headers = [];
      let dataRows = [];
      if (this.firstRowHeader) {
        const hrow = Array.isArray(rows[0]) ? rows[0] : [];
        for (let i = 0; i < width; i++) {
          const h = hrow[i];
          headers.push(h == null || String(h).trim() === '' ? `col${i + 1}` : String(h).trim());
        }
        dataRows = rows.slice(1);
      } else {
        for (let i = 0; i < width; i++) {
          headers.push(`col${i + 1}`);
        }
        dataRows = rows.slice();
      }
      this.headers = headers;
      this._dataRows = dataRows;
      this.totalRows = dataRows.length;
      this.previewRows = dataRows.slice(0, 20);
      if (this.totalRows > this.rowLimit) {
        this.parseError = this.$t('zong-hang-shu-chao-guo-shang-xian', [this.rowLimit]);
      }
    },
    async reparseCsv() {
      if (this.fileType !== 'csv' || !this._file) {
        return;
      }
      this.parsing = true;
      try {
        await this.parseCurrent();
      } catch (e) {
        appLogger.error('import reparse failed', e);
        this.parseError = (e && e.message) || this.$t('jie-xi-shi-bai');
      } finally {
        this.parsing = false;
      }
    },
    onSheetChange() {
      this.parseActiveSheet();
    },
    onHeaderToggle() {
      this.applyHeaderAndRows();
      this.buildAutoMapping();
    },
    buildAutoMapping() {
      if (!this.headers.length) {
        this.mapping = [];
        return;
      }
      const lower = {};
      this.targetColumns.forEach((c) => {
        lower[String(c.name).toLowerCase()] = c.name;
      });
      const first = this._dataRows && this._dataRows[0] ? this._dataRows[0] : [];
      this.mapping = this.headers.map((h, i) => {
        const v = Array.isArray(first) ? first[i] : null;
        return {
          dataCol: h,
          dataIndex: i,
          sample: v == null ? '' : (v instanceof Date ? v.toLocaleString() : String(v)).slice(0, 40),
          targetField: lower[String(h).toLowerCase()] || ''
        };
      });
    },
    // Table names of the current schema; the metadata call is made once and then filtered locally.
    async loadTableOptions() {
      if (this.tableOptionsLoading || this.tableOptions.length || !this.context.levels) {
        return;
      }
      this.tableOptionsLoading = true;
      try {
        const res = await this.$services.dmBrowseListLeaf({ data: { levels: this.context.levels, leafType: 'TABLE' } });
        if (res.success && Array.isArray(res.data)) {
          this.tableOptions = res.data
            .map((item) => (typeof item === 'string' ? item : item.objName))
            .filter(Boolean)
            .sort((a, b) => a.localeCompare(b));
        }
      } catch (e) {
        appLogger.error('import table list failed', e);
      } finally {
        this.tableOptionsLoading = false;
      }
    },
    handleTableSearch(keyword) {
      this.tableKeyword = String(keyword || '');
      this.loadTableOptions();
    },
    handleTargetTableChange(value) {
      const table = String(value || '').trim();
      this.loadTableOptions();
      // Switching to another table invalidates the loaded columns, so the mapping step reloads them.
      if (this.loadedTable && table !== this.loadedTable) {
        this.loadedTable = '';
        this.targetColumns = [];
        this.mapping = [];
        this.structureError = '';
      }
    },
    handleTablePicked() {
      // Picking a suggestion loads that table's columns right away.
      this.$nextTick(() => {
        if (this.targetTable && this.targetTable !== this.loadedTable) {
          this.fetchStructure();
        }
      });
    },
    async fetchStructure() {
      if (this.structureLoading) {
        return;
      }
      if (!this.targetTable) {
        this.$Message.warning(this.$t('qing-shu-ru-biao-ming'));
        return;
      }
      this.structureLoading = true;
      this.structureError = '';
      try {
        const res = await this.$services.dmBrowseRdbObjectDetail({
          data: { levels: this.context.levels, targetName: this.targetTable, targetType: 'TABLE' }
        });
        if (!res.success || !res.data || !Array.isArray(res.data.group)) {
          this.structureError = res.message || this.$t('huo-qu-biao-jie-gou-shi-bai');
          return;
        }
        const g = res.data.group.find((x) => x.type === 'RDB_COLUMN_GROUP');
        if (!g || !g.items || !g.items.length) {
          this.structureError = this.$t('huo-qu-biao-jie-gou-shi-bai');
          return;
        }
        this.targetColumns = g.items.map((c) => ({
          name: c.name,
          dbType: c.dbType || c.type || '',
          isKey: !!(c.attrs && c.attrs.isKey)
        }));
        this.loadedTable = this.targetTable;
        this.buildAutoMapping();
      } catch (e) {
        appLogger.error('import fetch structure failed', e);
        this.structureError = (e && e.message) || this.$t('huo-qu-biao-jie-gou-shi-bai');
      } finally {
        this.structureLoading = false;
      }
    },
    mappedType(m) {
      const c = this.targetColumns.find((x) => x.name === m.targetField);
      return c ? c.dbType : '';
    },
    goNext() {
      if (this.step === 1 && !this.targetColumns.length) {
        this.$Message.warning(this.$t('qing-xian-huo-qu-biao-jie-gou'));
        return;
      }
      if (!this.canNext) {
        return;
      }
      this.step = Math.min(3, this.step + 1);
      // A target table prefilled from the current result set is loaded as soon as the step opens,
      // so the mapping step already knows the columns; a retyped name still needs the explicit fetch.
      if (this.step === 1 && this.targetTable && !this.targetColumns.length && !this.structureLoading) {
        this.fetchStructure();
      }
    },
    goBack() {
      this.step = Math.max(0, this.step - 1);
    },
    buildBatches() {
      const mapped = this.mapping.filter((m) => m.targetField);
      if (!this.targetTable) {
        throw new Error(this.$t('qing-shu-ru-biao-ming'));
      }
      if (!mapped.length) {
        throw new Error(this.$t('zhi-shao-ying-she-yi-ge-zi-duan'));
      }
      const qualifier = sqlQualifier(this.dsType);
      const table = quoteName(this.targetTable, qualifier);
      const cols = mapped.map((m) => quoteName(m.targetField, qualifier)).join(', ');
      const metas = mapped.map((m) => this.targetColumns.find((c) => c.name === m.targetField));
      const opts = {
        emptyAsNull: this.emptyAsNull,
        tooLongMessage: this.$t('nei-rong-chao-guo-oracle-dan-tiao-yu-ju-xie-ru-shang-xian')
      };
      const tuple = (row) =>
        '(' +
        mapped
          .map((m, i) => formatSqlValue(Array.isArray(row) ? row[m.dataIndex] : null, metas[i] ? metas[i].dbType : undefined, this.dsType, opts))
          .join(', ') +
        ')';
      const rows = this._dataRows || [];
      const multi = !this.isOracleFamily(this.dsType);
      const size = Math.max(1, Math.min(1000, Number(this.batchSize) || 200));
      const batches = [];
      for (let i = 0; i < rows.length; i += size) {
        const chunk = rows.slice(i, i + size);
        if (multi) {
          batches.push(`insert into ${table} (${cols}) values ${chunk.map(tuple).join(', ')}`);
        } else {
          chunk.forEach((r) => batches.push(`insert into ${table} (${cols}) values ${tuple(r)}`));
        }
      }
      return batches;
    },
    buildBatchRows() {
      const rows = (this._dataRows || []).length;
      const size = Math.max(1, Math.min(1000, Number(this.batchSize) || 200));
      const list = [];
      if (this.isOracleFamily(this.dsType)) {
        for (let i = 0; i < rows; i++) {
          list.push(1);
        }
      } else {
        for (let i = 0; i < rows; i += size) {
          list.push(Math.min(size, rows - i));
        }
      }
      return list;
    },
    startImport() {
      if (this.running) {
        return;
      }
      let batches;
      try {
        batches = this.buildBatches();
      } catch (e) {
        this.$Message.error(e.message || this.$t('dao-ru-shi-bai'));
        return;
      }
      if (!batches.length) {
        this.$Message.warning(this.$t('wu-shu-ju'));
        return;
      }
      this.batches = batches;
      this.outcome = null;
      this.running = true;
      this.progress = { done: 0, total: batches.length, rows: 0 };
      this.$bus.emit(EVENT_BUS_NAME_LIST.EXECUTE_IMPORT, {
        batches,
        batchRows: this.buildBatchRows(),
        sessionId: this.context.sessionId,
        useTransaction: this.txSupported,
        totalRows: this.totalRows
      });
    },
    cancelImport() {
      this.$bus.emit(EVENT_BUS_NAME_LIST.CANCEL_IMPORT, { sessionId: this.context.sessionId });
    },
    handleProgress(p = {}) {
      if (p.sessionId && p.sessionId !== this.context.sessionId) {
        return;
      }
      this.progress = { done: p.done || 0, total: p.total || this.progress.total, rows: p.rows || 0 };
    },
    handleOutcome(o = {}) {
      if (o.sessionId && o.sessionId !== this.context.sessionId) {
        return;
      }
      this.running = false;
      this.outcome = o;
      if (o.success) {
        this.$Message.success(this.$t('dao-ru-cheng-gong', [o.rows || this.totalRows]));
      } else if (o.cancelled) {
        this.$Message.warning(o.error || this.$t('yi-qu-xiao'));
      } else {
        this.$Message.error(o.error || this.$t('dao-ru-shi-bai'));
      }
    },
    failedSql() {
      if (this.outcome && typeof this.outcome.failedBatch === 'number') {
        return this.batches[this.outcome.failedBatch] || '';
      }
      return '';
    }
  },
  mounted() {
    this.$bus.on(EVENT_BUS_NAME_LIST.SHOW_IMPORT_MODAL, this.handleShow);
    this.$bus.on(EVENT_BUS_NAME_LIST.IMPORT_PROGRESS, this.handleProgress);
    this.$bus.on(EVENT_BUS_NAME_LIST.IMPORT_OUTCOME, this.handleOutcome);
  },
  beforeUnmount() {
    this.$bus.off(EVENT_BUS_NAME_LIST.SHOW_IMPORT_MODAL, this.handleShow);
    this.$bus.off(EVENT_BUS_NAME_LIST.IMPORT_PROGRESS, this.handleProgress);
    this.$bus.off(EVENT_BUS_NAME_LIST.IMPORT_OUTCOME, this.handleOutcome);
  }
};
</script>

<template>
  <CCModal v-model="visible" :title="$t('dao-ru')" :width="900" :mask-closable="false" transfer @on-cancel="handleClose">
    <div class="import-wizard">
      <div class="import-steps">
        <div v-for="(label, i) in stepLabels" :key="i" class="import-step" :class="{ active: i === step, done: i < step }">
          <span class="import-step-index">{{ i + 1 }}</span>
          <span class="import-step-label">{{ label }}</span>
        </div>
      </div>

      <!-- Step 1: source & parse -->
      <div v-show="step === 0" class="import-panel">
        <div class="import-form-row">
          <span class="import-label">{{ $t('xuan-ze-wen-jian') }}</span>
          <a-upload :before-upload="beforeUpload" :show-upload-list="false" accept=".csv,.txt,.xlsx,.xls,.xlsm">
            <a-button size="small">{{ $t('xuan-ze-wen-jian') }}</a-button>
          </a-upload>
          <span class="import-file-name">{{ fileName }}</span>
        </div>
        <div class="import-form-row" v-if="fileType === 'csv'">
          <span class="import-label">{{ $t('fen-ge-fu') }}</span>
          <a-select v-model:value="delimiter" :options="delimiterOptions" size="small" style="width: 120px" @change="reparseCsv" />
          <a-input
            v-if="delimiter === '__custom__'"
            v-model:value="customDelimiter"
            size="small"
            style="width: 80px"
            :placeholder="$t('zi-ding-yi')"
            @change="reparseCsv"
          />
          <span class="import-label">{{ $t('bian-ma') }}</span>
          <a-select
            v-model:value="encoding"
            size="small"
            style="width: 130px"
            :options="[
              { label: 'UTF-8', value: 'utf-8' },
              { label: 'GBK', value: 'gbk' },
              { label: 'Latin1', value: 'windows-1252' }
            ]"
            @change="reparseCsv"
          />
        </div>
        <div class="import-form-row" v-if="fileType === 'excel' && sheetNames.length > 1">
          <span class="import-label">{{ $t('gong-zuo-biao') }}</span>
          <a-select
            v-model:value="activeSheet"
            size="small"
            style="width: 200px"
            :options="sheetNames.map((s) => ({ label: s, value: s }))"
            @change="onSheetChange"
          />
        </div>
        <div class="import-form-row">
          <a-checkbox v-model:checked="firstRowHeader" @change="onHeaderToggle">{{ $t('shou-xing-wei-biao-tou') }}</a-checkbox>
          <span class="import-total">{{ $t('zong-hang-shu') }}: {{ totalRows }}</span>
          <a-spin v-if="parsing" size="small" />
        </div>
        <div v-if="parseError" class="import-error">{{ parseError }}</div>
        <div class="import-preview">
          <a-table
            v-if="headers.length"
            :columns="previewColumns"
            :data-source="previewData"
            :pagination="false"
            :scroll="{ x: 'max-content', y: 260 }"
            size="small"
            bordered
            row-key="__key"
          />
          <div v-else class="import-empty">{{ $t('jie-xi-yu-lan') }}</div>
        </div>
      </div>

      <!-- Step 2: target table -->
      <div v-show="step === 1" class="import-panel">
        <div class="import-form-row">
          <span class="import-label">{{ $t('mu-biao-biao') }}</span>
          <a-auto-complete
            v-model:value="targetTable"
            size="small"
            style="width: 260px"
            :placeholder="$t('qing-shu-ru-biao-ming')"
            allow-clear
            :options="tableSuggestions"
            :filter-option="false"
            @focus="loadTableOptions"
            @search="handleTableSearch"
            @change="handleTargetTableChange"
            @select="handleTablePicked"
          />
          <a-button size="small" :loading="structureLoading" @click="fetchStructure">{{ $t('huo-qu-biao-jie-gou') }}</a-button>
        </div>
        <div v-if="presetTable && targetTable === presetTable" class="import-hint">{{ $t('mu-biao-biao-mo-ren-qu-zi-dang-qian-jie-guo-ji') }}</div>
        <div v-if="structureError" class="import-error">{{ structureError }}</div>
        <div class="import-preview">
          <a-table
            v-if="targetColumns.length"
            :columns="[
              { title: $t('zi-duan'), dataIndex: 'name', key: 'name' },
              { title: $t('lei-xing'), dataIndex: 'dbType', key: 'dbType' },
              { title: $t('zhu-jian'), dataIndex: 'isKey', key: 'isKey' }
            ]"
            :data-source="targetColumns"
            :pagination="false"
            :scroll="{ y: 300 }"
            size="small"
            bordered
            row-key="name"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'isKey'">
                <a-tag v-if="record.isKey" color="blue">{{ $t('zhu-jian') }}</a-tag>
              </template>
            </template>
          </a-table>
          <div v-else class="import-empty">{{ $t('qing-xian-huo-qu-biao-jie-gou') }}</div>
        </div>
      </div>

      <!-- Step 3: mapping -->
      <div v-show="step === 2" class="import-panel">
        <div class="import-form-row">
          <span class="import-label">{{ $t('pi-ci-da-xiao') }}</span>
          <a-select v-model:value="batchSize" :options="batchOptions" size="small" style="width: 100px" />
          <a-checkbox v-model:checked="emptyAsNull">{{ $t('kong-dan-yuan-ge-shi-wei-null') }}</a-checkbox>
        </div>
        <div class="import-mapping-head">
          <span class="mh-col">{{ $t('shu-ju-lie') }}</span>
          <span class="mh-sample">{{ $t('yang-li-zhi') }}</span>
          <span class="mh-target">{{ $t('mu-biao-zi-duan') }}</span>
          <span class="mh-type">{{ $t('mu-biao-lie-xing') }}</span>
        </div>
        <div class="import-mapping">
          <div v-for="(m, idx) in mapping" :key="idx" class="import-mapping-row">
            <span class="mh-col">{{ m.dataCol }}</span>
            <span class="mh-sample" :title="m.sample">{{ m.sample }}</span>
            <span class="mh-target">
              <a-select
                v-model:value="m.targetField"
                :options="targetFieldOptions"
                size="small"
                style="width: 100%"
                allow-clear
                show-search
                :filter-option="(input, option) => String(option.label).toLowerCase().includes(input.toLowerCase())"
                :placeholder="$t('mu-biao-zi-duan')"
              />
            </span>
            <span class="mh-type">{{ mappedType(m) }}</span>
          </div>
        </div>
        <div class="import-preview-sql">
          <div class="import-label">{{ $t('insert-yu-lan') }}</div>
          <ReadOnlyEditor v-if="previewSql" :text="previewSql" language="sql" :ds-type="dsType" :border="1" :max-height="120" />
          <div v-else class="import-empty">{{ $t('zhi-shao-ying-she-yi-ge-zi-duan') }}</div>
        </div>
      </div>

      <!-- Step 4: execute -->
      <div v-show="step === 3" class="import-panel import-panel--execute">
        <div class="import-summary">
          <span>{{ $t('mu-biao-biao') }}: {{ targetTable }}</span>
          <span>{{ $t('zong-hang-shu') }}: {{ totalRows }}</span>
          <span>{{ $t('pi-ci') }}: {{ estimatedBatchCount }}</span>
        </div>
        <a-alert v-if="!txSupported" type="warning" show-icon :message="$t('gai-shu-ju-yuan-bu-zhi-chi-shi-wu')" style="margin-bottom: 8px" />
        <a-checkbox v-if="!txSupported" v-model:checked="acknowledgeNoTx">{{ $t('wo-yi-zhi-xi-an-zi-dong-ti-jiao-dao-ru') }}</a-checkbox>
        <div v-if="running" class="import-progress">
          <a-progress :percent="progress.total ? Math.round((progress.done / progress.total) * 100) : 0" size="small" />
          <span class="import-progress-text">{{ `${progress.done}/${progress.total} ${$t('pi-ci')}, ${progress.rows} ${$t('hang')}` }}</span>
        </div>
        <div v-if="outcome" class="import-outcome">
          <div v-if="outcome.success" class="import-success">{{ $t('dao-ru-cheng-gong', [outcome.rows || totalRows]) }}</div>
          <div v-else-if="outcome.cancelled" class="import-cancelled">{{ $t('yi-qu-xiao') }}</div>
          <template v-else>
            <div class="import-error">{{ outcome.error || $t('dao-ru-shi-bai') }}</div>
            <div v-if="txSupported" class="import-rollback">{{ $t('dao-ru-shi-bai-yi-hui-gun') }}</div>
            <div v-if="typeof outcome.failedBatch === 'number'" class="import-failed-batch">{{ `${$t('pi-ci')} #${outcome.failedBatch + 1}` }}</div>
            <ReadOnlyEditor v-if="failedSql()" :text="failedSql()" language="sql" :ds-type="dsType" :border="1" :max-height="120" />
          </template>
        </div>
      </div>
    </div>

    <template #footer>
      <a-button v-if="step > 0 && !running" size="small" @click="goBack">{{ $t('shang-yi-bu') }}</a-button>
      <a-button v-if="step < 3" size="small" type="primary" :disabled="!canNext" @click="goNext">{{ $t('xia-yi-bu') }}</a-button>
      <a-button
        v-if="step === 3 && !running && !(outcome && outcome.success)"
        size="small"
        type="primary"
        :disabled="!txSupported && !acknowledgeNoTx"
        @click="startImport"
      >
        {{ $t('kai-shi-dao-ru') }}
      </a-button>
      <a-button v-if="running" size="small" danger @click="cancelImport">{{ $t('qu-xiao') }}</a-button>
      <a-button size="small" :disabled="running" @click="handleClose">{{ $t('guan-bi') }}</a-button>
    </template>
  </CCModal>
</template>

<style scoped lang="less">
.import-wizard {
  .import-steps {
    display: flex;
    gap: 8px;
    margin-bottom: 16px;

    .import-step {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 10px;
      color: var(--text-tertiary);
      font-size: 13px;

      .import-step-index {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 18px;
        height: 18px;
        border-radius: 50%;
        background: var(--text-disabled);
        color: var(--text-inverse);
        font-size: 11px;
      }

      &.active {
        background: var(--bg-hover);
        color: var(--text-primary);

        .import-step-index {
          background: var(--text-primary);
        }
      }

      &.done {
        color: var(--text-secondary);

        .import-step-index {
          background: var(--text-tertiary);
        }
      }
    }
  }

  .import-panel {
    min-height: 360px;
  }

  // The execute step only holds the summary plus progress/outcome, so it grows with its content
  // instead of reserving the same tall body as the parsing and mapping steps.
  .import-panel--execute {
    min-height: 0;
  }

  .import-form-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    flex-wrap: wrap;
  }

  .import-label {
    font-size: 13px;
    color: var(--text-secondary);
  }

  .import-hint {
    margin: -4px 0 12px;
    font-size: 13px;
    color: var(--text-tertiary);
  }

  .import-file-name {
    font-size: 13px;
    color: var(--text-primary);
  }

  .import-total {
    font-size: 13px;
    color: var(--text-secondary);
  }

  .import-error {
    color: var(--error-color);
    font-size: 13px;
    margin-bottom: 8px;
    word-break: break-all;
  }

  .import-empty {
    color: var(--text-tertiary);
    font-size: 13px;
    padding: 24px 0;
    text-align: center;
  }

  .import-preview {
    margin-top: 8px;
  }

  .import-mapping-head,
  .import-mapping-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 4px 0;
    font-size: 13px;
  }

  .import-mapping-head {
    font-weight: 500;
    color: var(--text-secondary);
    border-bottom: 1px solid var(--border-secondary);
  }

  .import-mapping {
    max-height: 220px;
    overflow-y: auto;
  }

  .mh-col {
    flex: 0 0 110px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mh-sample {
    flex: 1 1 120px;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-tertiary);
  }

  .mh-target {
    flex: 0 0 200px;
  }

  .mh-type {
    flex: 0 0 90px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-tertiary);
  }

  .import-preview-sql {
    margin-top: 12px;
  }

  .import-summary {
    display: flex;
    gap: 24px;
    font-size: 13px;
    color: var(--text-secondary);
    margin-bottom: 12px;
  }

  .import-progress {
    margin-top: 12px;

    .import-progress-text {
      font-size: 13px;
      color: var(--text-secondary);
    }
  }

  .import-outcome {
    margin-top: 12px;

    .import-success {
      color: var(--success-color);
      font-size: 13px;
      margin-bottom: 6px;
    }

    .import-cancelled {
      color: var(--warning-color);
      font-size: 13px;
    }

    .import-rollback {
      color: var(--warning-color);
      font-size: 13px;
      margin: 4px 0;
    }

    .import-failed-batch {
      font-size: 13px;
      color: var(--text-secondary);
      margin: 4px 0;
    }
  }
}
</style>
