{
  description = "faraday — one control plane, one gate, one console (jolt devShell)";

  inputs = {
    nixpkgs.follows = "jolt/nixpkgs";
    jolt.url = "git+https://github.com/jolt-lang/jolt.git?submodules=1";
  };

  outputs =
    { self, nixpkgs, jolt }:
    let
      # Match jolt-lang's own systems: its flake builds jolt only for these.
      systems = [
        "x86_64-linux"
        "aarch64-darwin"
      ];
      forAllSystems =
        f: nixpkgs.lib.genAttrs systems (system: f system nixpkgs.legacyPackages.${system});

      # jolt-lang's flake bakes self.shortRev as the version, so a nix-built
      # jolt reports e.g. "7cf200f". jolt.deps reads a version's leading
      # numeric prefix, so a bare sha reads as a MAJOR number (7cf200f -> 7)
      # and a sha starting with 0 would fail every :jolt/min-version floor
      # (0a1b2c3 -> 0). A dev-build version with no leading number
      # ("dev-g<sha>") carries the sha but applies no floor, matching
      # tools/version.sh's no-tag checkout spelling.
      jolt-bin =
        system:
        jolt.packages.${system}.jolt.overrideAttrs (old: {
          __intentionallyOverridingVersion = true;
          version = "dev-g${jolt.shortRev}";
          JOLT_VERSION = "dev-g${jolt.shortRev}";
        });
    in
    {
      # `nix build .#jolt` / `nix run .#jolt` — the jolt toolchain faraday runs on.
      packages = forAllSystems (
        system: _pkgs:
        let
          j = jolt-bin system;
        in
        {
          jolt = j;
          default = j;
        }
      );

      # `nix develop` — the faraday dev toolchain. jolt is the language/toolchain
      # (faraday is Clojure on Chez, run via `jolt -M:...`); git + openssl back
      # jolt's deps.edn git/https fetches; openssh provides ssh/ssh-keygen for the
      # jolt-sandbox microVM; ripgrep + clj-kondo + jq for search, lint, scripting.
      devShells = forAllSystems (
        system: pkgs:
        let
          # Native shared objects faraday's tree dlopens by bare soname at
          # runtime: sqlite3 (jolt-lang/db), z + zstd (ring-chez-adapter),
          # ncursesw (glimmer-tui). A nix-built jolt cannot open the host's
          # copies (host glibc vs nix glibc), so these are nix-provided and
          # exported on LD_LIBRARY_PATH — which also lands in the sandbox's
          # dev-env.sh when a microVM layers this shell in.
          native-libs = [
            pkgs.sqlite
            pkgs.zlib
            pkgs.zstd
            pkgs.ncurses
            pkgs.openssl
          ];
        in
        {
          default = pkgs.mkShell {
            packages = [
              (jolt-bin system)
              pkgs.ripgrep
              pkgs.git
              pkgs.openssl
              pkgs.openssh
              pkgs.clj-kondo
              pkgs.jq
            ] ++ native-libs;

            shellHook = ''
              export LD_LIBRARY_PATH=${pkgs.lib.makeLibraryPath native-libs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
              echo "faraday dev env — jolt, ripgrep, sandbox tooling"
            '';
          };
        }
      );

      formatter = forAllSystems (system: _pkgs: nixpkgs.legacyPackages.${system}.nixfmt);
    };
}
