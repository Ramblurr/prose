(ns prose.playground.preview-document)

(def preview-theme
  (str "\n<style id=\"playground-preview-theme\">\n"
       "  :where(:root) { color-scheme: var(--preview-color-scheme); }\n"
       "  :where(body) {\n"
       "    background: var(--preview-background);\n"
       "    color: var(--preview-text);\n"
       "    font: 1rem/1.62 ui-sans-serif, system-ui, sans-serif;\n"
       "    margin: 0;\n"
       "    padding: clamp(2rem, 6vw, 5rem);\n"
       "  }\n"
       "  :where(h1, h2, h3) {\n"
       "    color: var(--preview-heading);\n"
       "    font-family: ui-serif, Georgia, serif;\n"
       "    letter-spacing: -0.025em;\n"
       "    line-height: 1.12;\n"
       "  }\n"
       "  :where(h1, h2) { margin-block: 0.4em 0.7em; }\n"
       "  :where(p, ul, ol, pre, blockquote) { max-width: 72ch; }\n"
       "  :where(a) { color: var(--preview-link); }\n"
       "  :where(mark) { background: var(--preview-mark); border-radius: 0.2rem; "
       "padding-inline: 0.2rem; }\n"
       "  :where(code, pre) { font-family: ui-monospace, monospace; }\n"
       "  :where(blockquote) { border-inline-start: 0.25rem solid var(--preview-border); "
       "margin-inline: 0; padding-inline: 1rem; }\n"
       "  :where(hr) { border: 0; border-top: 1px solid var(--preview-border); }\n"
       "</style>"))

(def palettes
  {"dark" (str "--preview-color-scheme: dark; --preview-background: #1b1e23; "
               "--preview-text: #e0e3e6; --preview-heading: #fff; "
               "--preview-link: #8fded7; --preview-mark: #705e24; "
               "--preview-border: #555b64;")
   "light" (str "--preview-color-scheme: light; --preview-background: #fff; "
                "--preview-text: #25231f; --preview-heading: #171714; "
                "--preview-link: #075e63; --preview-mark: #fae57c; "
                "--preview-border: #d8d4ca;")})

(defn preview-document [html ^js options]
  (let [appearance (.-appearance options)
        theme (if (.-themeEnabled options)
                (str "<style id=\"playground-preview-palette\">:where(:root) { "
                     (get palettes appearance (get palettes "light"))
                     " }</style>"
                     preview-theme)
                "")]
    (str "<!doctype html><html><head>"
         "<meta http-equiv=\"Content-Security-Policy\" "
         "content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:; "
         "form-action 'none'; base-uri 'none'\">"
         theme
         "</head><body>"
         html
         "</body></html>")))
