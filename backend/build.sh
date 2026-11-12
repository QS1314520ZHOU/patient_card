#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v mvn >/dev/null 2>&1; then
  echo "未找到 Maven。请先安装 Maven 3.8+，然后重新执行。" >&2
  exit 1
fi
mvn -DskipTests clean package
mkdir -p target/config
cp config/application.yml.example target/config/application.yml.example
cat > target/start.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$DIR/patient-card-0.0.9.jar" --spring.config.additional-location="file:$DIR/config/"
EOF
chmod +x target/start.sh
printf '\n构建完成。部署 target 目录，并将现有配置放入 target/config/application.yml：\n  %s\n' "$(pwd)/target"
