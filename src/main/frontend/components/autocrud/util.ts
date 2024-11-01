import React, { type CSSProperties, forwardRef } from 'react';

export type ComponentStyleProps = Readonly<{
    id?: string;
    style?: CSSProperties;
    className?: string;
}>;

export function registerStylesheet(stylesheet: CSSStyleSheet): void {
    const css = Array.from(stylesheet.cssRules)
        .map((rule) => rule.cssText)
        .join('\n');

    const styleTag = document.createElement('style');
    styleTag.textContent = css;
    document.head.prepend(styleTag);
}