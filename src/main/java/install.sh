#!/usr/bin/env bash
# ============================================================
# install.sh — One-shot setup for VCF Duplicate Detector
#
# Run as root on your Xeon server:
#   chmod +x install.sh && sudo ./install.sh
#
# What this does:
#   1. Installs Java 17 JDK + Maven (if not present)
#   2. Builds the fat JAR (all dependencies bundled)
#   3. Runs all tests
#   4. Installs JAR + cron script to /opt/vcf-dedup
#   5. Schedules cron job at 2 AM daily
# ============================================================

set -euo pipefail

INSTALL_DIR="/opt/vcf-dedup"
CRON_TIME="0 2 * * *"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── 1. Check OS ───────────────────────────────────────────────────────────────
[[ "$(uname -s)" == "Linux" ]] || error "This script requires Linux"
command -v sort >/dev/null      || error "GNU sort not found — install coreutils"

# ── 2. Java ───────────────────────────────────────────────────────────────────
install_java() {
    info "Installing OpenJDK 17..."
    if command -v apt-get &>/dev/null; then
        apt-get update -qq
        apt-get install -y openjdk-17-jdk
    elif command -v yum &>/dev/null; then
        yum install -y java-17-openjdk-devel
    elif command -v dnf &>/dev/null; then
        dnf install -y java-17-openjdk-devel
    else
        error "Cannot install Java — no apt-get/yum/dnf found. Install OpenJDK 17 manually."
    fi
}

if ! command -v javac &>/dev/null; then
    install_java
else
    JAVA_VER=$(javac -version 2>&1 | grep -oP '\d+' | head -1)
    if [[ "$JAVA_VER" -lt 17 ]]; then
        warn "Java $JAVA_VER found, need 17+. Installing..."
        install_java
    else
        info "Java $JAVA_VER found — OK"
    fi
fi

# ── 3. Maven ──────────────────────────────────────────────────────────────────
if ! command -v mvn &>/dev/null; then
    info "Installing Maven..."
    if command -v apt-get &>/dev/null; then
        apt-get install -y maven
    elif command -v yum &>/dev/null; then
        yum install -y maven
    elif command -v dnf &>/dev/null; then
        dnf install -y maven
    else
        # Manual install as fallback
        MAVEN_VERSION="3.9.6"
        MAVEN_URL="https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
        info "Downloading Maven ${MAVEN_VERSION}..."
        cd /opt
        curl -fsSL -o maven.tar.gz "$MAVEN_URL"
        tar -xzf maven.tar.gz
        rm maven.tar.gz
        ln -sf "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn
        info "Maven installed to /opt/apache-maven-${MAVEN_VERSION}"
    fi
else
    info "Maven $(mvn -version 2>&1 | head -1) — OK"
fi

# ── 4. Build ──────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

info "Running tests..."
mvn test -q && info "All tests passed" || error "Tests failed — aborting install"

info "Building fat JAR..."
mvn package -DskipTests -q
JAR=$(ls target/vcf-dedup-*.jar | grep -v original | head -1)
[[ -f "$JAR" ]] || error "JAR not found after build"
info "Built: $JAR"

# ── 5. Install ────────────────────────────────────────────────────────────────
mkdir -p "$INSTALL_DIR"
cp "$JAR" "$INSTALL_DIR/vcf-dedup.jar"
cp vcf-dedup-cron.sh "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/vcf-dedup-cron.sh"

info "Installed to $INSTALL_DIR"
ls -lh "$INSTALL_DIR/"

# ── 6. Configure cron ─────────────────────────────────────────────────────────
echo ""
echo "────────────────────────────────────────────────────────"
echo "  Configure before scheduling:"
echo ""
echo "  Edit $INSTALL_DIR/vcf-dedup-cron.sh and set:"
echo "    SCAN_DIR   = root directory of your VCF files"
echo "    REPORT_DIR = where reports should be written"
echo ""
read -rp "  Set SCAN_DIR now? (enter path or press Enter to skip): " SCAN_DIR
read -rp "  Set REPORT_DIR now? (enter path or press Enter to skip): " REPORT_DIR

if [[ -n "$SCAN_DIR" && -n "$REPORT_DIR" ]]; then
    sed -i "s|SCAN_DIR=\"/data/vcf\"|SCAN_DIR=\"${SCAN_DIR}\"|" "$INSTALL_DIR/vcf-dedup-cron.sh"
    sed -i "s|REPORT_DIR=\"/data/vcf-dedup-reports\"|REPORT_DIR=\"${REPORT_DIR}\"|" "$INSTALL_DIR/vcf-dedup-cron.sh"
    info "Paths configured"
fi

# ── 7. Schedule ───────────────────────────────────────────────────────────────
read -rp "  Schedule cron job at 2 AM daily? [y/N]: " SCHEDULE
if [[ "${SCHEDULE,,}" == "y" ]]; then
    CRON_LINE="${CRON_TIME} ${INSTALL_DIR}/vcf-dedup-cron.sh >> /var/log/vcf-dedup-cron.log 2>&1"
    (crontab -l 2>/dev/null | grep -v vcf-dedup-cron; echo "$CRON_LINE") | crontab -
    info "Cron job scheduled: $CRON_LINE"
fi

echo ""
echo -e "${GREEN}════════════════════════════════════════${NC}"
echo -e "${GREEN}  Installation complete!${NC}"
echo -e "${GREEN}════════════════════════════════════════${NC}"
echo ""
echo "  Manual run:"
echo "    java -Xmx2g -jar $INSTALL_DIR/vcf-dedup.jar \\"
echo "         <scan-dir> <output-dir> [threads] [throttle-ms]"
echo ""
echo "  Cron script:  $INSTALL_DIR/vcf-dedup-cron.sh"
echo "  Logs:         /var/log/vcf-dedup-cron.log"
echo ""
