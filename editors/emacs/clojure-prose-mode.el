;;; clojure-prose-mode.el --- Edit Prose with embedded Clojure -*- lexical-binding: t; -*-

;; Copyright (C) 2016 Junsong Li
;; Copyright (C) 2026 Casey Link

;; Author: Casey Link
;; Maintainer: Casey Link
;; Version: 0.1.0
;; Package-Requires: ((emacs "25.1") (polymode "0.2.2") (clojure-mode "5.20.0"))
;; Keywords: languages, clojure, prose
;; URL: https://github.com/ramblurr/prose/tree/master/editors/emacs
;; License: LGPL-3.0-only

;; This file incorporates ideas from pollen-mode.el by Junsong Li.
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Lesser General Public License as published
;; by the Free Software Foundation, version 3 of the License.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Lesser General Public License for more details.
;;
;; You should have received a copy of the GNU Lesser General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.

;;; Commentary:

;; Clojure Prose Mode uses Polymode to preserve the filename-selected host
;; while recursively editing Prose commands and embedded Clojure regions.

;;; Code:

(require 'cl-lib)
(require 'polymode)
(require 'clojure-mode)

(defgroup clojure-prose nil
  "Editing Prose documents with embedded Clojure."
  :group 'languages)

(defcustom clojure-prose-host-mode nil
  "Major mode override for host text in the current Prose buffer.

When nil, use the mode associated with the filename before its final
`.prose' suffix.  An unavailable override falls back to `text-mode'."
  :type '(choice (const :tag "Filename association" nil) symbol)
  :group 'clojure-prose)

(make-variable-buffer-local 'clojure-prose-host-mode)

(defcustom clojure-prose-embedded-mode 'clojure-mode
  "Major mode for embedded Clojure in the current Prose buffer.

The mode is selected explicitly; Prose content never infers a runtime.
An unavailable value falls back to `clojure-mode'."
  :type 'symbol
  :group 'clojure-prose)

(make-variable-buffer-local 'clojure-prose-embedded-mode)

(defconst clojure-prose--reader-whitespace
  '(9 10 11 12 13 32 160 5760
    8192 8193 8194 8195 8196 8197 8198 8199 8200 8201 8202
    8232 8233 8239 8287 12288)
  "Character codes treated as whitespace by the Prose reader.")

(defun clojure-prose--reader-whitespace-p (character)
  "Return non-nil when CHARACTER is Prose reader whitespace."
  (memq character clojure-prose--reader-whitespace))

(defun clojure-prose--symbol-regular-character-p (character)
  "Return non-nil when CHARACTER may continue a Prose reader symbol."
  (and character
       (not (clojure-prose--reader-whitespace-p character))
       (not (memq character '(?◊ ?/ ?\\ ?\( ?\) ?\[ ?\] ?\{ ?\} ?\")))))

(defun clojure-prose--symbol-first-character-p (character)
  "Return non-nil when CHARACTER may begin a Prose reader symbol."
  (and (clojure-prose--symbol-regular-character-p character)
       (not (eq character ?#))
       (not (and (>= character ?0) (<= character ?9)))))

(defun clojure-prose--simple-symbol-end (start)
  "Return the end of the simple Prose reader symbol at START, or nil."
  (when (clojure-prose--symbol-first-character-p (char-after start))
    (let ((position (1+ start)))
      (while (clojure-prose--symbol-regular-character-p
              (char-after position))
        (setq position (1+ position)))
      position)))

(defun clojure-prose--symbol-end (start)
  "Return the reader token end of the Prose symbol at START, or nil."
  (let ((name-end (clojure-prose--simple-symbol-end start)))
    (when name-end
      (if (eq (char-after name-end) ?/)
          (or (clojure-prose--simple-symbol-end (1+ name-end))
              name-end)
        name-end))))

(defun clojure-prose--quoted-end (start)
  "Return the end of the escaped quoted text at START, or nil."
  (let ((position (1+ start))
        end)
    (while (and (not end) (< position (point-max)))
      (pcase (char-after position)
        (?\" (setq end (1+ position)))
        (?\\ (setq position
                   (if (< (1+ position) (point-max))
                       (+ position 2)
                     (point-max))))
        (_ (setq position (1+ position)))))
    end))

(defun clojure-prose--skip-reader-whitespace (start)
  "Return the first position at or after START that is not reader whitespace."
  (let ((position start))
    (while (clojure-prose--reader-whitespace-p (char-after position))
      (setq position (1+ position)))
    position))

(defun clojure-prose--outermost-ranges (ranges)
  "Return RANGES with regions contained by another region removed."
  (let (outermost)
    (dolist (range ranges (nreverse outermost))
      (unless (cl-some (lambda (outer)
                         (and (<= (car outer) (car range))
                              (>= (cdr outer) (cdr range))))
                       outermost)
        (push range outermost)))))

(defun clojure-prose--effective-host-ranges (clojure-ranges host-ranges)
  "Return disjoint host spans selected by CLOJURE-RANGES and HOST-RANGES."
  (let (boundaries tagged effective)
    (dolist (range clojure-ranges)
      (push (car range) boundaries)
      (push (cdr range) boundaries)
      (push (cons range 'clojure) tagged))
    (dolist (range host-ranges)
      (push (car range) boundaries)
      (push (cdr range) boundaries)
      (push (cons range 'host) tagged))
    (setq boundaries (sort (delete-dups boundaries) #'<))
    (while (cdr boundaries)
      (let ((start (car boundaries))
            (end (cadr boundaries))
            innermost)
        (dolist (entry tagged)
          (let ((range (car entry)))
            (when (and (<= (car range) start)
                       (>= (cdr range) end)
                       (or (not innermost)
                           (> (car range) (caar innermost))
                           (and (= (car range) (caar innermost))
                                (< (cdr range) (cdar innermost)))))
              (setq innermost entry))))
        (when (and innermost (eq (cdr innermost) 'host))
          (if (and effective (= (cdr (car effective)) start))
              (setcdr (car effective) end)
            (push (cons start end) effective))))
      (setq boundaries (cdr boundaries)))
    (nreverse effective)))

(defvar-local clojure-prose--scan-cache nil
  "Last recursive scan computed for the base Prose buffer.")

(defvar-local clojure-prose--scan-cache-tick nil
  "Character modification tick represented by `clojure-prose--scan-cache'.")

(defun clojure-prose--scan-buffer ()
  "Return recursive Prose regions and local warnings as a plist.

The `:clojure' value contains outer embedded ranges; `:host' contains the
nested host overrides within them.  `:names' contains every command name,
and `:warnings' contains locally detectable malformed syntax."
  (let (clojure-ranges host-ranges name-ranges warning-ranges)
    (save-restriction
      (widen)
      (save-excursion
        (cl-labels
            ((delimited-end
              (start content-mode)
              (let* ((opening (char-after start))
                     (closing (cond ((eq opening ?\() ?\))
                                    ((eq opening ?\[) ?\])
                                    ((eq opening ?\{) ?\})))
                     (clojure-content-p (eq content-mode 'clojure))
                     (position (1+ start))
                     (depth 1)
                     end)
                (when closing
                  (while (and (not end) (< position (point-max)))
                    (let ((character (char-after position)))
                      (cond
                       ((and clojure-content-p (eq character ?\"))
                        (setq position
                              (or (clojure-prose--quoted-end position)
                                  (point-max))))
                       ((and clojure-content-p (eq character ?\\))
                        (setq position (min (point-max) (+ position 2))))
                       ((and clojure-content-p (eq character ?\;))
                        (setq position
                              (or (save-excursion
                                    (goto-char position)
                                    (search-forward "\n" nil t))
                                  (point-max))))
                       ((eq character ?◊)
                        (let ((command-finish
                               (command-end position content-mode)))
                          (setq position
                                (if (> command-finish position)
                                    command-finish
                                  (1+ position)))))
                       ((eq character opening)
                        (setq depth (1+ depth)
                              position (1+ position)))
                       ((eq character closing)
                        (setq depth (1- depth)
                              position (1+ position))
                        (when (= depth 0)
                          (setq end position)))
                       (t
                        (setq position (1+ position))))))
                  (unless end
                    (push (cons start (1+ start)) warning-ranges))
                  end)))
             (named-command-end
              (command-start grouped)
              (let* ((name-start (+ command-start (if grouped 2 1)))
                     (name-end (clojure-prose--symbol-end name-start)))
                (if (not name-end)
                    (progn
                      (push (cons command-start
                                  (max (1+ command-start)
                                       (min (point-max) (1+ name-start))))
                            warning-ranges)
                      nil)
                  (push (cons name-start name-end) name-ranges)
                  (let ((position name-end)
                        done)
                    (while (not done)
                      (let ((argument-start
                             (clojure-prose--skip-reader-whitespace position)))
                        (pcase (char-after argument-start)
                          (?\[
                           (let ((end (delimited-end argument-start 'clojure)))
                             (if end
                                 (progn
                                   (push (cons argument-start end)
                                         clojure-ranges)
                                   (setq position end))
                               (setq position (point-max)
                                     done t))))
                          (?\{
                           (let ((end (delimited-end argument-start 'host)))
                             (if end
                                 (setq position end)
                               (setq position (point-max)
                                     done t))))
                          (_ (setq done t)))))
                    position))))
             (command-end
              (command-start enclosing-mode)
              (let ((payload-start (1+ command-start))
                    end
                    recognized)
                (if (< payload-start (point-max))
                    (pcase (char-after payload-start)
                      (?\"
                       (let ((quoted-end
                              (clojure-prose--quoted-end payload-start)))
                         (unless quoted-end
                           (push (cons payload-start (1+ payload-start))
                                 warning-ranges))
                         (setq end (or quoted-end (point-max))
                               recognized (and quoted-end t))))
                      (?\(
                       (let ((delimiter-end
                              (delimited-end payload-start 'clojure)))
                         (when delimiter-end
                           (push (cons payload-start delimiter-end)
                                 clojure-ranges))
                         (setq end (or delimiter-end (point-max))
                               recognized (and delimiter-end t))))
                      (?|
                       (let* ((symbol-start (1+ payload-start))
                              (symbol-end
                               (clojure-prose--symbol-end symbol-start)))
                         (if symbol-end
                             (push (cons symbol-start symbol-end)
                                   clojure-ranges)
                           (push (cons command-start
                                       (min (point-max) (1+ symbol-start)))
                                 warning-ranges))
                         (setq end (or symbol-end
                                       (min (point-max) (1+ payload-start)))
                               recognized (and symbol-end t))))
                      (?◊
                       (let ((named-end
                              (named-command-end command-start t)))
                         (setq end (or named-end
                                       (min (point-max) (+ command-start 2)))
                               recognized (and named-end t))))
                      (_
                       (let ((named-end
                              (named-command-end command-start nil)))
                         (setq end (or named-end (1+ command-start))
                               recognized (and named-end t)))))
                  (push (cons command-start (1+ command-start))
                        warning-ranges))
                (setq end (or end (min (point-max) (1+ command-start))))
                (when (and recognized (eq enclosing-mode 'clojure))
                  (push (cons command-start end) host-ranges))
                end)))
          (goto-char (point-min))
          (while (search-forward "◊" nil t)
            (goto-char (command-end (1- (point)) 'host))))))
    (let* ((by-start
            (lambda (left right)
              (or (< (car left) (car right))
                  (and (= (car left) (car right))
                       (> (cdr left) (cdr right))))))
           (clojure-ranges (sort clojure-ranges by-start))
           (host-ranges (sort host-ranges by-start)))
      (list :clojure (clojure-prose--outermost-ranges clojure-ranges)
            :host (clojure-prose--effective-host-ranges
                   clojure-ranges host-ranges)
            :names (sort name-ranges by-start)
            :warnings (sort (delete-dups warning-ranges) by-start)))))

(defun clojure-prose--scan ()
  "Return current recursive Prose regions and local warnings.

Reuse the base buffer's scan until its characters change so Polymode and
font-lock matchers do not repeatedly rescan the same document."
  (let ((base (or (buffer-base-buffer) (current-buffer))))
    (with-current-buffer base
      (let ((tick (buffer-chars-modified-tick)))
        (unless (equal tick clojure-prose--scan-cache-tick)
          (setq clojure-prose--scan-cache (clojure-prose--scan-buffer)
                clojure-prose--scan-cache-tick tick))
        clojure-prose--scan-cache))))

(defun clojure-prose--range-at-or-after (ranges position)
  "Return the first of ordered RANGES starting at or after POSITION."
  (catch 'found
    (dolist (range ranges)
      (when (>= (car range) position)
        (throw 'found range)))))

(defun clojure-prose--range-at-or-before (ranges position)
  "Return the last of ordered RANGES starting at or before POSITION."
  (let (previous)
    (dolist (range ranges previous)
      (when (<= (car range) position)
        (setq previous range)))))

(defun clojure-prose--range-head-matcher (kind ahead)
  "Match the next recursive range of KIND in direction AHEAD."
  (let* ((origin (point))
         (ranges (plist-get (clojure-prose--scan) kind))
         (match (if (< ahead 0)
                    (clojure-prose--range-at-or-before ranges origin)
                  (clojure-prose--range-at-or-after ranges origin))))
    (when match
      (goto-char (if (< ahead 0)
                     (car match)
                   (min (point-max) (1+ (car match)))))
      (cons (car match) (car match)))))

(defun clojure-prose--range-tail-matcher (kind)
  "Match the end of the recursive range of KIND beginning at point."
  (let* ((ranges (plist-get (clojure-prose--scan) kind))
         (match (clojure-prose--range-at-or-after ranges (point))))
    (when match
      (goto-char (cdr match))
      (cons (cdr match) (cdr match)))))

(defun clojure-prose--clojure-head-matcher (ahead)
  "Match the next embedded Clojure range in direction AHEAD."
  (clojure-prose--range-head-matcher :clojure ahead))

(defun clojure-prose--clojure-tail-matcher (_ahead)
  "Match the embedded Clojure range end beginning at point."
  (clojure-prose--range-tail-matcher :clojure))

(defun clojure-prose--host-head-matcher (ahead)
  "Match the next recursively nested host range in direction AHEAD."
  (clojure-prose--range-head-matcher :host ahead))

(defun clojure-prose--host-tail-matcher (_ahead)
  "Match the recursively nested host range end beginning at point."
  (clojure-prose--range-tail-matcher :host))

(defun clojure-prose--font-lock-range-matcher (kind limit)
  "Find the next recursive range of KIND before LIMIT."
  (let* ((ranges (plist-get (clojure-prose--scan) kind))
         (match (clojure-prose--range-at-or-after ranges (point))))
    (when (and match (< (car match) limit))
      (goto-char (cdr match))
      (set-match-data (list (car match) (cdr match)))
      t)))

(defun clojure-prose--font-lock-command-matcher (limit)
  "Find the next recursive command name before LIMIT."
  (clojure-prose--font-lock-range-matcher :names limit))

(defun clojure-prose--font-lock-warning-matcher (limit)
  "Find the next locally detectable malformed construct before LIMIT."
  (clojure-prose--font-lock-range-matcher :warnings limit))

(defconst clojure-prose--font-lock-keywords
  '((clojure-prose--font-lock-command-matcher
     0 font-lock-function-name-face t)
    (clojure-prose--font-lock-warning-matcher
     0 font-lock-warning-face t))
  "Font-lock rules added to every Prose chunk.")

(defun clojure-prose--font-lock-setup ()
  "Compose Prose command highlighting with the current chunk mode."
  (font-lock-add-keywords nil clojure-prose--font-lock-keywords 'append))

;;;###autoload
(defun clojure-prose-insert-lozenge ()
  "Insert a lozenge at point."
  (interactive)
  (insert "◊"))

(defun clojure-prose--electric-lozenge ()
  "Insert a lozenge, or turn an immediately preceding insertion into `@'."
  (interactive)
  (if (and (eq last-command 'clojure-prose--electric-lozenge)
           (eq (char-before) ?◊))
      (progn
        (delete-char -1)
        (insert "@"))
    (clojure-prose-insert-lozenge)))

(defun clojure-prose--host-file-name ()
  "Return the current filename without its final `.prose' suffix."
  (when (and buffer-file-name
             (string-match "\\.prose\\'" buffer-file-name))
    (substring buffer-file-name 0 (match-beginning 0))))

(defun clojure-prose--activate-associated-host-mode ()
  "Activate the normal mode association for the current Prose host."
  (let ((host-file-name (clojure-prose--host-file-name))
        host-auto-mode-alist
        activated)
    (dolist (entry auto-mode-alist)
      (unless (eq (if (consp (cdr entry))
                      (cadr entry)
                    (cdr entry))
                  'clojure-prose-mode)
        (push entry host-auto-mode-alist)))
    (setq host-auto-mode-alist (nreverse host-auto-mode-alist))
    (fundamental-mode)
    (when host-file-name
      (condition-case nil
          (let ((buffer-file-name host-file-name)
                (auto-mode-alist host-auto-mode-alist)
                (enable-local-variables nil)
                (interpreter-mode-alist nil)
                (magic-mode-alist nil)
                (magic-fallback-mode-alist nil))
            (set-auto-mode)
            (setq activated (not (eq major-mode 'fundamental-mode))))
        (error nil)))
    (unless activated
      (text-mode))))

(defvar-local clojure-prose--resolved-host-mode 'text-mode
  "Major mode selected for host chunks in the current Prose buffer.")

(defun clojure-prose--host-chunk-mode ()
  "Activate the selected host mode in a nested host chunk."
  (let* ((base (or (buffer-base-buffer) (current-buffer)))
         (host-mode
          (buffer-local-value 'clojure-prose--resolved-host-mode base)))
    (if (fboundp host-mode)
        (funcall host-mode)
      (text-mode))))

(define-innermode clojure-prose-clojure-innermode
  :mode nil
  :fallback-mode 'clojure-mode
  :head-matcher #'clojure-prose--clojure-head-matcher
  :tail-matcher #'clojure-prose--clojure-tail-matcher
  :head-mode 'body
  :tail-mode 'body
  :allow-nested t
  :can-nest t
  :adjust-face nil
  :head-adjust-face nil)

(define-innermode clojure-prose-host-innermode
  :mode 'clojure-prose--host-chunk-mode
  :fallback-mode 'text-mode
  :head-matcher #'clojure-prose--host-head-matcher
  :tail-matcher #'clojure-prose--host-tail-matcher
  :head-mode 'body
  :tail-mode 'body
  :allow-nested t
  :can-nest t
  :adjust-face nil
  :head-adjust-face nil)

(defvar-local clojure-prose-mode nil
  "Non-nil when Clojure Prose Mode is active.")

(defvar clojure-prose-mode-hook nil
  "Hook run after Clojure Prose Mode initializes a chunk buffer.")

(define-polymode clojure-prose--polymode
  :innermodes '(clojure-prose-clojure-innermode
                clojure-prose-host-innermode)
  ;; simplification: rescan until Polymode's cache preserves nested ownership.
  (setq-local pm-use-cache nil)
  (setq-local clojure-prose-mode t)
  (clojure-prose--font-lock-setup)
  (run-hooks 'clojure-prose-mode-hook))

(declare-function clojure-prose--polymode "clojure-prose-mode"
                  (&optional arg))

(defvaralias 'clojure-prose-mode-map 'clojure-prose--polymode-map)

;;;###autoload
(defun clojure-prose-mode ()
  "Edit a Prose document using its filename-selected host mode."
  (interactive)
  (let ((host-mode clojure-prose-host-mode)
        (embedded-mode clojure-prose-embedded-mode))
    (when (bound-and-true-p clojure-prose--polymode)
      (clojure-prose--polymode -1))
    (cond
     ((and host-mode (fboundp host-mode))
      (funcall host-mode))
     (host-mode
      (text-mode))
     (t
      (clojure-prose--activate-associated-host-mode)))
    (setq-local clojure-prose--resolved-host-mode major-mode)
    (setq-local clojure-prose-host-mode host-mode)
    (setq-local clojure-prose-embedded-mode embedded-mode)
    (setq-local polymode-default-inner-mode
                (if (fboundp embedded-mode)
                    embedded-mode
                  'clojure-mode))
    (clojure-prose--polymode 1)))

;;;###autoload
(defun clojure-prose--prefer-file-mode-association
    (original &optional keep-mode-if-same)
  "Run ORIGINAL with KEEP-MODE-IF-SAME, preferring Prose associations."
  (if (and buffer-file-name
           (eq (assoc-default buffer-file-name auto-mode-alist
                              #'string-match)
               'clojure-prose-mode))
      (clojure-prose-mode)
    (funcall original keep-mode-if-same)))

(define-key clojure-prose-mode-map (kbd "@")
  #'clojure-prose--electric-lozenge)

;;;###autoload
(add-to-list 'auto-mode-alist '("\\.prose\\'" . clojure-prose-mode))

;;;###autoload
(unless (advice-member-p #'clojure-prose--prefer-file-mode-association
                         'set-auto-mode)
  (advice-add 'set-auto-mode :around
              #'clojure-prose--prefer-file-mode-association))

(defun clojure-prose-mode-unload-function ()
  "Remove Clojure Prose Mode's file-association precedence advice."
  (advice-remove 'set-auto-mode
                 #'clojure-prose--prefer-file-mode-association)
  nil)

(provide 'clojure-prose-mode)

;;; clojure-prose-mode.el ends here
