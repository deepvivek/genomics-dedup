#!/usr/bin/env bash
# ============================================================
# vcf-dedup-cron.sh  — nightly off-hours runner
#
# Cron example (2 AM daily, hard-stop after 4 hours):
#   0 2 * * * /opt/vcf-dedup/vcf-dedup-cron.sh >> /var/log/vcf-dedup-cron.log 2>&1
# ============================================================

set -euo pipefail

# ── Configure these two paths ─────────────────────────────────────────────────
SCAN_DIR="/data/vcf"                         # root directory of VCF files
REPORT_DIR="/data/vcf-dedup-reports"         # where reports will be written

# ── Tuning (safe defaults for 24-core Xeon) ───────────────────────────────────
JAR="/opt/vcf-dedup/vcf-dedup.jar"
THREADS=8                # hashing threads  (8 of 24 cores — leaves room for production)
THROTTLE_MS=50           # ms sleep per file per thread
MAX_RUNTIME="4h"         # hard stop — must finish before morning peak

# JVM: 2g heap is sufficient with the O(1) streaming design
JAVA_OPTS="-Xmx2g -Xms256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError"

# ── Derived ───────────────────────────────────────────────────────────────────
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
OUTPUT_DIR="${REPORT_DIR}/${TIMESTAMP}"
LOCK_FILE="/tmp/vcf-dedup.lock"

# ── Lock: prevent overlapping runs ────────────────────────────────────────────
if [[ -e "${LOCK_FILE}" ]]; then
    EXISTING_PID=$(cat "${LOCK_FILE}" 2>/dev/null || echo "unknown")
    echo "[$(date)] Already running (PID ${EXISTING_PID}) — exiting"
    exit 0
fi
echo $$ > "${LOCK_FILE}"
trap 'rm -f "${LOCK_FILE}"; echo "[$(date)] Lock released"' EXIT

# ── Pre-flight ────────────────────────────────────────────────────────────────
[[ -d "${SCAN_DIR}" ]] || { echo "[$(date)] ERROR: SCAN_DIR not found: ${SCAN_DIR}"; exit 1; }
[[ -f "${JAR}" ]]      || { echo "[$(date)] ERROR: JAR not found: ${JAR}"; exit 1; }
mkdir -p "${OUTPUT_DIR}"

echo "[$(date)] ════ VCF Dedup Starting ════"
echo "[$(date)]   SCAN_DIR   : ${SCAN_DIR}"
echo "[$(date)]   OUTPUT_DIR : ${OUTPUT_DIR}"
echo "[$(date)]   THREADS    : ${THREADS}"
echo "[$(date)]   THROTTLE   : ${THROTTLE_MS}ms"
echo "[$(date)]   MAX RUNTIME: ${MAX_RUNTIME}"

# ── Run ───────────────────────────────────────────────────────────────────────
# ionice -c 3  : idle IO class — lowest disk priority
# nice -n 15   : below-normal CPU priority
# timeout      : hard kill after MAX_RUNTIME
timeout "${MAX_RUNTIME}" \
    ionice -c 3 \
    nice -n 15 \
    java ${JAVA_OPTS} \
        -jar "${JAR}" \
        "${SCAN_DIR}" \
        "${OUTPUT_DIR}" \
        "${THREADS}" \
        "${THROTTLE_MS}"

EXIT_CODE=$?
case ${EXIT_CODE} in
    0)   echo "[$(date)] ════ Scan complete. Reports: ${OUTPUT_DIR}" ;;
    124) echo "[$(date)] WARNING: Timed out after ${MAX_RUNTIME}. Partial results in ${OUTPUT_DIR}" ;;
    *)   echo "[$(date)] ERROR: Process exited with code ${EXIT_CODE}" ;;
esac

# ── Symlink latest report for easy access ─────────────────────────────────────
ln -sfn "${OUTPUT_DIR}" "${REPORT_DIR}/latest"
echo "[$(date)] Latest symlink: ${REPORT_DIR}/latest"

exit ${EXIT_CODE}
