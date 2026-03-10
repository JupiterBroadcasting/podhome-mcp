{
  description = "Podhome MCP Server — Babashka Streamable HTTP MCP server for the Podhome Integration API";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = nixpkgs.legacyPackages.${system};
        bb = pkgs.babashka;

        podhome-mcp = pkgs.stdenv.mkDerivation {
          name = "podhome-mcp";
          version = "0.1.0";
          src = ./.;

          nativeBuildInputs = [bb];

          unpackPhase = "cp -r $src/* ./";
          buildPhase = "chmod +x podhome_mcp.bb";
          installPhase = ''
            mkdir -p $out/bin $out/share/podhome-mcp
            cp podhome_mcp.bb $out/share/podhome-mcp/
            cat > $out/bin/podhome-mcp << 'EOF'
#!/usr/bin/env bash
exec ${bb}/bin/bb ${placeholder "out"}/share/podhome-mcp/podhome_mcp.bb "$@"
EOF
            chmod +x $out/bin/podhome-mcp
          '';

          meta = with pkgs.lib; {
            description = "Podhome MCP Server for Jupiter Broadcasting";
            homepage = "https://github.com/JupiterBroadcasting/podhome-mcp";
            license = licenses.mit;
            platforms = platforms.linux;
          };
        };
      in {
        formatter = pkgs.alejandra;
        packages = {
          default = podhome-mcp;
          inherit podhome-mcp;
        };

        devShells.default = pkgs.mkShell {
          buildInputs = [bb];
          shellHook = ''
            echo "podhome-mcp dev shell"
            echo "  bb run      — start server (OS-assigned port)"
            echo "  bb start    — start server on port 9999"
            echo "  bb test     — run tests"
            echo ""
            echo "Auth: set PODHOME_API_KEY"
            echo "      or write ~/.config/podhome/config.edn"
          '';
        };
      }
    )
}
