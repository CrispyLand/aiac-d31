#!/usr/bin/env bash
# Deploy all apps to the Hetzner VPS.
# Run from the repository root: ./deploy.sh
# Prerequisites (one-time, on the VPS):
#   echo 'CEREBRAS_API_KEY=csk-...' | ssh russell@2.28.124.111 'install -m 600 /dev/stdin /opt/aiac/.env'

set -euo pipefail

VPS="russell@2.28.124.111"
ROOT="$(cd "$(dirname "$0")" && pwd)"

# ── SSH multiplexing ──────────────────────────────────────────────────────────
# All ssh/scp/rsync calls share one master connection so rapid successive
# commands don't hit the server's MaxStartups limit.
SSH_CTRL="/tmp/deploy-ssh-$$"
ssh -o ControlMaster=yes -o ControlPath="$SSH_CTRL" -o ControlPersist=120 -fN "$VPS"
trap "ssh -O exit -o ControlPath='$SSH_CTRL' '$VPS' 2>/dev/null || true" EXIT

vssh()  { ssh  -o ControlPath="$SSH_CTRL" "$@"; }
vscp()  { scp  -o ControlPath="$SSH_CTRL" "$@"; }
vrsync(){ rsync -e "ssh -o ControlPath=$SSH_CTRL" "$@"; }

# ── 1. Build ──────────────────────────────────────────────────────────────────
echo "==> Building agent..."
cd "$ROOT"
mvn -q clean package -DskipTests

echo "==> Building MCP server..."
cd "$ROOT/mcp-server"
mvn -q package -DskipTests
cd "$ROOT"

echo "==> Building weather server..."
cd "$ROOT/weather-server"
mvn -q package -DskipTests
cd "$ROOT"

AGENT_JAR="$ROOT/target/agent-chat-0.0.1-SNAPSHOT.jar"
MCP_JAR="$ROOT/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar"
WEATHER_JAR="$ROOT/weather-server/target/weather-server-0.0.1-SNAPSHOT.jar"

# ── 2. Provision directories (idempotent) ─────────────────────────────────────
echo "==> Creating directories on VPS..."
vssh "$VPS" "mkdir -p /opt/aiac/mcp/data /opt/aiac/agent/data /opt/aiac/agent/profiles /opt/aiac/notes /opt/aiac/weather"

# ── 2b. Sync git repo for MCP git tools ───────────────────────────────────────
echo "==> Syncing git repo to VPS..."
vrsync -a --delete --exclude='target/' --exclude='.idea/' --exclude='*.jar' \
    --exclude='.google-tokens/' --exclude='data/' \
    "$ROOT/" "$VPS:/opt/aiac/repo/"

# ── 3. Install nginx (idempotent) ─────────────────────────────────────────────
echo "==> Ensuring nginx is installed..."
vssh "$VPS" "which nginx > /dev/null 2>&1 || sudo apt-get install -y nginx"

# ── 4. Upload jars ────────────────────────────────────────────────────────────
echo "==> Uploading agent jar ($(du -h "$AGENT_JAR" | cut -f1))..."
vscp "$AGENT_JAR" "$VPS:/opt/aiac/agent/agent.jar"

echo "==> Uploading MCP server jar ($(du -h "$MCP_JAR" | cut -f1))..."
vscp "$MCP_JAR" "$VPS:/opt/aiac/mcp/mcp-server.jar"

echo "==> Uploading weather server jar ($(du -h "$WEATHER_JAR" | cut -f1))..."
vscp "$WEATHER_JAR" "$VPS:/opt/aiac/weather/weather-server.jar"

# ── 5. Upload Google credentials ──────────────────────────────────────────────
echo "==> Uploading credentials.json..."
vscp "$ROOT/credentials.json" "$VPS:/opt/aiac/credentials.json"

echo "==> Syncing Google tokens (preserving permissions)..."
vrsync -a "$ROOT/.google-tokens/" "$VPS:/opt/aiac/.google-tokens/"

# ── 6. Upload profiles ────────────────────────────────────────────────────────
if [ -d "$ROOT/profiles" ] && [ "$(ls -A "$ROOT/profiles")" ]; then
    echo "==> Syncing profiles..."
    vrsync -a "$ROOT/profiles/" "$VPS:/opt/aiac/agent/profiles/"
fi

# ── 7. Install systemd units ──────────────────────────────────────────────────
echo "==> Installing systemd units..."
vscp "$ROOT/deploy/aiac-mcp.service"     "$VPS:/tmp/aiac-mcp.service"
vscp "$ROOT/deploy/aiac-weather.service" "$VPS:/tmp/aiac-weather.service"
vscp "$ROOT/deploy/aiac-agent.service"   "$VPS:/tmp/aiac-agent.service"
vssh "$VPS" "
    sudo mv /tmp/aiac-mcp.service     /etc/systemd/system/aiac-mcp.service
    sudo mv /tmp/aiac-weather.service /etc/systemd/system/aiac-weather.service
    sudo mv /tmp/aiac-agent.service   /etc/systemd/system/aiac-agent.service
    sudo systemctl daemon-reload
    sudo systemctl enable aiac-mcp aiac-weather aiac-agent
"

# ── 8. Install nginx config ───────────────────────────────────────────────────
echo "==> Installing nginx config..."
vscp "$ROOT/deploy/nginx-aiac.conf" "$VPS:/tmp/nginx-aiac.conf"
vssh "$VPS" "
    sudo mv /tmp/nginx-aiac.conf /etc/nginx/sites-available/aiac
    sudo ln -sf /etc/nginx/sites-available/aiac /etc/nginx/sites-enabled/aiac
    sudo rm -f /etc/nginx/sites-enabled/default
    sudo nginx -t
    sudo systemctl reload nginx
"

# ── 9. Restart services (MCP first — agent's MCP client does not reconnect) ───
echo "==> Restarting MCP server..."
vssh "$VPS" "sudo systemctl restart aiac-mcp"

echo "==> Waiting for MCP server to open its port..."
vssh "$VPS" "
    for i in \$(seq 1 20); do
        ss -tlnp | grep -q ':8081' && echo 'Port 8081 is open.' && break
        echo \"  attempt \$i/20...\"
        sleep 2
    done
"

echo "==> Restarting weather server..."
vssh "$VPS" "sudo systemctl restart aiac-weather"

echo "==> Waiting for weather server to open its port..."
vssh "$VPS" "
    for i in \$(seq 1 20); do
        ss -tlnp | grep -q ':8082' && echo 'Port 8082 is open.' && break
        echo \"  attempt \$i/20...\"
        sleep 2
    done
"

echo "==> Restarting agent..."
vssh "$VPS" "sudo systemctl restart aiac-agent"

echo "==> Waiting for agent to be ready..."
for i in $(seq 1 20); do
    if curl -s -o /dev/null -w '%{http_code}' http://2.28.124.111/ 2>/dev/null | grep -q 200; then
        echo "Agent is up."
        break
    fi
    echo "  attempt $i/20..."
    sleep 3
done

# ── 10. Reindex RAG (runs in background on the server) ────────────────────────
echo "==> Triggering RAG reindex..."
curl -s -X POST http://2.28.124.111/indexer/reindex -o /dev/null -w 'HTTP %{http_code}\n'

# ── 12. Status ────────────────────────────────────────────────────────────────
echo ""
echo "==> Done. Services:"
vssh "$VPS" "sudo systemctl status aiac-mcp aiac-weather aiac-agent --no-pager -l | grep -E 'Loaded|Active|Main PID'"
echo ""
echo "Logs:  ssh $VPS 'journalctl -u aiac-mcp -u aiac-weather -u aiac-agent -f'"
echo "Agent: http://2.28.124.111"
