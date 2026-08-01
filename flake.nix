{
  description = "Prose development environment";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    devshell.url = "github:numtide/devshell";
    devshell.inputs.nixpkgs.follows = "nixpkgs";
    devenv.url = "https://flakehub.com/f/ramblurr/nix-devenv/*";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
    clj-helpers.url = "github:outskirtslabs/clojure-nix-locker-helpers";
    clj-helpers.inputs.nixpkgs.follows = "nixpkgs";
  };
  outputs =
    inputs@{
      self,
      devenv,
      devshell,
      clj-helpers,
      ...
    }:
    let
      package =
        pkgs:
        let
          jdk = pkgs.jdk25;
          clojure = pkgs.clojure.override { inherit jdk; };
        in
        clj-helpers.lib.mkCljLib {
          inherit jdk pkgs;
          name = "prose";
          version = "0.0.0";
          src = ./.;
          buildCommand = "clojure -Srepro -T:package jar";
          prepAliases = [
            "package"
            "clj"
            "cljs"
            "test"
          ];
          prefetchAliases = [ "clj:cljs:test" ];
          extraPrepInputs = [
            pkgs.babashka
            pkgs.git
          ];
          lockCommand = ''
            export HOME="$tmp/home"
            export PATH="${clojure}/bin:${jdk}/bin:$PATH"
            unset CLJ_CACHE CLJ_CONFIG XDG_CACHE_HOME XDG_CONFIG_HOME XDG_DATA_HOME

            clojure -Srepro -X:deps prep :aliases '[:package :clj :cljs :test]'
            clojure -Srepro -P -M:clj:cljs:test
            (cd playground && clojure -Srepro -P -M:test)
            bb -Sdeps '{:deps {io.github.jerems/prose {:local/root "."}}}' -e nil
            clojure -Srepro -T:package jar
          '';
          checkCommand = ''
            bb test:clj
            bb test:cljs
            mkdir -p "$TMPDIR/bb-clj-config"
            CLJ_CONFIG="$TMPDIR/bb-clj-config" bb test:bb
            bb playground:check
          '';
          gitRev = clj-helpers.lib.gitRev self;
          nativeBuildInputs = [
            pkgs.babashka
            pkgs.nodejs_22
            pkgs.pnpm_11
            pkgs.pnpmConfigHook
            pkgs.util-linux
          ];
          pnpmDeps = pkgs.fetchPnpmDeps {
            pname = "prose-playground";
            version = "0.0.0";
            src = ./playground;
            pnpm = pkgs.pnpm_11;
            fetcherVersion = 4;
            hash = "sha256-srcw3F/QmX9uR7DeiJZuZQoSs1+c9LbqJvbvZ+QHdcE=";
          };
          pnpmRoot = "playground";
          postPatch = "patchShebangs playground/scripts";
        };
    in
    devenv.lib.mkFlake ./. {
      inherit inputs;
      withOverlays = [
        devshell.overlays.default
        devenv.overlays.default
      ];
      packages = {
        default = package;
        # regenerates ./deps-lock.json: `nix run .#locker`
        locker = pkgs: (package pkgs).locker;
      };
      devShell =
        pkgs:
        pkgs.devshell.mkShell {
          imports = [
            devenv.capsules.base
            devenv.capsules.clojure
          ];
          # https://numtide.github.io/devshell
          commands = [ ];
          packages = [
            (
              if self ? packages then
                self.packages.${pkgs.system}.locker
              else
                clj-helpers.packages.${pkgs.system}.deps-lock
            )
            pkgs.nodejs_22
            pkgs.pnpm_11
            pkgs.util-linux
          ];
        };
    };
}
