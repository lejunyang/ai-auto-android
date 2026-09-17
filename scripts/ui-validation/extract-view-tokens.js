/**
 * 功能用途：从 UI 原型逐视图抽取机器可读的设计 token。
 *
 * 用法：由 collect-prototype-views.py 通过 shot.py --eval-file 注入执行。
 * 注入前脚本会先设置 location.hash 切到目标视图并等待渲染，
 * 然后调用本文件导出的采集逻辑，返回值经 JSON 序列化写入 shots.<view>.eval.result。
 *
 * 采集维度（与 Android 端 AppTokens 对齐所需）：
 * - 颜色：background / color / borderColor（统一转 sRGB hex）
 * - 字体：fontSize / fontWeight / lineHeight / letterSpacing
 * - 圆角：borderRadius
 * - 盒模型：width / height / padding / margin / borderWidth
 * - 点击区：可交互元素的实际尺寸（校验 >=44dp）
 *
 * 坐标口径：在 411x914 视口下 CSS px == Android dp（1080x2400 / 420dpi = 2.625x）。
 */

// 把 rgb()/rgba() 统一转为 #RRGGBB 大写 hex；透明返回 null。
function toHex(value) {
  if (!value || value === 'none' || value === 'transparent') return null;
  const m = value.match(/rgba?\(([^)]+)\)/);
  if (!m) return value.toUpperCase();
  const parts = m[1].split(',').map((s) => parseFloat(s.trim()));
  if (parts.length >= 4 && parts[3] === 0) return null;
  const hex = parts
    .slice(0, 3)
    .map((n) => Math.round(n).toString(16).padStart(2, '0'))
    .join('');
  return ('#' + hex).toUpperCase();
}

// 去掉 px 后缀转数值，保留一位小数；非数值返回原串。
function px(value) {
  if (value == null) return null;
  const n = parseFloat(value);
  return Number.isNaN(n) ? value : Math.round(n * 10) / 10;
}

// 采集单个元素的完整 token。
function measure(el, label) {
  if (!el) return null;
  const cs = getComputedStyle(el);
  const rect = el.getBoundingClientRect();
  return {
    label,
    tag: el.tagName.toLowerCase(),
    className: el.className || null,
    text: (el.textContent || '').trim().slice(0, 40) || null,
    box: {
      width: Math.round(rect.width * 10) / 10,
      height: Math.round(rect.height * 10) / 10,
      x: Math.round(rect.x * 10) / 10,
      y: Math.round(rect.y * 10) / 10,
    },
    color: {
      text: toHex(cs.color),
      background: toHex(cs.backgroundColor),
      border: toHex(cs.borderColor),
    },
    font: {
      size: px(cs.fontSize),
      weight: parseInt(cs.fontWeight, 10) || cs.fontWeight,
      lineHeight: px(cs.lineHeight),
      letterSpacing: px(cs.letterSpacing),
    },
    radius: {
      topLeft: px(cs.borderTopLeftRadius),
      topRight: px(cs.borderTopRightRadius),
      bottomRight: px(cs.borderBottomRightRadius),
      bottomLeft: px(cs.borderBottomLeftRadius),
    },
    spacing: {
      paddingTop: px(cs.paddingTop),
      paddingRight: px(cs.paddingRight),
      paddingBottom: px(cs.paddingBottom),
      paddingLeft: px(cs.paddingLeft),
      marginTop: px(cs.marginTop),
      marginRight: px(cs.marginRight),
      marginBottom: px(cs.marginBottom),
      marginLeft: px(cs.marginLeft),
    },
    borderWidth: px(cs.borderTopWidth),
  };
}

// 采集全局 CSS 变量（原型的调色板与圆角基准）。
function collectRootVars() {
  const cs = getComputedStyle(document.documentElement);
  const names = [
    '--bg', '--surface', '--surface-2', '--ink', '--muted', '--line',
    '--blue', '--blue-soft', '--green', '--amber', '--red',
    '--radius', '--radius-sm', '--radius-lg',
  ];
  const out = {};
  names.forEach((n) => {
    const v = cs.getPropertyValue(n).trim();
    if (v) out[n] = v.startsWith('#') || v.startsWith('rgb') ? toHex(v) || v : px(v);
  });
  return out;
}

// 采集页面上所有可交互元素的点击区，用于校验 >=44dp。
function collectTouchTargets() {
  const selector = 'button, a, input, textarea, select, [role=switch], [role=button]';
  const nodes = Array.from(document.querySelectorAll(selector));
  const targets = nodes
    .map((el) => {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) return null;
      return {
        text: (el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 24),
        className: el.className || null,
        width: Math.round(r.width * 10) / 10,
        height: Math.round(r.height * 10) / 10,
      };
    })
    .filter(Boolean);
  const tooSmall = targets.filter((t) => t.height < 44);
  return { total: targets.length, minHeight: Math.min(...targets.map((t) => t.height)), tooSmall };
}

/**
 * 采集当前视图的全部 token。
 * 选择器清单按原型 CSS 类名组织，缺失的元素返回 null（表示该视图没有此元素）。
 */
function collectCurrentView() {
  const sel = (s) => document.querySelector(s);
  const all = (s) => Array.from(document.querySelectorAll(s));

  return {
    view: (location.hash || '#home').slice(1),
    viewport: { width: window.innerWidth, height: window.innerHeight },
    rootVars: collectRootVars(),
    touchTargets: collectTouchTargets(),
    elements: {
      // 页头与全局容器
      appHeader: measure(sel('.app-header'), 'appHeader'),
      headerTitle: measure(sel('.app-header b, .app-header h1'), 'headerTitle'),
      headerSubtitle: measure(sel('.app-header small, .app-header .small'), 'headerSubtitle'),
      screen: measure(sel('#screen'), 'screen'),
      bottomNav: measure(sel('.tabbar, .bottom-nav'), 'bottomNav'),
      navItemActive: measure(sel('.tabbar .active, .bottom-nav .active'), 'navItemActive'),
      navItemInactive: measure(sel('.tabbar button:not(.active), .bottom-nav button:not(.active)'), 'navItemInactive'),

      // 首页
      greeting: measure(sel('.greeting'), 'greeting'),
      greetingHello: measure(sel('.hello'), 'greetingHello'),
      greetingTitle: measure(sel('.greeting h2'), 'greetingTitle'),
      tinyOrbit: measure(sel('.tiny-orbit'), 'tinyOrbit'),
      composer: measure(sel('.composer'), 'composer'),
      composerInput: measure(sel('.composer textarea'), 'composerInput'),
      composerFoot: measure(sel('.composer-foot'), 'composerFoot'),
      composerPill: measure(sel('.composer .pill'), 'composerPill'),
      composerIconBtn: measure(sel('.composer .icon-btn'), 'composerIconBtn'),
      composerSendBtn: measure(sel('.composer .icon-btn.send'), 'composerSendBtn'),
      quickChips: measure(sel('.quick-chips'), 'quickChips'),
      quickChip: measure(sel('.quick-chips button'), 'quickChip'),
      sectionHead: measure(sel('.section-head'), 'sectionHead'),
      sectionTitle: measure(sel('.section-head h3'), 'sectionTitle'),
      textBtn: measure(sel('.text-btn'), 'textBtn'),
      skillGrid: measure(sel('.skill-grid'), 'skillGrid'),
      skillTile: measure(sel('.skill-tile'), 'skillTile'),
      skillTileIcon: measure(sel('.skill-tile .tile-icon'), 'skillTileIcon'),
      skillTileIconMint: measure(sel('.skill-tile .tile-icon.mint'), 'skillTileIconMint'),
      skillTileTitle: measure(sel('.skill-tile b'), 'skillTileTitle'),
      skillTileMeta: measure(sel('.skill-tile small'), 'skillTileMeta'),
      teachCard: measure(sel('.teach-card'), 'teachCard'),
      recentItem: measure(sel('.recent-item'), 'recentItem'),

      // 通用卡片与标签
      card: measure(sel('.card'), 'card'),
      tag: measure(sel('.tag'), 'tag'),
      tagGreen: measure(sel('.tag.green'), 'tagGreen'),
      tagAmber: measure(sel('.tag.amber'), 'tagAmber'),
      tagBlue: measure(sel('.tag.blue'), 'tagBlue'),
      pageHeading: measure(sel('.page-heading'), 'pageHeading'),
      pageDesc: measure(sel('.page-desc'), 'pageDesc'),
      note: measure(sel('.note'), 'note'),
      notice: measure(sel('.notice'), 'notice'),
      noticeAmber: measure(sel('.notice.amber'), 'noticeAmber'),
      divider: measure(sel('.divider'), 'divider'),
      empty: measure(sel('.empty'), 'empty'),

      // 筛选与列表
      filterTabs: measure(sel('.filter-tabs'), 'filterTabs'),
      filterTabActive: measure(sel('.filter-tabs .active'), 'filterTabActive'),
      filterTabInactive: measure(sel('.filter-tabs button:not(.active)'), 'filterTabInactive'),
      skillListCard: measure(sel('.skill-list-card'), 'skillListCard'),

      // 设置页
      logo: measure(sel('.logo'), 'logo'),
      settingRow: measure(sel('.setting-row'), 'settingRow'),
      settingRowTitle: measure(sel('.setting-row b'), 'settingRowTitle'),
      settingRowSub: measure(sel('.setting-row small'), 'settingRowSub'),
      switchEl: measure(sel('.switch'), 'switch'),
      iconBtnSoft: measure(sel('.icon-btn.soft'), 'iconBtnSoft'),

      // 表单与按钮
      label: measure(sel('.label'), 'label'),
      input: measure(sel('input[type=url], input[type=text], input:not([type=checkbox])'), 'input'),
      primaryBtn: measure(sel('.primary'), 'primaryBtn'),
      primaryBtnFull: measure(sel('.primary.full'), 'primaryBtnFull'),
      secondaryBtn: measure(sel('.secondary'), 'secondaryBtn'),
      stack: measure(sel('.stack'), 'stack'),
      appSelect: measure(sel('.app-select'), 'appSelect'),

      // 会话与步骤
      stepRow: measure(sel('.step-row, .step'), 'stepRow'),
      progressBar: measure(sel('.progress, .progress-bar'), 'progressBar'),
      sessionCard: measure(sel('.session-card'), 'sessionCard'),

      // 编辑器
      editorTabs: measure(sel('.editor-tabs'), 'editorTabs'),
      stepList: measure(sel('.step-list'), 'stepList'),
    },
    // 计数信息，便于判断该视图渲染了多少列表项。
    counts: {
      skillTiles: all('.skill-tile').length,
      cards: all('.card').length,
      settingRows: all('.setting-row').length,
      filterTabs: all('.filter-tabs button').length,
      navItems: all('.tabbar button, .bottom-nav button').length,
      switches: all('.switch').length,
      stepRows: all('.step-row, .step').length,
    },
  };
}

// shot.py 会把本文件包在 async () => { ... } 中执行，因此直接 return。
return collectCurrentView();
