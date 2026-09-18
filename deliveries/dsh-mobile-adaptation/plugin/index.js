// dsh-mobile-adaptation host 侧入口。
// 本插件只做浏览器端适配（CSS 注入 + 少量 DOM 辅助），host 侧无逻辑；
// 导出空 apply 以满足 profile 装载协议。
export const name = 'mobile-adaptation';
export const inject = [];

export async function apply() {}
