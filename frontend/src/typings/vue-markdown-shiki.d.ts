// 2.0.0 的 exports 指向了未发布的 index.d.ts，补齐本项目实际使用的组件契约。
declare module 'vue-markdown-shiki' {
  import type { DefineComponent, Plugin } from 'vue';
  const markdownPlugin: Plugin;
  export default markdownPlugin;
  export const VueMarkdownIt: DefineComponent<{ content?: string }>;
  export const VueMarkdownItProvider: DefineComponent;
}
