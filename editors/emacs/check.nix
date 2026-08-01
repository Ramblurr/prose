{ pkgs }:

let
  emacs = (pkgs.emacsPackagesFor pkgs.emacs).emacsWithPackages (epkgs: [
    epkgs.clojure-mode
    epkgs.polymode
  ]);
in
pkgs.stdenvNoCC.mkDerivation {
  pname = "clojure-prose-mode-check";
  version = "0.1.0";
  src = ./.;

  nativeBuildInputs = [
    pkgs.babashka
    emacs
  ];

  dontConfigure = true;

  buildPhase = ''
    runHook preBuild
    export HOME="$TMPDIR/home"
    mkdir -p "$HOME"
    bb clean
    bb compile
    runHook postBuild
  '';

  doCheck = true;
  checkPhase = ''
    runHook preCheck
    bb test
    runHook postCheck
  '';

  installPhase = ''
    runHook preInstall
    install -Dm644 clojure-prose-mode.el "$out/share/emacs/site-lisp/clojure-prose-mode.el"
    install -Dm644 clojure-prose-mode.elc "$out/share/emacs/site-lisp/clojure-prose-mode.elc"
    runHook postInstall
  '';
}
