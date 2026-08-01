;;; clojure-prose-mode-test.el --- Tests for Clojure Prose Mode -*- lexical-binding: t; -*-

;; Copyright (C) 2026 Casey Link
;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'clojure-prose-mode)

(defvar-local clojure-prose-test-host-hook-ran nil)
(defvar-local clojure-prose-test-host-filled nil)
(defvar-local clojure-prose-test-host-navigated nil)

(defun clojure-prose-test-host-indent-line ()
  "Indent the current test host line by two spaces."
  (indent-line-to 2))

(defun clojure-prose-test-host-fill-paragraph (&optional _justify)
  "Record that test host filling was invoked."
  (setq clojure-prose-test-host-filled t))

(defun clojure-prose-test-host-beginning-of-defun (&optional _arg)
  "Record test host navigation and move to the buffer beginning."
  (setq clojure-prose-test-host-navigated t)
  (goto-char (point-min)))

(defun clojure-prose-test-host-completion ()
  "Offer one completion from the test host mode."
  (list (point) (point) '("HOST-COMPLETION")))

(defun clojure-prose-test-host-command ()
  "Provide a test host keymap command."
  (interactive))

(defun clojure-prose-test-host-tool-command ()
  "Provide a command owned by host tooling."
  (interactive))

(defun clojure-prose-test-embedded-tool-command ()
  "Provide a command owned by embedded Clojure tooling."
  (interactive))

(defun clojure-prose-test-host-note-hook ()
  "Record that the test host mode hook ran."
  (setq clojure-prose-test-host-hook-ran t))

(define-derived-mode clojure-prose-test-host-mode text-mode
  "Prose-Test-Host"
  "Synthetic host mode for buffer-level tests."
  (modify-syntax-entry ?# "<" clojure-prose-test-host-mode-syntax-table)
  (modify-syntax-entry ?\n ">" clojure-prose-test-host-mode-syntax-table)
  (setq-local font-lock-defaults '((("HOST" . font-lock-constant-face))))
  (setq-local indent-line-function #'clojure-prose-test-host-indent-line)
  (setq-local fill-paragraph-function
              #'clojure-prose-test-host-fill-paragraph)
  (setq-local beginning-of-defun-function
              #'clojure-prose-test-host-beginning-of-defun)
  (add-hook 'completion-at-point-functions
            #'clojure-prose-test-host-completion nil t))

(define-key clojure-prose-test-host-mode-map (kbd "C-c h")
  #'clojure-prose-test-host-command)

(define-key clojure-prose-test-host-mode-map (kbd "C-c C-c")
  #'clojure-prose-test-host-tool-command)

(add-hook 'clojure-prose-test-host-mode-hook
          #'clojure-prose-test-host-note-hook)

(define-derived-mode clojure-prose-test-embedded-mode clojure-mode
  "Prose-Test-Embedded"
  "Synthetic Clojure-family mode for buffer-level tests.")

(define-key clojure-prose-test-embedded-mode-map (kbd "C-c C-c")
  #'clojure-prose-test-embedded-tool-command)

(defmacro clojure-prose-test-with-suffix (suffix contents &rest body)
  "Visit a temporary Prose file with SUFFIX and CONTENTS, then evaluate BODY."
  (declare (indent 2) (debug (form form body)))
  `(let* ((file (make-temp-file "clojure-prose-test-" nil ,suffix ,contents))
          (buffer (find-file-noselect file)))
     (unwind-protect
         (with-current-buffer buffer
           ,@body)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (set-buffer-modified-p nil))
         (kill-buffer buffer))
       (delete-file file))))

(defmacro clojure-prose-test-with-file (contents &rest body)
  "Visit a temporary `.prose' file containing CONTENTS, then evaluate BODY."
  (declare (indent 1) (debug (form body)))
  `(clojure-prose-test-with-suffix ".prose" ,contents ,@body))

(defun clojure-prose-test-mode-at (needle offset)
  "Return the active major mode OFFSET characters from NEEDLE's start."
  (goto-char (point-min))
  (search-forward needle)
  (goto-char (+ (match-beginning 0) offset))
  (save-current-buffer
    (pm-switch-to-buffer)
    major-mode))

(defun clojure-prose-test-observation-at (needle offset)
  "Return mode and face OFFSET characters from NEEDLE's start."
  (goto-char (point-min))
  (search-forward needle)
  (let ((position (+ (match-beginning 0) offset)))
    (goto-char position)
    (save-current-buffer
      (pm-switch-to-buffer)
      (font-lock-mode 1)
      (font-lock-ensure)
      (list major-mode (get-text-property position 'face)))))

(ert-deftest clojure-prose-mode/opens-bare-prose-with-text-host ()
  (clojure-prose-test-with-file "ordinary text\n"
    (should (equal (list major-mode
                         (bound-and-true-p clojure-prose-mode))
                   '(text-mode t)))))

(ert-deftest clojure-prose-mode/resolves-host-mode-from-compound-filename ()
  (clojure-prose-test-with-suffix ".html.prose" "<p>ordinary text</p>\n"
    (should (and (bound-and-true-p clojure-prose-mode)
                 (derived-mode-p 'html-mode))))
  (let ((auto-mode-alist
         (append '(("\\.widget\\'" . clojure-prose-test-host-mode)
                   ("\\.missing\\'" . clojure-prose-test-missing-mode))
                 auto-mode-alist)))
    (dolist (fixture '((".unknown.prose" text-mode)
                       (".missing.prose" text-mode)
                       (".prose.prose" text-mode)
                       (".widget.prose" clojure-prose-test-host-mode)))
      (clojure-prose-test-with-suffix (car fixture) "ordinary text\n"
        (should (equal (list major-mode
                             (bound-and-true-p clojure-prose-mode))
                       (list (cadr fixture) t)))))))

(ert-deftest clojure-prose-mode/falls-back-from-content-only-mode-inference ()
  (dolist (contents '("#!/usr/bin/env python\nprint('not a host')\n"
                      "<?xml version=\"1.0\"?><document/>\n"))
    (clojure-prose-test-with-suffix ".unknown.prose" contents
      (should (equal (list major-mode
                           (bound-and-true-p clojure-prose-mode))
                     '(text-mode t))))))

(ert-deftest clojure-prose-mode/uses-buffer-local-host-mode-override ()
  (clojure-prose-test-with-suffix ".unknown.prose" "ordinary text\n"
    (setq-local clojure-prose-host-mode 'clojure-prose-test-host-mode)
    (clojure-prose-mode)
    (should (equal (list major-mode clojure-prose-host-mode)
                   '(clojure-prose-test-host-mode
                     clojure-prose-test-host-mode)))
    (setq-local clojure-prose-host-mode 'clojure-prose-test-missing-mode)
    (clojure-prose-mode)
    (should (equal (list major-mode clojure-prose-host-mode)
                   '(text-mode clojure-prose-test-missing-mode)))))

(ert-deftest clojure-prose-mode/uses-buffer-local-embedded-mode-override ()
  (clojure-prose-test-with-file "host ◊(identity 1)\n"
    (setq-local clojure-prose-embedded-mode
                'clojure-prose-test-embedded-mode)
    (clojure-prose-mode)
    (should
     (equal
      (list major-mode
            clojure-prose-embedded-mode
            (clojure-prose-test-mode-at "identity" 0))
      '(text-mode
        clojure-prose-test-embedded-mode
        clojure-prose-test-embedded-mode)))))

(ert-deftest clojure-prose-mode/preserves-host-editing-in-text-and-curly-bodies ()
  (let ((auto-mode-alist
         (cons '("\\.widget\\'" . clojure-prose-test-host-mode)
               auto-mode-alist)))
    (clojure-prose-test-with-suffix ".widget.prose"
      "HOST\n# comment\nunindented\n◊tag{HOST body}\n◊(inc 1)\n"
      (let ((body-observation
             (clojure-prose-test-observation-at "◊tag{HOST" 5)))
        (goto-char (point-min))
        (search-forward "# ")
        (let ((in-comment (nth 4 (syntax-ppss))))
          (search-forward "unindented")
          (beginning-of-line)
          (indent-for-tab-command)
          (fill-paragraph)
          (beginning-of-defun)
          (goto-char (point-max))
          (let ((completion
                 (run-hook-with-args-until-success
                  'completion-at-point-functions)))
            (should
             (equal
              (list body-observation
                    in-comment
                    clojure-prose-test-host-hook-ran
                    clojure-prose-test-host-filled
                    clojure-prose-test-host-navigated
                    (nth 2 completion)
                    (key-binding (kbd "C-c h"))
                    (buffer-string))
              '((clojure-prose-test-host-mode font-lock-constant-face)
                t t t t
                ("HOST-COMPLETION")
                clojure-prose-test-host-command
                "HOST\n# comment\n  unindented\n◊tag{HOST body}\n◊(inc 1)\n")))))))))

(ert-deftest clojure-prose-mode/prose-command-face-overrides-host-face ()
  (let ((auto-mode-alist
         (cons '("\\.widget\\'" . clojure-prose-test-host-mode)
               auto-mode-alist)))
    (clojure-prose-test-with-suffix ".widget.prose"
      "HOST text\n◊HOST{body}\n"
      (should
       (equal
        (list (clojure-prose-test-observation-at "HOST text" 0)
              (clojure-prose-test-observation-at "◊HOST" 1))
        '((clojure-prose-test-host-mode font-lock-constant-face)
          (clojure-prose-test-host-mode
           font-lock-function-name-face)))))))

(ert-deftest clojure-prose-mode/associates-only-final-prose-suffix ()
  (let* ((file (make-temp-file "clojure-prose-test-" nil ".prose.txt"
                               "ordinary text\n"))
         (buffer (find-file-noselect file)))
    (unwind-protect
        (with-current-buffer buffer
          (should (equal (list major-mode
                               (bound-and-true-p clojure-prose-mode))
                         '(text-mode nil))))
      (when (buffer-live-p buffer)
        (kill-buffer buffer))
      (delete-file file))))

(ert-deftest clojure-prose-mode/uses-clojure-for-complete-explicit-form ()
  (clojure-prose-test-with-file "before ◊(def answer 42) after\n"
    (search-forward "◊")
    (pm-switch-to-buffer)
    (should (eq major-mode 'clojure-mode))
    (forward-sexp)
    (should (looking-at " after"))))

(ert-deftest clojure-prose-mode/provides-clojure-fontification-and-indentation ()
  (clojure-prose-test-with-file "◊(let [x 1]\n(+ x 2))\n"
    (search-forward "(+")
    (beginning-of-line)
    (pm-switch-to-buffer)
    (indent-for-tab-command)
    (font-lock-mode 1)
    (font-lock-ensure)
    (goto-char (point-min))
    (search-forward "let")
    (should (equal (list (buffer-string)
                         (get-text-property (1- (point)) 'face))
                   '("◊(let [x 1]\n  (+ x 2))\n"
                     font-lock-keyword-face)))))

(ert-deftest clojure-prose-mode/highlights-named-command-without-pollen-semantics ()
  (clojure-prose-test-with-file "#lang racket\n◊strong{words}\n◊;{still text}\n"
    (font-lock-mode 1)
    (font-lock-ensure)
    (goto-char (point-min))
    (search-forward "strong")
    (let ((command-face (get-text-property (1- (point)) 'face)))
      (search-forward ";")
      (should (equal (list command-face
                           (nth 4 (syntax-ppss))
                           (get-text-property (1- (point)) 'face))
                     '(font-lock-function-name-face
                       nil
                       font-lock-function-name-face))))))

(ert-deftest clojure-prose-mode/electric-lozenge-works-in-host-and-clojure ()
  (clojure-prose-test-with-file "host\n◊(identity nil)\n"
    (goto-char (point-min))
    (search-forward "host")
    (let ((last-command nil))
      (call-interactively (key-binding (kbd "@")))
      (should (eq (char-before) ?◊))
      (setq last-command 'clojure-prose-insert-lozenge)
      (call-interactively (key-binding (kbd "@"))))
    (search-forward "identity")
    (pm-switch-to-buffer)
    (let ((last-command nil))
      (call-interactively (key-binding (kbd "@")))
      (should (eq (char-before) ?◊))
      (setq last-command 'clojure-prose-insert-lozenge)
      (call-interactively (key-binding (kbd "@"))))
    (should (equal (buffer-string)
                   "host@\n◊(identity@ nil)\n"))))

(ert-deftest clojure-prose-mode/electric-lozenge-preserves-existing-lozenges ()
  (clojure-prose-test-with-file "◊name\n◊(identity ◊)\n"
    (goto-char (1+ (point-min)))
    (let ((last-command nil))
      (call-interactively (key-binding (kbd "@"))))
    (search-forward "identity ◊")
    (pm-switch-to-buffer)
    (let ((last-command nil))
      (call-interactively (key-binding (kbd "@"))))
    (should (equal (buffer-string)
                   "◊◊name\n◊(identity ◊◊)\n"))))

(ert-deftest clojure-prose-mode/recognizes-symbol-command-token-boundaries ()
  (dolist (fixture
           '(("◊|simple"
              ((marker "◊|" 0) (payload "simple" 0))
              ((marker text-mode) (payload clojure-mode)))
             ("before ◊|some.ns/value after"
              ((payload "some.ns/value" 0) (after " after" 1))
              ((payload clojure-mode) (after text-mode)))
             ("◊|foo/bar/baz"
              ((payload "foo/bar" 0) (remainder "/baz" 0))
              ((payload clojure-mode) (remainder text-mode)))
             ("◊|foo/1bar"
              ((payload "foo" 0) (remainder "/1bar" 0))
              ((payload clojure-mode) (remainder text-mode)))
             ("◊|simple after"
              ((payload "simple" 0) (after " after" 1))
              ((payload clojure-mode) (after text-mode)))))
    (let ((source (nth 0 fixture))
          (probes (nth 1 fixture))
          (expected (nth 2 fixture)))
      (clojure-prose-test-with-file source
        (should
         (equal
          (mapcar (lambda (probe)
                    (list (car probe)
                          (clojure-prose-test-mode-at (nth 1 probe)
                                                     (nth 2 probe))))
                  probes)
          expected))))))

(ert-deftest clojure-prose-mode/recognizes-top-level-command-grammar ()
  (dolist
      (fixture
       '(("before ◊\"literal ◊|not-code and ◊strong and \\\"quote\" after"
          ((literal "literal" 0) (inner-symbol "not-code" 0)
           (inner-name "strong" 0) (escaped "quote" 0)
           (after " after" 1))
          ((literal text-mode nil) (inner-symbol text-mode nil)
           (inner-name text-mode nil) (escaped text-mode nil)
           (after text-mode nil)))
         ("◊tag"
          ((name "tag" 0))
          ((name text-mode font-lock-function-name-face)))
         ("◊some.ns/tag"
          ((name "some.ns/tag" 0))
          ((name text-mode font-lock-function-name-face)))
         ("◊foo/bar/baz"
          ((name "foo/bar" 0) (remainder "/baz" 0))
          ((name text-mode font-lock-function-name-face)
           (remainder text-mode nil)))
         ("◊foo/1bar"
          ((name "foo" 0) (remainder "/1bar" 0))
          ((name text-mode font-lock-function-name-face)
           (remainder text-mode nil)))
         ("◊tag   trailing"
          ((name "tag" 0) (trailing "trailing" 0))
          ((name text-mode font-lock-function-name-face)
           (trailing text-mode nil)))
         ("◊tag [1]"
          ((name "tag" 0) (open "[1]" 0) (close "[1]" 2))
          ((name text-mode font-lock-function-name-face)
           (open clojure-mode nil) (close clojure-mode nil)))
         ("◊tag[1]{two}[3]{four}"
          ((name "tag" 0) (first-square "[1]" 0) (first-body "two" 0)
           (second-square "[3]" 0) (second-body "four" 0))
          ((name text-mode font-lock-function-name-face)
           (first-square clojure-mode nil) (first-body text-mode nil)
           (second-square clojure-mode nil) (second-body text-mode nil)))
         ("◊◊some.ns/group [a]\n {body}"
          ((marker "◊◊" 0) (name "some.ns/group" 0)
           (square "[a]" 0) (body "body" 0))
          ((marker text-mode nil)
           (name text-mode font-lock-function-name-face)
           (square clojure-mode nil) (body text-mode nil)))
         ("plain () [] {}.\n◊tag[{:x [1 2]}\n \"closing ] and escaped \\\" quote\"] tail\n◊other{before {nested} after}"
          ((punctuation "()" 0) (outer-square "[{:x" 0)
           (nested-square "[1 2]" 0) (string-content "closing ]" 0)
           (outer-close "\"] tail" 1) (tail " tail" 1)
           (curly-body "before {nested} after" 0))
          ((punctuation text-mode nil) (outer-square clojure-mode nil)
           (nested-square clojure-mode nil)
           (string-content clojure-mode font-lock-string-face)
           (outer-close clojure-mode nil) (tail text-mode nil)
           (curly-body text-mode nil)))))
    (let ((source (nth 0 fixture))
          (probes (nth 1 fixture))
          (expected (nth 2 fixture)))
      (clojure-prose-test-with-file source
        (should
         (equal
          (mapcar (lambda (probe)
                    (cons (car probe)
                          (clojure-prose-test-observation-at (nth 1 probe)
                                                            (nth 2 probe))))
                  probes)
          expected))))))

(ert-deftest clojure-prose-mode/warns-on-locally-detectable-malformed-syntax ()
  (dolist (fixture '(("◊" "◊" 0)
                     ("◊#invalid" "◊#" 0)
                     ("◊#invalid" "◊#" 1)
                     ("◊\"unterminated" "\"" 0)
                     ("◊(list 1" "(" 0)
                     ("◊tag[1" "[" 0)
                     ("◊tag{body" "{" 0)))
    (clojure-prose-test-with-file (car fixture)
      (should (equal (clojure-prose-test-observation-at
                      (nth 1 fixture) (nth 2 fixture))
                     '(text-mode font-lock-warning-face))))))

(ert-deftest clojure-prose-mode/updates-modes-and-faces-after-character-edits ()
  (clojure-prose-test-with-file "HOST ◊tag[1] TAIL\n"
    (let (observations)
      (search-forward "]")
      (delete-char -1)
      (push (clojure-prose-test-observation-at "[1" 0) observations)
      (search-forward " TAIL")
      (goto-char (match-beginning 0))
      (insert "]")
      (push (clojure-prose-test-observation-at "[1]" 0) observations)
      (goto-char (point-min))
      (search-forward "◊tag")
      (goto-char (match-beginning 0))
      (delete-char 1)
      (push (clojure-prose-test-observation-at "tag" 0) observations)
      (insert "◊")
      (push (clojure-prose-test-observation-at "[1]" 0) observations)
      (goto-char (point-max))
      (insert "◊\"open")
      (push (clojure-prose-test-observation-at "\"open" 0) observations)
      (goto-char (point-max))
      (insert "\"")
      (push (clojure-prose-test-observation-at "\"open\"" 0) observations)
      (goto-char (point-max))
      (delete-char -1)
      (push (clojure-prose-test-observation-at "\"open" 0) observations)
      (goto-char (point-max))
      (insert "\"")
      (push (clojure-prose-test-observation-at "\"open\"" 0) observations)
      (should (equal (nreverse observations)
                     '((text-mode font-lock-warning-face)
                       (clojure-mode nil)
                       (text-mode nil)
                       (clojure-mode nil)
                       (text-mode font-lock-warning-face)
                       (text-mode nil)
                       (text-mode font-lock-warning-face)
                       (text-mode nil)))))))

(ert-deftest clojure-prose-mode/keeps-malformed-input-and-comments-editable ()
  (let ((auto-mode-alist
         (cons '("\\.widget\\'" . clojure-prose-test-host-mode)
               auto-mode-alist))
        (processes (process-list))
        navigated-mode)
    (clojure-prose-test-with-suffix ".widget.prose"
      (concat "# host comment\n"
              "◊#invalid\n"
              "◊(do ; clojure comment ◊fake[9]{HOST} ) ] } (\n"
              "  (inc 1))\n"
              "◊;{HOST}\n"
              "◊tag[1")
      (font-lock-ensure)
      (goto-char (point-max))
      (indent-for-tab-command)
      (goto-char (point-min))
      (polymode-next-chunk 1)
      (pm-switch-to-buffer)
      (setq navigated-mode major-mode)
      (cl-labels
          ((comment-at
            (needle)
            (goto-char (point-min))
            (search-forward needle)
            (let ((position (1+ (match-beginning 0))))
              (save-current-buffer
                (pm-switch-to-buffer)
                (list major-mode (nth 4 (syntax-ppss position)))))))
        (should
         (equal
          (list navigated-mode
                (comment-at "# host comment")
                (comment-at "; clojure comment")
                (clojure-prose-test-observation-at "◊fake" 1)
                (clojure-prose-test-observation-at "(inc" 0)
                (clojure-prose-test-observation-at "◊;" 1)
                (clojure-prose-test-observation-at "[1" 0)
                (equal processes (process-list)))
          '(clojure-mode
            (clojure-prose-test-host-mode t)
            (clojure-mode t)
            (clojure-mode font-lock-comment-face)
            (clojure-mode nil)
            (clojure-prose-test-host-mode font-lock-function-name-face)
            (clojure-prose-test-host-mode font-lock-warning-face)
            t)))))))

(ert-deftest clojure-prose-mode/preserves-clojure-character-literals ()
  (dolist (fixture '(("◊(vector \\; 1)" "(")
                     ("◊tag[\\; 1]" "[")))
    (clojure-prose-test-with-file (car fixture)
      (should
       (equal
        (list (clojure-prose-test-observation-at (cadr fixture) 0)
              (clojure-prose-test-observation-at "\\;" 1))
        '((clojure-mode nil)
          (clojure-mode clojure-character-face)))))))

(ert-deftest clojure-prose-mode/recursively-restores-enclosing-modes ()
  (let ((auto-mode-alist
         (cons '("\\.widget\\'" . clojure-prose-test-host-mode)
               auto-mode-alist))
        (source
         (concat
          "HOST0 ◊outer{HOST1 ◊curly[11]{HOST2} HOST3} HOST4\n"
          "◊square[21 ◊nested{HOST5} 22] HOST6\n"
          "◊(vector 31 ◊inside[32]{HOST7 ◊deep[33]{HOST8} HOST9} 34) HOST10\n")))
    (clojure-prose-test-with-suffix ".widget.prose" source
      (let ((probes
             '((host-start "HOST0" 0)
               (outer-name "◊outer" 1)
               (outer-curly "HOST1" 0)
               (curly-name "◊curly" 1)
               (curly-square "11" 0)
               (curly-square-body "HOST2" 0)
               (curly-restored "HOST3" 0)
               (host-restored "HOST4" 0)
               (square-before "21" 0)
               (square-name "◊nested" 1)
               (square-curly "HOST5" 0)
               (square-restored "22" 0)
               (host-restored-again "HOST6" 0)
               (form-before "31" 0)
               (form-name "◊inside" 1)
               (form-square "32" 0)
               (form-curly "HOST7" 0)
               (deep-name "◊deep" 1)
               (deep-square "33" 0)
               (deep-curly "HOST8" 0)
               (nested-host-restored "HOST9" 0)
               (form-restored "34" 0)
               (document-restored "HOST10" 0))))
        (should
         (equal
          (mapcar (lambda (probe)
                    (cons (car probe)
                          (clojure-prose-test-observation-at (nth 1 probe)
                                                            (nth 2 probe))))
                  probes)
          '((host-start clojure-prose-test-host-mode
                        font-lock-constant-face)
            (outer-name clojure-prose-test-host-mode
                        font-lock-function-name-face)
            (outer-curly clojure-prose-test-host-mode
                         font-lock-constant-face)
            (curly-name clojure-prose-test-host-mode
                        font-lock-function-name-face)
            (curly-square clojure-mode nil)
            (curly-square-body clojure-prose-test-host-mode
                               font-lock-constant-face)
            (curly-restored clojure-prose-test-host-mode
                            font-lock-constant-face)
            (host-restored clojure-prose-test-host-mode
                           font-lock-constant-face)
            (square-before clojure-mode nil)
            (square-name clojure-prose-test-host-mode
                         font-lock-function-name-face)
            (square-curly clojure-prose-test-host-mode
                          font-lock-constant-face)
            (square-restored clojure-mode nil)
            (host-restored-again clojure-prose-test-host-mode
                                 font-lock-constant-face)
            (form-before clojure-mode nil)
            (form-name clojure-prose-test-host-mode
                       font-lock-function-name-face)
            (form-square clojure-mode nil)
            (form-curly clojure-prose-test-host-mode
                        font-lock-constant-face)
            (deep-name clojure-prose-test-host-mode
                       font-lock-function-name-face)
            (deep-square clojure-mode nil)
            (deep-curly clojure-prose-test-host-mode
                        font-lock-constant-face)
            (nested-host-restored clojure-prose-test-host-mode
                                  font-lock-constant-face)
            (form-restored clojure-mode nil)
            (document-restored clojure-prose-test-host-mode
                               font-lock-constant-face))))))))

(ert-deftest clojure-prose-mode/protects-strings-and-balances-recursive-arguments ()
  (let ((auto-mode-alist
         (cons '("\\.widget\\'" . clojure-prose-test-host-mode)
               auto-mode-alist))
        (source
         (concat
          "◊outer[\n"
          "  {:message \"◊fake{] )} escaped \\\" ◊still-fake\"}\n"
          "  ◊inner[(list [1 {:x \"closing ]\"}])]{HOSTA\n"
          "    ◊deep[{:nested (vector 3)}]{HOSTB}\n"
          "    HOSTC}\n"
          "  99]\n"
          "{HOSTD ◊body[44]{HOSTE} HOSTF}\n"
          "[77 ◊again{HOSTG} 88]\n"
          "{HOSTH}\n"
          "HOSTI\n")))
    (clojure-prose-test-with-suffix ".widget.prose" source
      (let ((probes
             '((outer-name "◊outer" 1 clojure-prose-test-host-mode
                           font-lock-function-name-face)
               (string-lozenge "◊fake" 1 clojure-mode
                                font-lock-string-face)
               (escaped-string-lozenge "◊still-fake" 1 clojure-mode
                                        font-lock-string-face)
               (inner-name "◊inner" 1 clojure-prose-test-host-mode
                           font-lock-function-name-face)
               (balanced-list "(list" 0 clojure-mode nil)
               (inner-curly "HOSTA" 0 clojure-prose-test-host-mode
                            font-lock-constant-face)
               (deep-name "◊deep" 1 clojure-prose-test-host-mode
                          font-lock-function-name-face)
               (deep-square "{:nested" 0 clojure-mode nil)
               (deep-curly "HOSTB" 0 clojure-prose-test-host-mode
                           font-lock-constant-face)
               (inner-restored "HOSTC" 0 clojure-prose-test-host-mode
                               font-lock-constant-face)
               (outer-square-restored "99" 0 clojure-mode nil)
               (outer-curly "HOSTD" 0 clojure-prose-test-host-mode
                            font-lock-constant-face)
               (body-name "◊body" 1 clojure-prose-test-host-mode
                          font-lock-function-name-face)
               (body-square "44" 0 clojure-mode nil)
               (body-curly "HOSTE" 0 clojure-prose-test-host-mode
                           font-lock-constant-face)
               (body-restored "HOSTF" 0 clojure-prose-test-host-mode
                              font-lock-constant-face)
               (repeated-square "77" 0 clojure-mode nil)
               (again-name "◊again" 1 clojure-prose-test-host-mode
                           font-lock-function-name-face)
               (again-curly "HOSTG" 0 clojure-prose-test-host-mode
                            font-lock-constant-face)
               (repeated-square-restored "88" 0 clojure-mode nil)
               (repeated-curly "HOSTH" 0 clojure-prose-test-host-mode
                               font-lock-constant-face)
               (document-restored "HOSTI" 0 clojure-prose-test-host-mode
                                  font-lock-constant-face))))
        (should
         (equal
          (mapcar (lambda (probe)
                    (cons (car probe)
                          (clojure-prose-test-observation-at (nth 1 probe)
                                                            (nth 2 probe))))
                  probes)
          (mapcar (lambda (probe)
                    (list (car probe) (nth 3 probe) (nth 4 probe)))
                  probes)))))))

(ert-deftest clojure-prose-mode/preserves-polymode-commands-and-tooling-keys ()
  (let ((auto-mode-alist
         (cons '("\\.widget\\'" . clojure-prose-test-host-mode)
               auto-mode-alist)))
    (clojure-prose-test-with-suffix ".widget.prose"
      "HOST0\n◊outer[11 ◊inner{HOST1} 22]{HOST2}\nHOST3\n"
      (setq-local clojure-prose-embedded-mode
                  'clojure-prose-test-embedded-mode)
      (clojure-prose-mode)
      (goto-char (point-min))
      (let (navigation marked narrowed keys)
        (dotimes (_ 4)
          (polymode-next-chunk 1)
          (push (list (save-current-buffer
                        (pm-switch-to-buffer)
                        major-mode)
                      (char-after))
                navigation))
        (setq navigation (nreverse navigation))
        (goto-char (point-min))
        (search-forward "HOST1")
        (goto-char (match-beginning 0))
        (polymode-mark-or-extend-chunk)
        (setq marked
              (buffer-substring-no-properties (region-beginning)
                                              (region-end)))
        (deactivate-mark)
        (goto-char (point-min))
        (search-forward "11")
        (goto-char (match-beginning 0))
        (polymode-toggle-chunk-narrowing)
        (setq narrowed (buffer-string))
        (polymode-toggle-chunk-narrowing)
        (dolist (probe '(("HOST0" 0) ("11" 0) ("◊inner" 1)
                         ("22" 0) ("HOST2" 0)))
          (goto-char (point-min))
          (search-forward (car probe))
          (goto-char (+ (match-beginning 0) (cadr probe)))
          (push (save-current-buffer
                  (pm-switch-to-buffer)
                  (key-binding (kbd "C-c C-c")))
                keys))
        (should
         (equal
          (list navigation marked narrowed (nreverse keys))
          '(((clojure-prose-test-embedded-mode ?\[)
             (clojure-prose-test-host-mode ?◊)
             (clojure-prose-test-embedded-mode ?\s)
             (clojure-prose-test-host-mode ?\{))
            "◊inner{HOST1}"
            "[11 "
            (clojure-prose-test-host-tool-command
             clojure-prose-test-embedded-tool-command
             clojure-prose-test-host-tool-command
             clojure-prose-test-embedded-tool-command
             clojure-prose-test-host-tool-command))))))))

(ert-deftest clojure-prose-mode/electric-lozenge-works-in-recursive-chunks ()
  (let ((auto-mode-alist
         (cons '("\\.widget\\'" . clojure-prose-test-host-mode)
               auto-mode-alist)))
    (clojure-prose-test-with-suffix ".widget.prose"
      "HOST0\n◊outer[11 ◊inner[12]{HOST1} 22]{HOST2 ◊}\n"
      (let (modes)
        (dolist (needle '("HOST0" "11" "12" "HOST1" "22" "HOST2"))
          (goto-char (point-min))
          (search-forward needle)
          (save-current-buffer
            (pm-switch-to-buffer)
            (push major-mode modes)
            (let ((last-command nil))
              (call-interactively (key-binding (kbd "@")))
              (setq last-command 'clojure-prose-insert-lozenge)
              (call-interactively (key-binding (kbd "@"))))))
        (goto-char (point-min))
        (search-forward "HOST2@ ◊")
        (save-current-buffer
          (pm-switch-to-buffer)
          (let ((last-command nil))
            (call-interactively (key-binding (kbd "@")))))
        (should
         (equal
          (list (nreverse modes) (buffer-string))
          '((clojure-prose-test-host-mode
             clojure-mode
             clojure-mode
             clojure-prose-test-host-mode
             clojure-mode
             clojure-prose-test-host-mode)
            "HOST0@\n◊outer[11@ ◊inner[12@]{HOST1@} 22@]{HOST2@ ◊◊}\n")))))))

(ert-deftest clojure-prose-mode/fontifies-a-large-repeated-mixed-document ()
  (let* ((unit "HOST ◊outer[1 ◊inner{body} 2]{tail}\n")
         (source (concat (mapconcat #'identity (make-list 500 unit) "")
                         "◊broken[1")))
    (clojure-prose-test-with-file source
      (font-lock-ensure)
      (goto-char (point-max))
      (search-backward "◊inner")
      (goto-char (1+ (match-beginning 0)))
      (let ((last-name
             (save-current-buffer
               (pm-switch-to-buffer)
               (font-lock-ensure)
               (list major-mode (get-text-property (point) 'face)))))
        (goto-char (point-max))
        (search-backward " 2]")
        (goto-char (1+ (match-beginning 0)))
        (let ((last-clojure-mode
               (save-current-buffer
                 (pm-switch-to-buffer)
                 major-mode)))
          (should
           (equal
            (list last-name
                  last-clojure-mode
                  (clojure-prose-test-observation-at "◊broken[1" 7))
            '((text-mode font-lock-function-name-face)
              clojure-mode
              (text-mode font-lock-warning-face)))))))))

(ert-deftest clojure-prose-mode/moves-structurally-across-nested-commands ()
  (clojure-prose-test-with-file
      "◊(vector [1 {:x ◊inner[(list 2)]{body}}] 3) HOST\n"
    (search-forward "◊(")
    (backward-char)
    (pm-switch-to-buffer)
    (let ((mode major-mode))
      (forward-sexp)
      (should (equal (list mode
                           (buffer-substring-no-properties (point)
                                                           (point-max)))
                     '(clojure-mode " HOST\n"))))))

(provide 'clojure-prose-mode-test)

;;; clojure-prose-mode-test.el ends here
