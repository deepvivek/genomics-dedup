#!/usr/bin/env bash
# ============================================================
# fix-structure.sh
# Run this ONCE inside your dedupeFinal folder to move files
# into the correct Maven package directory structure.
#
# Usage:
#   cd /Users/vivek/Downloads/dedupeFinal
#   chmod +x fix-structure.sh && ./fix-structure.sh
# ============================================================

set -euo pipefail

BASE="src/main/java/com/genomics/dedup"
TEST="src/test/java/com/genomics/dedup"

echo "Creating package directories..."
mkdir -p "$BASE/config"
mkdir -p "$BASE/engine"
mkdir -p "$BASE/hash"
mkdir -p "$BASE/cache"
mkdir -p "$BASE/report"
mkdir -p "$BASE/xlsx"
mkdir -p "$TEST/config"
mkdir -p "$TEST/engine"
mkdir -p "$TEST/hash"
mkdir -p "$TEST/cache"
mkdir -p "$TEST/report"
mkdir -p "$TEST/xlsx"

FLAT_MAIN="src/main/java"
FLAT_TEST="src/test/java"

move_if_exists() {
    local src="$1" dst="$2"
    if [[ -f "$src" ]]; then
        mv "$src" "$dst"
        echo "  Moved: $(basename $src) → $dst"
    fi
}

echo "Moving main source files..."
move_if_exists "$FLAT_MAIN/VcfDuplicateDetector.java"  "$BASE/VcfDuplicateDetector.java"
move_if_exists "$FLAT_MAIN/ScanConfig.java"             "$BASE/config/ScanConfig.java"
move_if_exists "$FLAT_MAIN/FileScanEngine.java"         "$BASE/engine/FileScanEngine.java"
move_if_exists "$FLAT_MAIN/Phase1Walker.java"           "$BASE/engine/Phase1Walker.java"
move_if_exists "$FLAT_MAIN/Phase2Hasher.java"           "$BASE/engine/Phase2Hasher.java"
move_if_exists "$FLAT_MAIN/ExternalSorter.java"         "$BASE/engine/ExternalSorter.java"
move_if_exists "$FLAT_MAIN/FileHasher.java"             "$BASE/hash/FileHasher.java"
move_if_exists "$FLAT_MAIN/HashCache.java"              "$BASE/cache/HashCache.java"
move_if_exists "$FLAT_MAIN/CsvReportWriter.java"        "$BASE/report/CsvReportWriter.java"
move_if_exists "$FLAT_MAIN/DuplicateGroup.java"         "$BASE/report/DuplicateGroup.java"
move_if_exists "$FLAT_MAIN/XlsxReportGenerator.java"   "$BASE/xlsx/XlsxReportGenerator.java"
move_if_exists "$FLAT_MAIN/XlsxStyles.java"             "$BASE/xlsx/XlsxStyles.java"

echo "Moving test files..."
move_if_exists "$FLAT_TEST/ScanConfigTest.java"                  "$TEST/config/ScanConfigTest.java"
move_if_exists "$FLAT_TEST/FileScanEngineIntegrationTest.java"   "$TEST/engine/FileScanEngineIntegrationTest.java"
move_if_exists "$FLAT_TEST/Phase1WalkerTest.java"                "$TEST/engine/Phase1WalkerTest.java"
move_if_exists "$FLAT_TEST/FileHasherTest.java"                  "$TEST/hash/FileHasherTest.java"
move_if_exists "$FLAT_TEST/HashCacheTest.java"                   "$TEST/cache/HashCacheTest.java"
move_if_exists "$FLAT_TEST/CsvReportWriterTest.java"             "$TEST/report/CsvReportWriterTest.java"
move_if_exists "$FLAT_TEST/DuplicateGroupTest.java"              "$TEST/report/DuplicateGroupTest.java"
move_if_exists "$FLAT_TEST/XlsxReportGeneratorTest.java"         "$TEST/xlsx/XlsxReportGeneratorTest.java"

echo ""
echo "Final structure:"
find src -name "*.java" | sort

echo ""
echo "Done. Now run: mvn compile"
