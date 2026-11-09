#!/bin/sh
# Runs once inside the Alpine guest (under proot) on first launch.
echo "nameserver 1.1.1.1"  > /etc/resolv.conf
echo "nameserver 8.8.8.8" >> /etc/resolv.conf
echo "== [1/3] Installing Alpine packages"
apk update && apk add --no-cache bash git curl ca-certificates libgcc libstdc++ ripgrep nodejs npm openssh-client nano \
  || { echo "!! apk failed. Check your connection, then Menu > Re-run setup."; exit 1; }
mkdir -p /root/work /etc/profile.d
cat > /etc/profile.d/claude.sh <<'EOS'
export PATH="$HOME/.local/bin:$PATH"
export USE_BUILTIN_RIPGREP=0
EOS
export USE_BUILTIN_RIPGREP=0
export PATH="$HOME/.local/bin:$PATH"
echo "== [2/3] Installing Claude Code (native installer)"
curl -fsSL https://claude.ai/install.sh | bash || echo "native installer failed"
if ! command -v claude >/dev/null 2>&1; then
  echo "== [2/3] Falling back to npm"
  npm install -g --no-audit --no-fund @anthropic-ai/claude-code || { echo "!! Claude Code install failed"; exit 1; }
fi
echo "== [3/3] Verifying"
claude --version && touch /root/.bootstrapped && echo "DONE - Claude Code is ready."
