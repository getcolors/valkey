{ pkgs, ... }:
{
  languages.clojure.enable = true;
  languages.opentofu.enable = true;
  packages = with pkgs; [ ansible babashka curl jq openssh openssl rclone valkey python3 bash coreutils git ];
}
