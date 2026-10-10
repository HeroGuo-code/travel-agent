#!/bin/bash
# ============================================================
# 智能旅游助手（AgentScope 2.0）一键部署脚本
# 用法：把 install.sh 和 jar 包放在同一目录，执行 bash install.sh
# 功能：部署 jar -> 写环境变量 -> 注册 systemd -> 配置 Nginx -> 健康检查
# ============================================================
set -e

# ---------------- 配置区 ----------------
APP_NAME=aligo-travel
APP_DIR=/opt/aligo-travel
JAR_NAME=aligo-travel-agent-1.0.0.jar
DOMAIN=travel.guohaoai.com
PORT=8085
NGINX_CONF_DIR=/etc/nginx/conf.d
OPENROUTER_API_KEY="YOUR_OPENROUTER_API_KEY_HERE"
# ----------------------------------------

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo ">>> [1/6] 部署目录与 jar"
mkdir -p "$APP_DIR"
if [ -f "$SCRIPT_DIR/$JAR_NAME" ]; then
    cp "$SCRIPT_DIR/$JAR_NAME" "$APP_DIR/$JAR_NAME"
else
    echo "  当前目录未找到 $JAR_NAME（若已部署到 $APP_DIR 可忽略）"
fi
[ -f "$APP_DIR/$JAR_NAME" ] || { echo "  错误：$APP_DIR/$JAR_NAME 不存在"; exit 1; }

echo ">>> [2/6] 检测 Java"
JAVA_BIN=""
if command -v java >/dev/null 2>&1; then
    JAVA_BIN=$(command -v java)
elif [ -f /etc/systemd/system/yuaicode.service ]; then
    # 从已运行的零代码平台服务中提取 java 路径
    JAVA_BIN=$(grep '^ExecStart' /etc/systemd/system/yuaicode.service | head -1 | sed 's/^ExecStart=//' | awk '{print $1}')
fi
if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
    for p in /usr/lib/jvm/*/bin/java /usr/local/java/*/bin/java /www/server/java/*/bin/java; do
        [ -x "$p" ] && JAVA_BIN="$p" && break
    done
fi
[ -n "$JAVA_BIN" ] || { echo "  错误：未找到 Java，请先安装 JDK 17+"; exit 1; }
echo "  使用 Java: $JAVA_BIN"
"$JAVA_BIN" -version 2>&1 | head -1

echo ">>> [3/6] 写入环境变量文件（含 API Key）"
cat > "$APP_DIR/aligo.env" <<EOF
SERVER_PORT=$PORT
OPENROUTER_API_KEY=$OPENROUTER_API_KEY
EOF
chmod 600 "$APP_DIR/aligo.env"

echo ">>> [4/6] 注册 systemd 服务（开机自启 + 崩溃自动重启）"
cat > /etc/systemd/system/${APP_NAME}.service <<EOF
[Unit]
Description=Aligo Travel Agent (AgentScope 2.0)
After=network.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/aligo.env
ExecStart=$JAVA_BIN -Djava.net.preferIPv4Stack=true -jar $APP_DIR/$JAR_NAME
Restart=always
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable "$APP_NAME"
systemctl restart "$APP_NAME"

echo ">>> [5/6] 写入 Nginx 站点配置（SSE 三件套：关缓冲/长超时/HTTP1.1）"
mkdir -p "$NGINX_CONF_DIR"
cat > "$NGINX_CONF_DIR/${DOMAIN}.conf" <<EOF
server {
    listen 80;
    server_name $DOMAIN;

    # SSE 流式接口：必须关缓冲，否则流式输出被攒包、Cloudflare 100s 超时
    location /api/ {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    # 静态页面与其它请求
    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF
nginx -t
nginx -s reload

echo ">>> [6/6] 等待应用启动并做健康检查"
sleep 8
systemctl --no-pager status "$APP_NAME" | head -12 || true
echo
if curl -s -m 5 "http://127.0.0.1:$PORT/api/health" | grep -q .; then
    echo "======================================================"
    echo " 部署完成！应用运行于 127.0.0.1:$PORT"
    echo " 剩余步骤：Cloudflare 添加 A 记录 $DOMAIN -> 43.155.144.174（开启代理）"
    echo "======================================================"
else
    echo " 健康检查未响应，查看日志排查：journalctl -u $APP_NAME -n 50 --no-pager"
fi
