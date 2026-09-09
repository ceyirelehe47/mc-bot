# dsh-mobile-adaptation

DeepSeek Harness Web UI 的移动端适配插件（纯浏览器端，无 host 逻辑）。

## 部署位置

- 插件目录：`~/.dsh/plugins/dsh-mobile-adaptation/`（symlink 于 `~/.dsh/profiles/web/node_modules/`）
- 注册：`~/.dsh/profiles/web/cordis.patch.yml` 的 `insert` 列表（id: `mobile-adaptation`）
- 服务：`systemctl restart deepseek-harness`

## 适配内容（<768px 生效，桌面端零影响）

### F1 侧栏 drawer 化

- 隐藏常驻 56px 侧栏 rail（`#root > div > div[style*="grid-template-columns"]` 的内联三列 grid 被 `!important` 覆盖为单列）；
- 左上角悬浮汉堡按钮（`.dsh-ma-fab`，44×44），点击调用 `ctx.layout.toggleSidebar()` 展开侧栏；
- 侧栏展开态（React `narrowExpanded`，特征为 frame 无 `data-sidebar-collapsed`）下 sidebarCol 脱流为固定定位覆盖层（宽 `min(85vw, 320px)`，滑入动画）；
- 同时显示遮罩 `.dsh-ma-backdrop`（点击/Escape 关闭）、锁定 body 滚动；侧栏自带收起按钮仍可用（双通道关闭）；
- 收起态 `visibility: hidden` 移出 tab 序（避免幽灵控件）。

### F2 消息内容防溢出

- `[data-conversation-scroll] :where(img, video) { max-width:100%; height:auto }`（`:where` 降权，官方带类名的 cover 布局不受影响）；
- `[data-conversation-scroll] :where(pre) { max-width:100%; overflow-x:auto }`（代码块兜底横滚；表格不设 max-width，官方 tableScroll 依赖 width:max-content + 外层横滚）；
- `[data-conversation-scroll] > * { min-width: 0 }`。

### F3 composer/触控目标优化

- 全局 tap-highlight 透明（官方未处理）；
- 会话区按钮/下拉触控目标 `min-height/min-width: 32px`（`:not([class*='copyButton'])` 排除 JSON 树悬浮复制按钮）。

### F4 模态弹窗适配（设置面板）

- 设置弹窗（`[role='dialog'][aria-modal='true']:has(> nav)`，全前端唯一命中）窄屏下侧导航（188px 固定宽，会把内容挤到 154px）改为顶部横向滚动 tab，内容区恢复全宽。

## 稳定性约定

官方前端使用 CSS Modules 哈希类名（每次构建变化），本插件的 CSS 锚点只使用稳定特征：

| 锚点 | 含义 |
| --- | --- |
| `#root > div > div[style*="grid-template-columns"]` | AppFrame 三列 grid（root 下有一层 display:contents 槽位包装） |
| 其第一个子 `div` | sidebarCol |
| `data-sidebar-collapsed` | React 侧栏收起态 |
| `[data-conversation-scroll]` | 会话滚动容器（含消息区 + composerSeat） |
| `[role='dialog'][aria-modal='true']:has(> nav)` | 设置弹窗 |
| `:has()` | Chrome 105+ / Safari 15.4+ / Firefox 121+ |

已知层叠事实：本插件 `immediately:true` 先注入 CSS，官方 UI 模块 CSS 后注入——同 specificity 时官方胜；跨属性（官方 width/height vs 插件 min-*）取 max，官方显式 min-* 同属性可压制插件。需要稳定胜出的规则用多属性选择器提高 specificity（如 F4 的 (0,2,1)+）。

## 验证方式

服务器 `/root/dsh-mobile-adaptation/` 下有 CDP 截图/诊断脚本（headless Chrome 调试端口 9333）：

- `shot.mjs <out> <w> <h> <url> <waitMs>`：视口截图 + 布局诊断（frameGrid、横向溢出）
- `f1-test.mjs`：drawer 开关三态验证；`f2-test.mjs`/`f2-retest.mjs`：内容注入防溢出验证
- `f3-test.mjs`：触控目标测量；`f4-test.mjs`：设置弹窗注入式验证
- 启动调试 Chrome：`nohup /root/.agent-browser/browsers/chrome-*/chrome --headless=new --no-sandbox --remote-debugging-port=9333 --user-data-dir=/tmp/dsh-cdp-profile ...`

## 迭代记录

- v0.1.0（2026-08-20）：F1 侧栏 drawer 化（subagent 审查通过，含 6 项加固）。
- v0.2.0（2026-08-20）：F2 消息内容防溢出（审查返修 :where 降权后复核通过）。
- v0.3.0（2026-08-20）：F3 触控目标 32px + tap-highlight（审查通过，含 copyButton 排除）。
- v0.4.0（2026-08-20）：F4 设置弹窗侧导航横化（审查通过，无返修项）。
