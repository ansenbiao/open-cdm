<template>
  <div class="home" :class="{ 'home--sql': isSqlRoute }">
    <dm-water-mark :input-text="watermarkStr" ref="watermark" v-if="!isDesktop && globalSetting.enableWaterMark" />

    <template v-if="showChild">
      <Transition name="layout-switch" mode="out-in">
        <div v-if="isSqlRoute" key="sql" class="sql-layout">
          <header class="sql-compact-header">
            <div class="sql-compact-header__brand" @click="handleGoAppHome">
              <AppBrandLogo />
              <span class="sql-compact-header__title">{{ $t('sql-cha-xun') }}</span>
            </div>
            <div class="sql-compact-header__actions">
              <button type="button" class="sql-compact-header__back" @click="handleGoAppHome">
                <span>{{ $t('fan-hui-gong-zuo-tai') }}</span>
              </button>
              <AppUserActions compact @check-version="checkVersion(true)" />
            </div>
          </header>
          <div class="sql-layout-body">
            <router-view />
          </div>
        </div>
        <div v-else key="app" class="app-layout">
          <AppSidebar />
          <main class="app-main">
            <div class="app-main-body">
              <div class="app-main-card">
                <AppContentHeader @check-version="checkVersion(true)" />
                <div class="app-main-card__body">
                  <router-view />
                </div>
              </div>
            </div>
          </main>
        </div>
      </Transition>
    </template>

    <div v-else class="home-entry-loading">
      <a-spin />
      <span>{{ $t('zheng-zai-jia-zai') }}</span>
    </div>

    <div class="user-expr-tip" v-if="userInfo.subAccountPwdValidDays !== null && userInfo.subAccountPwdValidDays < limitDays">
      {{ $t('gen-ju-zhu-zhang-hao-she-zhi-de-mi-ma-shi-xiao-ce-lue', [userInfo.subAccountPwdValidDays + 1]) }}
    </div>
    <CCModal v-model="showDetailModal" :title="selectedCellDetail.column.property" v-if="showDetailModal" key="showCellDetailModal" :width="800">
      <div class="cell-detail">
        <template v-if="cellEdit.active">
          <ReadOnlyEditor v-if="cellDetailJsonView" :text="cellDetailContent" language="json" :border="0" :max-height="360" />
          <a-textarea
            v-else
            v-model:value="cellDetailContent"
            :rows="15"
            :disabled="cellEdit.setNull"
            style="font-family: monospace; font-size: 12px"
          />
          <div class="cell-edit-toolbar">
            <a-checkbox v-model:checked="cellEdit.setNull">NULL</a-checkbox>
            <span class="cell-edit-hint" v-if="cellEdit.columnMeta">{{ cellEdit.columnMeta.dbType || cellEdit.columnMeta.type }}</span>
            <template v-if="cellEdit.columnMeta && isBinaryColumnType(cellEdit.columnMeta.dbType)">
              <a-radio-group :value="cellEdit.binaryAsText" size="small" @change="(e) => handleBinaryModeToggle(e.target.value)">
                <a-radio-button :value="false">{{ cellEditHexLabel }}</a-radio-button>
                <a-radio-button :value="true">{{ $t('wen-ben') }}</a-radio-button>
              </a-radio-group>
              <span class="cell-edit-hint">
                {{ cellEdit.binaryAsText ? $t('wen-ben-jiang-an-utf-8-zi-jie-xie-ru') : $t('er-jin-zhi-bian-ji-xian-shi-mo-shi') }}
              </span>
            </template>
          </div>
          <div v-if="cellEdit.sql" class="cell-edit-sql-preview">
            <div class="cell-edit-sql-label">{{ $t('jiang-zhi-xing-de-geng-xin-yu-ju') }}</div>
            <pre>{{ cellEdit.sql }}</pre>
          </div>
        </template>
        <template v-else>
          <ReadOnlyEditor
            v-if="cellDetailJsonView"
            :text="cellDetailContent"
            language="json"
            :format-json="selectedCellDetail.complete && !hasMoreData && !cellDetailLoading"
            :border="0"
            :max-height="360"
          />
          <template v-else-if="cellDecodedText">
            <div class="cell-decode-hint">{{ $t('er-jin-zhi-yi-an-wen-ben-xian-shi') }}</div>
            <ReadOnlyEditor :text="cellDecodedText" language="plaintext" :border="0" :max-height="360" />
          </template>
          <a-textarea v-else v-model:value="cellDetailContent" :rows="15" readonly style="font-family: monospace; font-size: 12px" />
        </template>
        <div v-if="cellDetailLoading" class="cell-detail-loading">
          <a-spin :spinning="cellDetailLoading" />
          <span style="margin-left: 8px">{{ $t('zheng-zai-jia-zai') }}</span>
        </div>
      </div>
      <template #footer>
        <a-button v-if="hasMoreData && !cellDetailLoading && !selectedCellDetail.error && !selectedCellDetail.mask" @click="handleLoadMoreCellData">
          {{ $t('jia-zai-geng-duo') }}
        </a-button>
        <a-button v-if="isJsonCell" @click="cellDetailJsonView = !cellDetailJsonView">
          {{ $t(cellDetailJsonView ? 'query-text-view' : 'query-json-view') }}
        </a-button>
        <template v-if="cellEdit.active">
          <a-button type="primary" :loading="cellEdit.generating" @click="handleGenerateUpdateSql">{{ $t('bao-cun') }}</a-button>
          <a-button @click="handleCancelCellEdit">{{ $t('qu-xiao') }}</a-button>
        </template>
        <template v-else>
          <a-button v-if="cellEdit.eligible" type="primary" ghost :loading="cellEdit.checking" @click="handleStartCellEdit">
            {{ $t('bian-ji') }}
          </a-button>
          <a-button @click="handleDetailCopy">{{ $t('fu-zhi') }}</a-button>
          <a-button type="primary" @click="handleCloseCellDetailModal">{{ $t('guan-bi') }}</a-button>
        </template>
      </template>
    </CCModal>
    <CCModal v-model="showInactiveModal" :title="$t('cuo-wu')" :closable="false" :mask-closable="false">
      {{ inactiveMsg }}
      <template #footer>
        <Button @click="handleCloseModal">{{ $t('qu-xiao') }}</Button>
      </template>
    </CCModal>
    <a-modal v-model="showVersionDetailModal" :title="$t('ban-ben-jian-cha')" :width="800" :mask-closable="false" :closable="false">
      <div>
        <h2>{{ $t('xin-de-clouddm-ban-ben-ke-yong') }}</h2>
        <h2>{{ $t('dang-qian-ban-ben') }}{{ displayVersion }}</h2>
        <h2>{{ $t('zui-xin-ban-ben') }}{{ this.version.lastVersion }}</h2>
        <div style="max-height: 500px; overflow: auto">
          <pre v-for="(d, index) in version.detail" :key="index" v-html="d"></pre>
        </div>
      </div>
      <div class="footer">
        <a-button style="margin-right: 10px" type="primary" @click="handleDownload">
          {{ $t('xia-zai-zui-xin-ban-ben') }}
        </a-button>
        <a-button style="margin-right: 10px" @click="handleCloseVersionDetailModal">
          {{ $t('guan-bi') }}
        </a-button>
        <a-checkbox v-model="version.ignore">{{ $t('bu-zai-ti-shi') }}</a-checkbox>
      </div>
    </a-modal>
  </div>
</template>

<script>
import appLogger from '@/utils/logger';
import { mapGetters, mapState } from 'vuex';
import AppSidebar from '@/components/layout/AppSidebar';
import ReadOnlyEditor from '@/components/editor/ReadOnlyEditor.vue';
import AppBrandLogo from '@/components/layout/AppBrandLogo';
import AppContentHeader from '@/components/layout/AppContentHeader';
import AppUserActions from '@/components/layout/AppUserActions';
import { setApprovalProcessMixin, setOpPasswordMixin } from '@/mixins/modal';
import enterOpPwdMixin from '@/mixins/modal/enterOpPwdMixin';
import XEClipboard from 'xe-clipboard';
import DmWaterMark from '@/components/widgets/DmWaterMark';
import store from '@/store';
import dayjs from 'dayjs';
import fecha from 'fecha';
import { EVENT_BUS_NAME_LIST } from '@/utils/eventBusName';
import { decodeHexToText } from '@/utils/cellValueDisplay';
import { TAB_TYPE } from '@/const';
import { resolveWorkbenchFallbackPath, resolveWorkbenchRoute } from '@/utils/workbenchRoute';

export default {
  name: 'Home',
  components: {
    ReadOnlyEditor,
    DmWaterMark,
    AppSidebar,
    AppBrandLogo,
    AppContentHeader,
    AppUserActions
  },
  data() {
    return {
      showInactiveModal: false,
      inactiveMsg: '',
      showChild: false,
      showVersionDetailModal: false,
      version: {
        ignore: false,
        newVersion: false,
        prompt: false,
        lastVersion: '',
        detail: []
      },
      showDetailModal: false,
      testHtml: 'test\ntest\n',
      selectedCellDetail: {
        column: {},
        row: {},
        resultId: '',
        rowNumber: -1,
        colNumber: -1,
        cellValue: '',
        moreSize: 0,
        totalSize: 0,
        complete: true,
        error: false,
        mask: false,
        dsType: '',
        dataSourceType: '',
        connected: false,
        activeTabType: '',
        columnList: [],
        rowRaw: null
      },
      cellDetailContent: '',
      cellEditHexLabel: 'HEX',
      cellDetailJsonView: false,
      cellDetailLoading: false,
      hasMoreData: false,
      // Single-cell UPDATE editing state for the detail modal.
      cellEdit: {
        eligible: false,
        checking: false,
        active: false,
        generating: false,
        setNull: false,
        binaryAsText: false,
        sql: '',
        columnMeta: null,
        tableName: null,
        tableMeta: null,
        context: null,
        conditionCols: [],
        findColumn: null,
        savedContent: ''
      },
      cellUpdateTimeout: null,
      limitDays: 6,
      watermarkStr: '',
      store,
      fecha,
      dayjs
    };
  },
  mixins: [setOpPasswordMixin, setApprovalProcessMixin, enterOpPwdMixin],
  computed: {
    ...mapGetters(['isDesktop', 'displayVersion', 'isInternalUser']),
    ...mapState(['userInfo', 'myAuth', 'globalSetting', 'defaultRedirectUrl', 'dmGlobalSetting', 'mySystemMenuItems']),
    ...mapGetters(['isSaas']),
    isJsonCell() {
      return ['JSON_DOCUMENT', 'JSON', 'JSONB'].includes(this.selectedCellDetail.column.columnType) && !this.selectedCellDetail.error;
    },
    // In view mode, a text-bearing binary cell is decoded from hex to readable text and shown as a read-only document; real binary stays as hex.
    cellDecodedText() {
      if (this.cellEdit.active || this.selectedCellDetail.error || this.selectedCellDetail.mask) {
        return null;
      }
      if (!this.isBinaryColumnType(this.selectedCellDetail.column.columnType)) {
        return null;
      }
      return decodeHexToText(this.cellDetailContent);
    },
    isSqlRoute() {
      return this.$route.path === '/sql' || this.$route.path.startsWith('/sql/');
    }
  },
  async created() {
    await this.$store.dispatch('getDmGlobalConfig');

    if (this.$route.path === '/') {
      await this.$router.replace({ path: this.defaultRedirectUrl || '/sql' }).catch(() => {});
    }

    this.showChild = true;
    if (this.globalSetting.enableWaterMark) {
      const waterMark = await this.$services.rdpUserWatermark();
      this.watermarkStr = `${waterMark.data.user_name}_${waterMark.data.user_phone}`;
    }
    this.$bus.on('setOpPasswordModal', (edit = false) => {
      if (this.globalSetting.authOpPassword) {
        this.setOpPasswordModal(edit);
      }
    });
    this.$bus.on('showCellDetailModal', async (data) => {
      appLogger.debug('showCellDetailModal', data);
      if (!this.showDetailModal) {
        this.showDetailModal = true;
        this.resetCellEditState();
        this.cellEdit.eligible = this.checkCellEditEligibility(data);
        this.cellEdit.context = data.updateContext || null;
        this.$_cellEditStash = data.updateContext || null;
        this.selectedCellDetail = {
          row: data.row || {},
          column: data.column || {},
          resultId: data.resultId || '',
          rowNumber: data.rowNumber !== undefined ? data.rowNumber : -1,
          colNumber: data.colNumber !== undefined ? data.colNumber : -1,
          cellValue: data.cellValue || '',
          moreSize: data.moreSize || 0,
          totalSize: data.totalSize || 0,
          complete: data.complete !== undefined ? data.complete : true,
          error: data.error || false,
          mask: data.mask || false,
          dsType: data.dsType || '',
          dataSourceType: data.dataSourceType || '',
          connected: !!data.connected,
          activeTabType: data.activeTabType || '',
          columnList: data.columnList || [],
          rowRaw: data.rowRaw || null
        };
        // Full texts fetched for truncated locator columns during this edit session (non-reactive cache).
        this.$_cellFullValues = {};
        // Initialize content (show initial values)
        this.cellDetailContent = this.selectedCellDetail.cellValue || '';
        this.cellDetailJsonView = this.isJsonCell;
        // Disable loading more if error or mask is true
        const canLoadMore = !this.selectedCellDetail.error && !this.selectedCellDetail.mask;
        this.hasMoreData = canLoadMore && (this.selectedCellDetail.moreSize || 0) > 0;

        if (this.hasMoreData && !this.cellDetailLoading) {
          await this.handleLoadMoreCellData();
        }
      }
    });
    this.$bus.on('showEnterOpPwdModal', this.showEnterOpPwdModal);
    this.$bus.on('dingDingSettingModal', this.setApprovalProcessModal);
    this.$bus.on(EVENT_BUS_NAME_LIST.SHOW_INACTIVE_MODAL, (msg) => this.handleShowInactiveModal(msg));
    this.$bus.on(EVENT_BUS_NAME_LIST.CELL_UPDATE_OUTCOME, this.handleCellUpdateOutcome);
    await this.checkVersion();
  },
  unmounted() {
    this.$bus.off('setOpPasswordModal');
    this.$bus.off('showEnterOpPwdModal');
    this.$bus.off('dingDingSettingModal');
    this.$bus.off('showCellDetailModal');
    this.$bus.off(EVENT_BUS_NAME_LIST.SHOW_INACTIVE_MODAL);
    this.$bus.off(EVENT_BUS_NAME_LIST.CELL_UPDATE_OUTCOME);
  },
  methods: {
    handleShowInactiveModal(msg) {
      appLogger.debug(msg);
      this.showInactiveModal = true;
      this.inactiveMsg = msg;
    },
    handleDownload() {
      window.open('https://www.clougence.com/clouddm-personal', 'blank');
      this.handleCloseVersionDetailModal();
    },
    async handleCloseVersionDetailModal() {
      this.showVersionDetailModal = false;
      if (this.version.ignore) {
        this.version.ignore = false;
        await this.$services.dmVersionIgnore();
        await this.checkVersion();
      }
    },
    async checkVersion(showDetailModal = false) {
      const res = await this.$services.dmVersionCheck();
      if (res.success) {
        const { newVersion, prompt } = res.data;
        this.version = {
          newVersion,
          prompt
        };
        if (prompt || showDetailModal) {
          const res2 = await this.$services.dmVersionDetail();
          if (res2.success && res2.data) {
            this.version.detail = res2.data.detail;
            this.version.lastVersion = res2.data.lastVersion;
            this.showVersionDetailModal = true;
          }
        }
      }
    },
    handleDetailCopy() {
      if (XEClipboard.copy(this.cellDetailContent)) {
        this.$Message.success(this.$t('fu-zhi-cheng-gong'));
      }
    },
    handleCloseCellDetailModal() {
      this.showDetailModal = false;
      // Reset Status
      this.cellDetailContent = '';
      this.hasMoreData = false;
      this.cellDetailLoading = false;
      if (this.cellUpdateTimeout) {
        clearTimeout(this.cellUpdateTimeout);
        this.cellUpdateTimeout = null;
      }
      this.$_cellEditStash = null;
      this.resetCellEditState();
      this.selectedCellDetail = {
        column: {},
        row: {},
        resultId: '',
        rowNumber: -1,
        colNumber: -1,
        cellValue: '',
        moreSize: 0,
        totalSize: 0,
        complete: true,
        error: false,
        mask: false,
        dsType: '',
        dataSourceType: '',
        connected: false,
        activeTabType: '',
        columnList: [],
        rowRaw: null
      };
    },
    async handleLoadMoreCellData(isInitial = false) {
      if (this.cellDetailLoading) {
        return;
      }

      // More loads are not allowed if error or mask is true
      if (this.selectedCellDetail.error || this.selectedCellDetail.mask) {
        return;
      }

      const selectedDetail = this.selectedCellDetail;
      const { resultId, rowNumber, colNumber } = selectedDetail;

      if (!resultId || rowNumber < 0 || colNumber < 0) {
        appLogger.error('缺少必要参数:', { resultId, rowNumber, colNumber });
        return;
      }

      // Calculating item(s)
      const offset = this.cellDetailContent?.length || 0;

      const fetchSize = 128 * 1024; // 128K character

      this.cellDetailLoading = true;

      try {
        const res = await this.$services.dmQueryFetchResultData({
          data: {
            resultId,
            rowNumber,
            colNumber,
            offset,
            fetchSize
          }
        });

        if (!this.showDetailModal || selectedDetail !== this.selectedCellDetail) {
          return;
        }
        if (res.success && res.data) {
          // The data structure returned from the interface is {value: {complete, mark, error, moreSize, totalSize, value}
          const dataValue = res.data.value || res.data;
          const { value, moreSize, totalSize, complete, error } = dataValue;

          if (error) {
            this.$Message.error(this.$t('jia-zai-shu-ju-shi-bai'));
            return;
          }

          // Add content (because initial values have been shown, only new data will be required here)
          this.cellDetailContent += value || '';

          // Update moreSize and totalSize
          this.selectedCellDetail.complete = complete;
          this.selectedCellDetail.moreSize = moreSize || 0;
          this.selectedCellDetail.totalSize = totalSize || 0;
          // Disable loading more if error or mask is true
          const canLoadMore = !this.selectedCellDetail.error && !this.selectedCellDetail.mask;
          this.hasMoreData = canLoadMore && (moreSize || 0) > 0;
        } else {
          this.$Message.error(res.message || this.$t('jia-zai-shu-ju-shi-bai'));
        }
      } catch (error) {
        appLogger.error('加载单元格数据失败:', error);
        this.$Message.error(this.$t('jia-zai-shu-ju-shi-bai'));
      } finally {
        if (selectedDetail === this.selectedCellDetail) {
          this.cellDetailLoading = false;
        }
      }
    },
    resetCellEditState() {
      // Keep the edit context (table/session info) so「编辑」can be retried after a cancel or a failed save.
      this.cellEdit = {
        eligible: this.cellEdit.eligible,
        checking: false,
        active: false,
        generating: false,
        setNull: false,
        binaryAsText: false,
        sql: '',
        columnMeta: null,
        tableName: null,
        tableMeta: null,
        context: this.$_cellEditStash || null,
        conditionCols: [],
        findColumn: null,
        savedContent: ''
      };
    },
    // Quick checks that do not need a server round trip; the full validation runs when「编辑」is clicked.
    checkCellEditEligibility(data) {
      if (!data || data.error || data.mask || data.cellValue == null) {
        return false;
      }
      if (!data.connected) {
        return false;
      }
      const ctx = data.updateContext;
      if (!ctx || !Array.isArray(ctx.levels) || !ctx.levels.length || !ctx.effectiveSql || !ctx.sessionId) {
        return false;
      }
      if ((ctx.activeTabType || data.activeTabType) !== TAB_TYPE.QUERY) {
        return false;
      }
      // Oracle-family needs TO_CLOB/HEXTORAW wrappers for LOB/binary literals; ClickHouse stores binary in String columns.
      // Both are left to the dedicated data editor for now.
      const dsType = data.dataSourceType || data.dsType;
      const unsupportedDs = ['Oracle', 'ObForOracle', 'GoldenDBOracle', 'YashanDBOracle', 'KingbaseESOracle', 'ClickHouse'];
      if (!dsType || unsupportedDs.includes(dsType)) {
        return false;
      }
      return true;
    },
    isBinaryColumnType(type) {
      // Types whose grid value is hex text (RowStorage/ResultFileReader render byte[] via bytes2hex).
      // BIT/BOOLEAN/BOOL are excluded: they are displayed as True/False numbers, not hex.
      const t = String(type || '').toUpperCase();
      if (t === 'BIT' || t === 'BOOLEAN' || t === 'BOOL' || t.startsWith('BIT(') || /^BIT \d/.test(t)) {
        return false;
      }
      return /BLOB|BINARY|VARBINARY|BYTEA|IMAGE|ROWVERSION|HIERARCHYID|\bRAW\b/.test(t);
    },
    isNumericLiteral(value) {
      return value != null && value !== '' && /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(String(value));
    },
    // Normalize the displayed hex text (accepts 0x prefix, spaces, newlines) to bare hex; null when not valid.
    normalizeHex(value) {
      let s = String(value == null ? '' : value).trim();
      if (/^0[xX]/.test(s)) {
        s = s.slice(2);
      }
      s = s.replace(/\s+/g, '');
      if (s.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(s)) {
        return null;
      }
      return s.toUpperCase();
    },
    // Binary literal per dialect family; values come from the grid as hex text (RowStorage renders byte[] via bytes2hex).
    binaryLiteral(hex, dsType) {
      const ds = String(dsType || '');
      // Pg-family bytea (GSN base64 input is not enabled by default, hex format is accepted).
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
    },
    // Encode UTF-8 text to an uppercase hex string, used when a binary cell is edited as text.
    utf8TextToHex(text) {
      const bytes = new TextEncoder().encode(text == null ? '' : String(text));
      let hex = '';
      for (const b of bytes) {
        hex += b.toString(16).padStart(2, '0');
      }
      return hex.toUpperCase();
    },
    // Best-effort decode of a hex string back to UTF-8 text (invalid sequences become replacement chars).
    hexToUtf8Text(hex) {
      const h = this.normalizeHex(hex);
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
    },
    // Switch a binary cell between HEX and text input, converting the current content on the fly.
    handleBinaryModeToggle(asText) {
      this.cellEdit.binaryAsText = asText;
      if (asText) {
        this.cellDetailContent = this.hexToUtf8Text(this.cellDetailContent);
      } else {
        this.cellDetailContent = this.utf8TextToHex(this.cellDetailContent);
      }
    },
    sqlQualifier(dsType) {
      // MySQL-family uses backticks; the rest use double quotes, matching getQualifier's default rule.
      const mysqlFamily = ['MySQL', 'PolarDB', 'TiDB', 'OceanBase', 'OBForMySQL', 'ObForMySQL', 'MariaDB', 'StarRocks', 'Doris', 'ADB'];
      return mysqlFamily.includes(dsType) ? { left: '`', right: '`' } : { left: '"', right: '"' };
    },
    sqlLiteral(value, colType, dsType) {
      if (value === null) {
        return 'null';
      }
      if (this.isBinaryColumnType(colType)) {
        const hex = this.normalizeHex(value);
        if (hex === null) {
          throw new Error(this.$t('he-xin-zi-fu-fei-fa-de-er-jin-zhi-wen-ben'));
        }
        return this.binaryLiteral(hex, dsType);
      }
      if (this.isNumericLiteral(value)) {
        return String(value);
      }
      // NO_BACKSLASH_ESCAPES is assumed for MySQL-family; the preview lets the user review the statement.
      const q = dsType === 'SQLServer' ? value.replace(/'/g, "''") : value.replace(/\\/g, '\\\\').replace(/'/g, "''");
      return `'${q}'`;
    },
    async fetchFullCellValue(colNumber, rowNumber) {
      let content = '';
      let offset = 0;
      let moreSize = Infinity;
      const fetchSize = 128 * 1024;
      let guard = 0;

      while (moreSize > 0) {
        if (++guard > 512) {
          throw new Error(this.$t('dan-yuan-ge-nei-rong-guo-da'));
        }
        const res = await this.$services.dmQueryFetchResultData({
          data: { resultId: this.selectedCellDetail.resultId, rowNumber, colNumber, offset, fetchSize }
        });
        if (!res.success || !res.data) {
          throw new Error(res.message || this.$t('jia-zai-shu-ju-shi-bai'));
        }
        const dataValue = res.data.value || res.data;
        if (dataValue.error) {
          throw new Error(this.$t('jia-zai-shu-ju-shi-bai'));
        }
        const chunk = dataValue.value || '';
        if (!chunk && (dataValue.moreSize || 0) > 0) {
          throw new Error(this.$t('jia-zai-shu-ju-shi-bai'));
        }
        content += chunk;
        // Offsets count characters for text values and bytes for binary values.
        offset += fetchSize;
        moreSize = dataValue.moreSize || 0;
      }
      return content;
    },
    async handleStartCellEdit() {
      const ctx = this.cellEdit.context;
      const detail = this.selectedCellDetail;
      try {
        if (!this.showDetailModal || !ctx) {
          return;
        }
        // Matches a (possibly qualified) table identifier quoted with backticks, double quotes or brackets, or bare.
        // The terminator is a lookahead (whitespace, ';', ')' or end-of-string) so「from t;」and「from t」still resolve the table.
        const identifier = '(?:`[^`]+`|"[^"]+"|\\[[^\\]]+\\]|[\\w$]+)';
        const tableMatch = ctx.effectiveSql.match(new RegExp(`\\bFROM\\s+(${identifier}(\\.${identifier})?)(?=[\\s);]|$)`, 'i'));
        if (!tableMatch || /\bJOIN\b|\bUNION\b|\bGROUP\s+BY\b|\bDISTINCT\b|\(\s*SELECT\b/i.test(ctx.effectiveSql)) {
          this.$Message.warning(this.$t('dang-qian-cha-xun-jie-guo-bu-zhi-chi-bian-ji'));
          return;
        }
        const clean = (t) => (t || '').replace(/[`[\]"]+/g, '');
        const parts = tableMatch[1].split('.').map(clean).filter(Boolean);
        // The table is resolved from the query itself; the tab node only carries the schema path (its title is not the table).
        const tableNameFromSql = parts[parts.length - 1];
        if (!tableNameFromSql) {
          this.$Message.warning(this.$t('dang-qian-cha-xun-jie-guo-bu-zhi-chi-bian-ji'));
          return;
        }

        this.cellEdit.checking = true;
        const res = await this.$services.dmBrowseRdbObjectDetail({
          data: { levels: ctx.levels, targetName: tableNameFromSql, targetType: 'TABLE' }
        });
        if (!res.success || !res.data || !Array.isArray(res.data.group)) {
          this.$Message.error(res.message || this.$t('huo-qu-biao-jie-gou-shi-bai'));
          return;
        }
        const columnGroup = res.data.group.find((g) => g.type === 'RDB_COLUMN_GROUP');
        const primaryGroup = res.data.group.find((g) => g.type === 'RDB_PRIMARY_GROUP');
        if (!columnGroup || !columnGroup.items || !columnGroup.items.length) {
          this.$Message.error(this.$t('huo-qu-biao-jie-gou-shi-bai'));
          return;
        }
        const columns = columnGroup.items;
        const findColumn = (name) => columns.find((c) => String(c.name).toLowerCase() === String(name).toLowerCase()) || null;

        const targetColName = detail.column.property;
        const columnMeta = findColumn(targetColName);
        if (!columnMeta) {
          this.$Message.warning(this.$t('dang-qian-cha-xun-jie-guo-bu-zhi-chi-bian-ji'));
          return;
        }
        // Every result column must be traceable to the target table, otherwise the statement may hit another table.
        const resultColumns = detail.columnList || [];
        if (!resultColumns.length || resultColumns.some((name) => !findColumn(name))) {
          this.$Message.warning(this.$t('dang-qian-cha-xun-jie-guo-bu-zhi-chi-bian-ji'));
          return;
        }

        // Row locator must be a genuine PK/unique key. Without one the generated UPDATE could match (and overwrite) sibling rows.
        let conditionCols = columns.filter((c) => c.attrs && c.attrs.isKey).map((c) => c.name);
        if (!conditionCols.length) {
          conditionCols = columns.filter((c) => c.attrs && c.attrs.isUnique).map((c) => c.name);
        }
        const missingLocator = conditionCols.filter((name) => !resultColumns.some((c) => String(c).toLowerCase() === String(name).toLowerCase()));
        if (!conditionCols.length || missingLocator.length) {
          this.$Message.warning(this.$t('que-shao-zhu-jian-huo-wei-yi-jian-wu-fa-an-quan-ding-wei'));
          return;
        }
        const locateCols = conditionCols.filter((name) => name !== detail.column.property);
        const targetCellTruncated = (detail.moreSize || 0) > 0 || !detail.complete;
        const truncatedLocators = locateCols.filter((name) => !this.isCompleteInGrid(name));
        if (targetCellTruncated || truncatedLocators.length) {
          this.cellEdit.checking = false;
          this.cellDetailLoading = true;
          try {
            // Fetch the full value of the edited cell and of every truncated locator column so the WHERE clause is exact.
            if (targetCellTruncated) {
              this.cellDetailContent = await this.fetchFullCellValue(detail.colNumber, detail.rowNumber);
            }
            for (const name of truncatedLocators) {
              const colNumber = (detail.columnList || []).indexOf(name);
              if (colNumber >= 0) {
                const full = await this.fetchFullCellValue(colNumber, detail.rowNumber);
                this.$_cellFullValues[name] = full;
              }
            }
          } catch (err) {
            this.$Message.error(err.message || this.$t('jia-zai-shu-ju-shi-bai'));
            return;
          } finally {
            this.cellDetailLoading = false;
            this.cellEdit.checking = true;
          }
          this.hasMoreData = false;
        }

        this.cellEdit.columnMeta = columnMeta;
        this.cellEdit.tableName = tableNameFromSql;
        this.cellEdit.tableMeta = { columns, hasPk: !!(primaryGroup && primaryGroup.items && primaryGroup.items.length) };
        this.cellEdit.conditionCols = conditionCols;
        this.cellEdit.findColumn = findColumn;
        this.cellEdit.savedContent = this.cellDetailContent;
        this.cellEdit.active = true;
      } catch (err) {
        appLogger.error('cell edit preparation failed', err);
        this.$Message.error(err.message || this.$t('dang-qian-cha-xun-jie-guo-bu-zhi-chi-bian-ji'));
      } finally {
        this.cellEdit.checking = false;
      }
    },
    // Whether the grid sample of a condition column is complete (not truncated), based on its raw cell meta.
    isCompleteInGrid(colName) {
      const detail = this.selectedCellDetail;
      if (colName === detail.column.property) {
        // The edited cell itself is fully loaded into cellDetailContent before editing starts.
        return true;
      }
      const idx = (detail.columnList || []).indexOf(colName);
      if (idx < 0) {
        return false;
      }
      const cell = Array.isArray(detail.rowRaw) ? detail.rowRaw[idx] : null;
      // Missing raw meta (unknown receive mode) is treated as complete; WHERE fallbacks still use IS NULL-safe literals.
      if (!cell || typeof cell !== 'object') {
        return true;
      }
      return cell.complete !== undefined ? cell.complete : true;
    },
    // Original (pre-edit) displayed value of a row column, as kept when the modal was opened.
    cellDetailValue(colName) {
      // Truncated locator columns are fully fetched (characters) before editing starts.
      if (this.$_cellFullValues && colName in this.$_cellFullValues) {
        return this.$_cellFullValues[colName];
      }
      const detail = this.selectedCellDetail;
      const idx = (detail.columnList || []).indexOf(colName);
      const cell = idx >= 0 && Array.isArray(detail.rowRaw) ? detail.rowRaw[idx] : undefined;
      if (cell !== undefined && cell !== null && typeof cell === 'object' && 'value' in cell) {
        return cell.value == null ? null : cell.value;
      }
      const rowValue = (detail.row || {})[colName];
      return rowValue === undefined ? null : rowValue;
    },
    handleCancelCellEdit() {
      this.cellDetailContent = this.cellEdit.savedContent;
      this.resetCellEditState();
    },
    // Build the UPDATE statement: single-column SET plus a PK/unique row locator WHERE.
    buildCellUpdateSql() {
      const detail = this.selectedCellDetail;
      const ctx = this.cellEdit.context;
      const dsType = detail.dataSourceType || detail.dsType;
      if (!ctx || !this.cellEdit.tableName) {
        throw new Error(this.$t('dang-qian-cha-xun-jie-guo-bu-zhi-chi-bian-ji'));
      }
      const qualifier = this.sqlQualifier(dsType);
      const quoteName = (name) =>
        `${qualifier.left}${String(name)
          .split('.')
          .map((p) => p.replace(new RegExp(qualifier.left, 'g'), ''))
          .join(`${qualifier.right}.${qualifier.left}`)}${qualifier.right}`;
      const targetCol = detail.column.property;
      const locateCols = (this.cellEdit.conditionCols || []).filter((c) => c !== targetCol);
      if (!locateCols.length) {
        throw new Error(this.$t('que-shao-zhu-jian-huo-wei-yi-jian-wu-fa-an-quan-ding-wei'));
      }
      for (const col of locateCols) {
        if (!this.isCompleteInGrid(col) && !(this.$_cellFullValues && col in this.$_cellFullValues)) {
          throw new Error(this.$t('dan-yuan-ge-nei-rong-guo-da'));
        }
      }
      const targetMeta = this.cellEdit.columnMeta;
      let valueExpr;
      if (this.cellEdit.setNull) {
        valueExpr = 'null';
      } else if (targetMeta && this.isBinaryColumnType(targetMeta.dbType) && this.cellEdit.binaryAsText) {
        // Text input mode: store the UTF-8 bytes of the typed text as the binary value.
        valueExpr = this.binaryLiteral(this.utf8TextToHex(this.cellDetailContent), dsType);
      } else {
        valueExpr = this.sqlLiteral(this.cellDetailContent, targetMeta ? targetMeta.dbType : undefined, dsType);
      }
      const where = locateCols
        .map((col) => {
          const meta = this.cellEdit.findColumn ? this.cellEdit.findColumn(col) : null;
          const raw = this.cellDetailValue(col);
          // Empty displayed text is ambiguous (NULL vs ''); locate as IS NULL since `col = null` never matches.
          if (raw === null || raw === '') {
            return `${quoteName(col)} is null`;
          }
          return `${quoteName(col)}=${this.sqlLiteral(raw, meta ? meta.dbType : undefined, dsType)}`;
        })
        .join(' and ');
      return `update ${quoteName(this.cellEdit.tableName)} set ${quoteName(targetCol)}=${valueExpr} where ${where}`;
    },
    // Save: generate the UPDATE, preview it for confirmation, then execute through the SQL window WebSocket channel.
    handleGenerateUpdateSql() {
      if (this.cellEdit.generating) {
        return;
      }
      let sql = '';
      try {
        sql = this.buildCellUpdateSql();
      } catch (err) {
        this.$Message.error(err.message || this.$t('dang-qian-cha-xun-jie-guo-bu-zhi-chi-bian-ji'));
        return;
      }
      this.cellEdit.sql = sql;
      this.$Modal.confirm({
        title: this.$t('ti-shi'),
        content: this.$t('que-ren-zhi-xing-geng-xin-tishi', [sql]),
        okText: this.$t('que-ding'),
        cancelText: this.$t('qu-xiao'),
        onOk: () => {
          const ctx = this.cellEdit.context;
          if (!ctx || !ctx.sessionId) {
            this.$Message.warning(this.$t('lian-jie-yi-duan-kai'));
            return;
          }
          this.cellEdit.generating = true;
          if (this.cellUpdateTimeout) {
            clearTimeout(this.cellUpdateTimeout);
          }
          this.cellUpdateTimeout = setTimeout(() => {
            if (this.cellEdit.generating) {
              this.cellEdit.generating = false;
              this.$Message.error(this.$t('zhi-xing-chao-shi'));
            }
          }, 60000);
          this.$bus.emit(EVENT_BUS_NAME_LIST.EXECUTE_CELL_UPDATE, { sql, sessionId: ctx.sessionId });
        }
      });
    },
    // Outcome of the UPDATE executed by the SQL window over WebSocket.
    handleCellUpdateOutcome({ sessionId, success, error } = {}) {
      if (!this.cellEdit.generating) {
        return;
      }
      const ctx = this.cellEdit.context;
      if (!ctx || (sessionId && ctx.sessionId && sessionId !== ctx.sessionId)) {
        return;
      }
      this.cellEdit.generating = false;
      if (this.cellUpdateTimeout) {
        clearTimeout(this.cellUpdateTimeout);
        this.cellUpdateTimeout = null;
      }
      if (success) {
        this.$Message.success(this.$t('geng-xin-cheng-gong'));
        this.resetCellEditState();
      } else {
        this.$Message.error(error || this.$t('zhi-xing-shi-bai'));
      }
    },
    handleGoBackHome() {
      if (this.$route.path !== this.defaultRedirectUrl) {
        this.$router.push({ path: this.defaultRedirectUrl });
      }
    },
    handleGoAppHome() {
      if (this.isSqlRoute) {
        const fallback = resolveWorkbenchFallbackPath(this.mySystemMenuItems);
        const target = resolveWorkbenchRoute(fallback, this.userInfo?.uid, this.mySystemMenuItems);
        this.$router.push(target).catch(() => {});
        return;
      }
      this.handleGoBackHome();
    },
    _setApprovalProcessModal() {
      this.$store.dispatch('getUserInfo');
      this.setApprovalProcessModal();
    },
    _setOpPasswordModal() {
      this.$store.dispatch('getUserInfo');
      this.setOpPasswordModal(true);
    },
    goUserConfig() {
      this.$router.push({ name: 'User_Config' });
    },

    handleCloseModal() {
      this.showInactiveModal = false;
    }
  }
};
</script>

<style lang="less" scoped>
.home {
  height: 100%;
  display: flex;
  flex-direction: column;

  .user-expr-tip {
    width: 100%;
    height: 16px;
    line-height: 16px;
    background: rgba(239, 68, 68, 0.15);
    position: fixed;
    top: 0;
    left: 0;
    z-index: 1001;
    text-align: center;
    font-size: 12px;
    color: var(--error-color);
  }

  &--sql .user-expr-tip {
    top: 58px;
  }

  .home-entry-loading {
    flex: 1;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    color: var(--text-secondary);
    background: #f5f7fa;
    font-size: 14px;
  }

  .footer {
    //height: 24px;
    width: 100%;
    border-top: 1px solid #ccc;
    display: flex;
    justify-content: flex-end;
    align-items: center;

    .right {
      display: flex;

      .btn {
        font-size: 12px;
      }
    }

    .async-list {
      position: absolute;
      width: 400px;
      max-height: 400px;
      overflow: auto;
      bottom: 24px;
      border: 1px solid #ccc;
      border-bottom: none;
      z-index: 999;
      background: #fff;
      box-shadow: rgba(100, 100, 111, 0.2) 0px 7px 29px 0px;

      .async-list-header {
        height: 24px;
        border-bottom: 1px solid #ccc;
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0 10px;
      }

      .list {
        padding: 5px;

        .task {
          padding: 3px;
          border: 1px solid #ccc;
          border-radius: 5px;
          padding: 5px 10px;
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 5px;
          width: 100%;

          .content {
            flex: 1;
            min-width: 0;
            margin-right: 40px;

            .title {
              overflow: hidden;
              white-space: nowrap;
              text-overflow: ellipsis;
              width: 100%;
            }
          }

          .action {
            .async-task-btn + .async-task-btn {
              margin-left: 5px;
            }
          }
        }
      }
    }
  }

  .content-container {
    // padding-top defined in app-shell.less
    height: 100%;
    overflow-y: auto;
  }
}

.ivu-dropdown .ivu-select-dropdown {
  top: 80px !important;
}

.message-list-container {
  width: 360px;
  background: white;
  box-shadow: 1px 1px 6px rgba(164, 164, 164, 0.66);

  .title {
    background: #ececec;
    line-height: 50px;
    font-size: 14px;
    font-family: PingFangSC-Semibold;
    font-weight: 500;
    padding: 0 20px;
  }

  .time {
    color: #888888;
  }

  .message-item {
    padding: 20px;
    border-bottom: 1px solid #dfdfdf;
    line-height: 20px;
    cursor: pointer;

    &:hover {
      background: #f5f5f5;
    }
  }

  .message-footer {
    padding: 0 20px;
    line-height: 38px;
    cursor: pointer;

    &:hover {
      background: #f5f5f5;
    }
  }
}

.cell-detail {
  position: relative;

  .cell-decode-hint {
    margin-bottom: 6px;
    font-size: 12px;
    color: var(--text-secondary, #999);
  }

  .cell-detail-loading {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(255, 255, 255, 0.9);
    padding: 10px 20px;
    border-radius: 4px;
    z-index: 10;
  }

  white-space: pre-line;
  width: 100%;
  margin-top: 10px;
  max-height: 400px;
  overflow: auto;

  .cell-edit-toolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;

    .cell-edit-hint {
      font-size: 12px;
      color: var(--text-secondary, #999);
    }
  }

  .cell-edit-sql-preview {
    margin-top: 10px;
    padding: 8px 10px;
    border: 1px solid var(--border-color, #e5e6eb);
    border-radius: 4px;
    background: #f7f8fa;

    .cell-edit-sql-label {
      font-size: 12px;
      color: var(--text-secondary, #999);
      margin-bottom: 4px;
    }

    pre {
      margin: 0;
      white-space: pre-wrap;
      word-break: break-all;
      font-family: monospace;
      font-size: 12px;
    }
  }
}

.renew-license-modal {
  padding: 14px 14px 30px 14px;

  :deep(textarea) {
    height: 80px;
    width: 100%;
  }

  .operation {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .section {
    .title {
      font-weight: 400;
      font-size: 16px;
      line-height: 24px;
      color: rgba(0, 0, 0, 0.9);
      margin-bottom: 8px;
    }

    .sub-title {
      color: rgba(0, 0, 0, 0.6);
      margin-bottom: 8px;
      display: flex;
      justify-content: space-between;
      align-items: center;

      div {
        display: flex;
        justify-content: space-between;
        align-items: center;
        cursor: pointer;

        img {
          margin-right: 4px;
        }
      }
    }

    .apply-code {
      width: 100%;
      border: 1px solid #dfdfdf;
      word-wrap: break-word;
      padding: 10px;
      background-color: transparent;
      color: inherit;
    }

    .copy-btn {
      color: #0052d9;
      cursor: pointer;
    }

    .contact-link {
      color: #075ddf;
      cursor: pointer;
    }
  }
}

.apply-license-modal {
  width: 100%;
  padding: 14px;

  .body {
    display: flex;

    :deep(textarea) {
      height: 80px;
    }

    .steps {
      margin-right: 16px;

      .circle {
        width: 25px;
        height: 25px;
        border-radius: 50%;
        border: 1px solid #0052d9;
        font-size: 16px;
        text-align: center;
        line-height: 25px;
        color: #0052d9;
      }

      .line1,
      .line2 {
        width: 1px;
        background: #0052d9;
        margin: 12px;
      }

      .line1 {
        height: 28px;
      }

      .line2 {
        height: 100px;
      }
    }

    .copy-btn {
      color: #0052d9;
      cursor: pointer;
    }

    .contact-link {
      color: #075ddf;
      cursor: pointer;
    }

    .content {
      .tip {
        color: #666666;
        margin-top: 24px;
        width: 825px;
        padding: 15px;
        background: #fafafa;
        line-height: 18px;
        margin-bottom: 30px;

        .link {
          color: #075ddf;
          margin-top: 10px;

          a {
            color: #075ddf;
          }
        }
      }

      .section {
        .title {
          font-weight: 400;
          font-size: 16px;
          line-height: 24px;
          color: rgba(0, 0, 0, 0.9);
          margin-bottom: 8px;
        }

        .sub-title {
          color: rgba(0, 0, 0, 0.6);
          margin-bottom: 8px;
          display: flex;
          justify-content: space-between;
          align-items: center;

          div {
            display: flex;
            justify-content: space-between;
            align-items: center;
            cursor: pointer;

            img {
              margin-right: 4px;
            }
          }
        }

        .apply-code {
          width: 825px;
          border: 1px solid #dfdfdf;
          word-wrap: break-word;
          padding: 10px;
          background-color: transparent;
          color: inherit;
        }

        .copy-btn {
          color: #0052d9;
          cursor: pointer;
        }

        .contact-link {
          color: #075ddf;
          cursor: pointer;
        }
      }
    }
  }
}

.cluster-select {
  width: max-content;
}

.navbar-nav-nb,
.navbar-nav-nb-dropdown {
  a {
    font-size: 14px;
  }

  .iconfont {
    font-size: 20px;
  }
}

.navbar-nav-nb,
.navbar-nav-nb-dropdown {
  a {
    color: #ffffff;
    opacity: 0.85;

    &:hover {
      opacity: 1;
      color: #ffffff;
    }
  }

  float: right;
  position: relative;

  display: block;
  height: 48px;
  line-height: 48px;
  color: #ffffff;
  font-size: 12px;
  text-decoration: none;
  //margin-left: 10px;

  &:hover {
    /*background-color: #f8f8f9;*/
    cursor: pointer;
  }

  .menu-dropdown {
    border: 1px solid rgba(0, 0, 0, 0.1);
    border-radius: 2px;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.1);
    position: absolute;
    width: 200px;
    background: #fff;
    right: 0;

    .info {
      border-bottom: 1px solid #dee5e7;
      color: #58666e;
      background-color: #edf1f2;
      padding: 15px;
      margin-bottom: 10px;
    }

    .divider {
      height: 1px;
      margin: 9px 0;
      overflow: hidden;
      background-color: #e5e5e5;
    }

    a {
      height: 30px;
      line-height: 30px;
      display: block;
      padding: 0 10px;
      color: #58666e;

      &:hover {
        background-color: #edf1f2;
      }
    }
  }
}

.navbar-nav-nb-dropdown {
  height: 30px;
  line-height: 30px;
  color: rgba(0, 0, 0, 0.88);

  a {
    color: rgba(0, 0, 0, 0.88);

    &:hover {
      opacity: 1;
      color: rgba(0, 0, 0, 0.88);
    }
  }
}

.deemph-text {
  span,
  a,
  strong {
    opacity: 1;
  }
}

.deemph-button {
  opacity: 1;
}

.layout-logo {
  width: 180px;
  height: 48px;
  display: flex;
  align-items: center;
  background-image: url('~@/assets/logo-BG.png');
  float: left;
  position: relative;
  color: #ffffff;
  padding-left: 20px;
  cursor: pointer;

  .iconfont {
    font-size: 22px;
  }
}

.uid-wrap {
  position: relative;
  top: -20px;

  span {
    font-size: 11px;
  }
}

// Layout switch transition: masks the height difference between
// sql-layout (compact header) and app-layout (card-based with padding)
.layout-switch-enter-active,
.layout-switch-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.layout-switch-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.layout-switch-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
