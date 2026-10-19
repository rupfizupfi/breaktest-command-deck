// Side-effect CSS imports need a module declaration; TypeScript reports TS2882 without it.
declare module '*.css';

declare module '*.css?inline' {
  import type { CSSResultGroup } from 'lit';
  const content: CSSResultGroup;
  export default content;
}

// Allow any CSS Custom Properties
declare module 'csstype' {
  interface Properties {
    [index: `--${string}`]: any;
  }
}
