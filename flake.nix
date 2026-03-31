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
          buildPhase = "chmod +x podhome_mcp.clj";
          installPhase = ''
            mkdir -p $out/bin $out/share/podhome-mcp
            cp podhome_mcp.clj $out/share/podhome-mcp/
            cat > $out/bin/podhome-mcp << 'EOF'
            #!/usr/bin/env bash
            exec ${bb}/bin/bb ${placeholder "out"}/share/podhome-mcp/podhome_mcp.clj "$@"
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
          buildInputs = [bb pkgs.clj-kondo pkgs.jq];
          shellHook = ''
            echo "podhome-mcp dev shell"
            echo "  bb run      — start server (OS-assigned port)"
            echo "  bb start    — start server on port 9999"
            echo "  bb test     — run tests"
            echo "  bb lint     — clj-kondo lint"
            echo ""
            echo "Auth: set PODHOME_API_KEY"
            echo "      or write ~/.config/podhome/config.edn"
          '';
        };
      }
    )
    // {
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }: let
        cfg = config.services.podhome-mcp;
        bb = pkgs.babashka;
        podhome-mcp-pkg = pkgs.stdenv.mkDerivation {
          name = "podhome-mcp";
          src = ./.;
          nativeBuildInputs = [bb];
          unpackPhase = "cp -r $src/* ./";
          buildPhase = "chmod +x podhome_mcp.clj";
          installPhase = ''
            mkdir -p $out/bin $out/share/podhome-mcp
            cp podhome_mcp.clj $out/share/podhome-mcp/
            cat > $out/bin/podhome-mcp << EOF
            #!/usr/bin/env bash
            exec ${bb}/bin/bb $out/share/podhome-mcp/podhome_mcp.clj "\$@"
            EOF
            chmod +x $out/bin/podhome-mcp
          '';
        };
      in {
        options.services.podhome-mcp = {
          enable = lib.mkEnableOption "Podhome MCP Server";

          port = lib.mkOption {
            type = lib.types.int;
            default = 3003;
            description = "Port for the Podhome MCP server to listen on.";
          };

          host = lib.mkOption {
            type = lib.types.str;
            default = "127.0.0.1";
            description = "Host address to bind to.";
          };

          openFirewall = lib.mkOption {
            type = lib.types.bool;
            default = false;
            description = "Open the firewall for the MCP server port.";
          };

          logLevel = lib.mkOption {
            type = lib.types.enum ["debug" "info" "warn" "error"];
            default = "info";
            description = "Logging level.";
          };

          apiKeyFile = lib.mkOption {
            type = lib.types.path;
            description = ''
              Path to a file containing the Podhome API key in the format:
                PODHOME_API_KEY=your-key
              Keep this file outside the Nix store (e.g. /run/secrets/podhome).
            '';
          };

          user = lib.mkOption {
            type = lib.types.str;
            default = "podhome-mcp";
            description = "User to run the service as.";
          };

          group = lib.mkOption {
            type = lib.types.str;
            default = "podhome-mcp";
            description = "Group to run the service as.";
          };
        };

        config = lib.mkIf cfg.enable {
          networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [cfg.port];

          users.users.${cfg.user} = {
            isSystemUser = true;
            group = cfg.group;
            description = "Podhome MCP Server";
          };
          users.groups.${cfg.group} = {};

          systemd.services.podhome-mcp = {
            description = "Podhome MCP Server";
            wantedBy = ["multi-user.target"];
            after = ["network.target"];

            serviceConfig = {
              Type = "simple";
              User = cfg.user;
              Group = cfg.group;
              ExecStart = "${podhome-mcp-pkg}/bin/podhome-mcp";
              Restart = "on-failure";
              RestartSec = "5s";

              EnvironmentFile = cfg.apiKeyFile;
              Environment = [
                "PODHOME_MCP_PORT=${toString cfg.port}"
                "PODHOME_MCP_HOST=${cfg.host}"
                "PODHOME_LOG_LEVEL=${cfg.logLevel}"
              ];

              # Hardening
              NoNewPrivileges = true;
              PrivateTmp = true;
              ProtectSystem = "strict";
              ProtectHome = "read-only";
            };
          };
        };
      };
    };
}
