{
  description = "scalacheck-propertym: a monadic property API for ScalaCheck (cats-effect IO)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
    git-hooks = {
      url = "github:cachix/git-hooks.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    mdreflow = {
      url = "github:GeorgeFlerovsky/mdreflow/v0.3.0-fork.2";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      git-hooks,
      mdreflow,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.jdk21;
        # The nixpkgs sbt launcher reads project/build.properties and bootstraps the sbt version it
        # names (1.10.7 here), so no launcher pin is needed.
        sbt = pkgs.sbt.override { jre = jdk; };
        mdreflowPkg = mdreflow.packages.${system}.default;
        # One sentence per line in every Markdown file. The settings live in .mdreflow.yaml, so
        # running `mdreflow .` by hand does the same as the hook.
        pre-commit-check = git-hooks.lib.${system}.run {
          src = ./.;
          hooks.mdreflow = {
            enable = true;
            name = "mdreflow";
            entry = "${mdreflowPkg}/bin/mdreflow";
            files = "\\.(md|mdx|markdown)$";
          };
        };
      in
      {
        checks = { inherit pre-commit-check; };

        devShells.default = pkgs.mkShell {
          packages = [
            jdk
            sbt
            pkgs.scalafmt
            # One demo property shells out to `factor` (from coreutils).
            pkgs.coreutils
            mdreflowPkg
          ];
          inherit (pre-commit-check) shellHook;
        };
      }
    );
}
