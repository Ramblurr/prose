const previewTheme = `
<style id="playground-preview-theme">
  :where(:root) { color-scheme: var(--preview-color-scheme); }
  :where(body) {
    background: var(--preview-background);
    color: var(--preview-text);
    font: 1rem/1.62 ui-sans-serif, system-ui, sans-serif;
    margin: 0;
    padding: clamp(2rem, 6vw, 5rem);
  }
  :where(h1, h2, h3) {
    color: var(--preview-heading);
    font-family: ui-serif, Georgia, serif;
    letter-spacing: -0.025em;
    line-height: 1.12;
  }
  :where(h1, h2) { margin-block: 0.4em 0.7em; }
  :where(p, ul, ol, pre, blockquote) { max-width: 72ch; }
  :where(a) { color: var(--preview-link); }
  :where(mark) { background: var(--preview-mark); border-radius: 0.2rem; padding-inline: 0.2rem; }
  :where(code, pre) { font-family: ui-monospace, monospace; }
  :where(blockquote) { border-inline-start: 0.25rem solid var(--preview-border); margin-inline: 0; padding-inline: 1rem; }
  :where(hr) { border: 0; border-top: 1px solid var(--preview-border); }
</style>`;

const palettes = {
  dark: `--preview-color-scheme: dark; --preview-background: #1b1e23; --preview-text: #e0e3e6; --preview-heading: #fff; --preview-link: #8fded7; --preview-mark: #705e24; --preview-border: #555b64;`,
  light: `--preview-color-scheme: light; --preview-background: #fff; --preview-text: #25231f; --preview-heading: #171714; --preview-link: #075e63; --preview-mark: #fae57c; --preview-border: #d8d4ca;`,
};

export function previewDocument(html, { appearance, themeEnabled }) {
  const theme = themeEnabled
    ? `<style id="playground-preview-palette">:where(:root) { ${palettes[appearance] ?? palettes.light} }</style>${previewTheme}`
    : "";
  return `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'">${theme}</head><body>${html}</body></html>`;
}
