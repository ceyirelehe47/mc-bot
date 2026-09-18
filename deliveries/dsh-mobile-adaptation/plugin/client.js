// dsh-mobile-adaptation 浏览器端入口（ModuleLoader 包装，参照官方插件产物格式）。
// F1：窄屏（<768px）侧栏 drawer 化 —— 隐藏常驻 rail，改为汉堡按钮唤出覆盖式侧栏。
// 桌面端（>=768px）所有规则不生效（全部包裹在宽度 media query 内）。
window.__ModuleLoader__.load({
  id: 'dsh-mobile-adaptation',
  factory: (require) => {
    const module = { exports: {} };
    const exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });

    const BREAKPOINT_MAX = '767.98px';

    // 说明：不使用官方 CSS Modules 哈希类名（每次构建会变），
    // 布局锚点全部基于稳定特征：
    //   #root > div > div[style*="grid-template-columns"]  => AppFrame 三列 grid（root 下有一层 display:contents 槽位包装）
    //   其第一个子 div                                  => sidebarCol
    //   data-sidebar-collapsed                        => React 侧栏收起态
    const css = `
/* ===== dsh-mobile-adaptation F1: sidebar drawer ===== */
.dsh-ma-fab {
  position: fixed;
  top: 10px;
  left: 10px;
  z-index: 50;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: none;
  align-items: center;
  justify-content: center;
  background: var(--dsw-alias-button-elevated-fill, #fff);
  color: var(--dsw-alias-label-primary, #333);
  border: 1px solid var(--dsw-alias-border-l2, rgba(0, 0, 0, 0.1));
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.14);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  touch-action: manipulation;
  padding: 0;
}
.dsh-ma-backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.42);
  display: none;
  border: none;
  padding: 0;
}
@media (max-width: ${BREAKPOINT_MAX}) {
  /* 单列布局：覆盖 AppFrame 的内联 grid-template-columns，rail 列宽归零 */
  #root > div > div[style*="grid-template-columns"] {
    grid-template-columns: 0 minmax(0, 1fr) 0 !important;
  }
  /* 收起态：去边框 + 移出 tab 序/可访问性树（避免幽灵控件） */
  #root > div > div[style*="grid-template-columns"][data-sidebar-collapsed] > div:first-child {
    border-right: none;
    visibility: hidden;
  }
  .dsh-ma-fab {
    display: inline-flex;
  }
  /* 侧栏展开态（React narrowExpanded）：sidebarCol 脱流为覆盖式 drawer */
  #root > div > div:not([data-sidebar-collapsed]) > div:first-child {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    width: min(85vw, 320px);
    max-width: min(85vw, 320px);
    z-index: 70;
    background: var(--dsw-specific-sidebar-fill, #fff);
    box-shadow: 8px 0 24px rgba(0, 0, 0, 0.2);
    border-right: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, 0.08));
    animation: dsh-ma-slide-in 0.18s ease-out;
  }
  /* drawer 宽度归一：覆盖 SidebarRoot 内联宽度（桌面拖宽到 >320px 的遗留偏好） */
  #root > div > div:not([data-sidebar-collapsed]) > div:first-child > :first-child {
    width: 100% !important;
    max-width: 100% !important;
  }
  body:has(#root > div > div:not([data-sidebar-collapsed]) > div:first-child) {
    overflow: hidden;
  }
  body:has(#root > div > div:not([data-sidebar-collapsed]) > div:first-child) .dsh-ma-backdrop {
    display: block;
  }
  body:has(#root > div > div:not([data-sidebar-collapsed]) > div:first-child) .dsh-ma-fab {
    display: none;
  }
  @media (prefers-reduced-motion: reduce) {
    #root > div > div:not([data-sidebar-collapsed]) > div:first-child {
      animation: none;
    }
  }
}
@keyframes dsh-ma-slide-in {
  from {
    transform: translateX(-100%);
  }
}
/* ===== dsh-mobile-adaptation F2: 消息内容防溢出（<768px） ===== */
@media (max-width: ${BREAKPOINT_MAX}) {
  /* 图片/视频不撑破气泡。用 :where() 降权到 (0,1,0)：官方 .class img 类规则
     （附件缩略图/图片 gallery 的 height:100% + object-fit:cover、markdown
     内容图 ._image_1nba0_229）天然胜出，仅保护无类保护的裸 img/video。
     注意：官方当前无 iframe 输出；若未来引入需补 :where(iframe){max-width:100%}。 */
  [data-conversation-scroll] :where(img, video) {
    max-width: 100%;
    height: auto;
  }
  /* 代码块兜底：即使官方包裹层缺失也限制在滚动容器内（表格不设 max-width，
     官方 tableScroll 依赖 width:max-content + 外层横滚，压宽会导致列变形）。
     同样 :where() 降权，让官方未来对 pre 的规则调整优先生效。 */
  [data-conversation-scroll] :where(pre) {
    max-width: 100%;
    overflow-x: auto;
  }
  /* 列内直接子节点允许收缩，防止单个超宽子项撑出横向滚动 */
  [data-conversation-scroll] > * {
    min-width: 0;
  }
}
/* ===== dsh-mobile-adaptation F3: composer/触控目标优化（<768px） ===== */
@media (max-width: ${BREAKPOINT_MAX}) {
  /* 去除移动端点按灰闪（官方未处理 tap-highlight） */
  :where(button, a, [role='button'], select, input, textarea, label) {
    -webkit-tap-highlight-color: transparent;
  }
  /* 触控目标温和扩大：会话区内 28px 的按钮/下拉抬到 32px（宽高同步，
     保持圆形按钮比例；34px 发送等更大控件不受影响）。
     层叠说明：官方显式 min-* 同属性规则可胜出（注入顺序官方模块更晚、
     同权时官方胜，如 hero 区 .pXSMma_workspace 保持 28px）；
     而官方 width/height 无法对抗 min-*（不同属性，取 max），
     凡未显式设 min-* 的按钮都会被抬到 32。
     排除 JSON 树悬浮复制按钮（官方 20×16，抬到 32 视觉过大；
     CSS Modules 语义名部分跨构建稳定，用子串匹配排除）。 */
  [data-conversation-scroll] :where(button, select):not([class*='copyButton']) {
    min-height: 32px;
    min-width: 32px;
  }
}
/* ===== dsh-mobile-adaptation F4: 模态弹窗适配（<768px） ===== */
@media (max-width: ${BREAKPOINT_MAX}) {
  /* 设置弹窗（role=dialog + aria-modal + 带 nav 侧栏，官方 panel 宽 800 自适应到
     342px 时，188px 固定侧导航会把内容挤到 154px）：侧导航改为顶部横向 tab。
     :has(> nav) 限定到带侧导航的模态，不影响其他 role=dialog 弹层。 */
  [role='dialog'][aria-modal='true']:has(> nav) {
    flex-direction: column;
  }
  [role='dialog'][aria-modal='true']:has(> nav) > nav {
    width: 100%;
    flex: none;
    flex-direction: row;
    align-items: center;
    gap: 12px;
    overflow-x: auto;
    padding: 12px 12px 4px;
    border-bottom: 1px solid var(--dsw-alias-border-l2, rgba(0, 0, 0, 0.08));
    box-sizing: border-box;
  }
  /* nav 下最后一个子块是 navList（navTitle 之后），横排 */
  [role='dialog'][aria-modal='true']:has(> nav) > nav > :last-child {
    flex: 1;
    min-width: 0;
    flex-direction: row;
    gap: 4px;
  }
  [role='dialog'][aria-modal='true']:has(> nav) > nav button {
    flex: none;
    height: 32px;
    padding: 4px 10px;
    white-space: nowrap;
  }
}
`.trim();

    const TAG_ID = 'dsh-mobile-adaptation/styles.css';
    const FAB_SELECTOR = '.dsh-ma-fab';
    const BACKDROP_SELECTOR = '.dsh-ma-backdrop';
    const FRAME_SELECTOR = '#root > div > div[style*="grid-template-columns"]';

    function ensureStyle() {
      if (typeof document === 'undefined') return;
      if (document.querySelector(`style[data-plugin-css=${JSON.stringify(TAG_ID)}]`) === null) {
        const tag = document.createElement('style');
        tag.dataset.plugin = 'dsh-mobile-adaptation';
        tag.dataset.pluginCss = TAG_ID;
        tag.textContent = css;
        document.head.appendChild(tag);
      }
    }

    // 工厂顶层注入：首屏尽早生效，避免闪动。
    ensureStyle();

    function fabLabel() {
      return (typeof navigator !== 'undefined' && String(navigator.language || '').toLowerCase().startsWith('zh'))
        ? '打开侧栏'
        : 'Open sidebar';
    }

    function ensureChrome() {
      if (typeof document === 'undefined' || !document.body) return;
      if (document.querySelector(FAB_SELECTOR) === null) {
        const fab = document.createElement('button');
        fab.type = 'button';
        fab.className = 'dsh-ma-fab';
        fab.setAttribute('aria-label', fabLabel());
        fab.innerHTML =
          '<svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">' +
          '<path d="M3 5h14M3 10h14M3 15h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>';
        document.body.appendChild(fab);
      }
      if (document.querySelector(BACKDROP_SELECTOR) === null) {
        const backdrop = document.createElement('div');
        backdrop.className = 'dsh-ma-backdrop';
        backdrop.setAttribute('aria-hidden', 'true');
        document.body.appendChild(backdrop);
      }
    }

    function sidebarOpen() {
      const frame = document.querySelector(FRAME_SELECTOR);
      return frame !== null && !frame.hasAttribute('data-sidebar-collapsed');
    }

    const inject = ['layout'];

    function apply(ctx) {
      ctx.effect(() => {
        if (typeof document === 'undefined' || !document.body) return () => {};
        ensureStyle();
        ensureChrome();
        const observer = new MutationObserver(ensureChrome);
        observer.observe(document.body, { childList: true });

        const safeToggle = () => {
          try {
            ctx.layout.toggleSidebar();
          } catch {
            // root entry 尚未挂载（LayoutController 未接线）时忽略
          }
        };

        const onClick = (event) => {
          const target = event.target instanceof Element ? event.target : null;
          if (target === null) return;
          if (target.closest(FAB_SELECTOR) !== null) {
            if (!sidebarOpen()) safeToggle();
            return;
          }
          if (target.closest(BACKDROP_SELECTOR) !== null) {
            if (sidebarOpen()) safeToggle();
          }
        };
        document.addEventListener('click', onClick);

        const onKeydown = (event) => {
          if (event.key === 'Escape' && sidebarOpen()) safeToggle();
        };
        document.addEventListener('keydown', onKeydown);

        return () => {
          observer.disconnect();
          document.removeEventListener('click', onClick);
          document.removeEventListener('keydown', onKeydown);
          document.querySelector(FAB_SELECTOR)?.remove();
          document.querySelector(BACKDROP_SELECTOR)?.remove();
          document.querySelector(`style[data-plugin-css=${JSON.stringify(TAG_ID)}]`)?.remove();
        };
      }, 'mobile-adaptation: F1 sidebar drawer');
    }

    exports.apply = apply;
    exports.inject = inject;
    return module.exports;
  },
});
