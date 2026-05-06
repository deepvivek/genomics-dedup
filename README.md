# Genomics Duplicate Detector

Scans a directory tree for duplicate genomics files using SHA-256 content hashing.
Produces a CSV report and a multi-sheet Excel workbook for manual review.

Supported file types: `.vcf` `.vcf.gz` `.g.vcf` `.g.vcf.gz` `.bam` `.fastq` `.fastq.gz` `.txt`

---

## Requirements

| Requirement | Version |
|---|---|
| Java (JDK or JRE) | 17 or higher |
| Maven | 3.8+ (only needed to build from source) |
| OS | Linux, macOS, Windows |
| RAM | 2 GB minimum recommended |
| Disk | Output dir needs ~10 MB + space for reports |

---

## Installation

### Option A — Use the pre-built JAR (recommended for servers)

Copy the fat JAR to the server. No other dependencies needed.

```bash
scp target/vcf-dedup-2.0.0.jar user@server:/opt/genomics/
```

### Option B — Build from source on the server

```bash
# 1. Install Java 17 (Ubuntu/Debian)
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk

# 1. Install Java 17 (RHEL/CentOS/Rocky)
sudo yum install -y java-17-openjdk-devel

# 2. Install Maven
sudo apt-get install -y maven        # Ubuntu/Debian
sudo yum install -y maven            # RHEL/CentOS

# 3. Clone or copy the source
git clone <repo-url> genomics-dedup
cd genomics-dedup

# 4. Build the fat JAR (runs all tests)
mvn package

# JAR is at: target/vcf-dedup-2.0.0.jar
```

Verify Java is available:

```bash
java -version
# Expected: openjdk version "17.x.x" ...
```

---

## Running

### Basic command

```bash
java -Xmx2g -jar vcf-dedup-2.0.0.jar <scanDir> <outputDir>
```

### Full command with all options

```bash
java -Xmx2g -jar vcf-dedup-2.0.0.jar <scanDir> <outputDir> [threads] [throttleMs] [extensions]
```

| Argument | Required | Default | Description |
|---|---|---|---|
| `scanDir` | Yes | — | Root directory to scan recursively |
| `outputDir` | Yes | — | Directory where reports are written |
| `threads` | No | `8` | Number of parallel hashing threads (max 20) |
| `throttleMs` | No | `50` | Milliseconds to wait per file per thread (use `0` for max speed, higher values to reduce I/O load on production servers) |
| `extensions` | No | see below | Comma-separated list of file extensions to scan |

Default extensions when not specified:
`.vcf,.vcf.gz,.g.vcf,.g.vcf.gz,.bam,.fastq,.fastq.gz,.txt`

### Examples

```bash
# Minimal — scan /data/genomics, write reports to /data/reports
java -Xmx2g -jar vcf-dedup-2.0.0.jar /data/genomics /data/reports

# With 16 threads, no throttle (fast machine, dedicated run)
java -Xmx2g -jar vcf-dedup-2.0.0.jar /data/genomics /data/reports 16 0

# Throttled — 100ms delay per file to reduce I/O impact on a shared server
java -Xmx2g -jar vcf-dedup-2.0.0.jar /data/genomics /data/reports 8 100

# Scan only VCF and BAM files
java -Xmx2g -jar vcf-dedup-2.0.0.jar /data/genomics /data/reports 8 50 .vcf,.vcf.gz,.bam

# Scan only TXT files
java -Xmx2g -jar vcf-dedup-2.0.0.jar /data/genomics /data/reports 8 50 .txt

# Large dataset — 4 GB heap, 20 threads
java -Xmx4g -jar vcf-dedup-2.0.0.jar /data/genomics /data/reports 20 0
```

### Run in the background (Linux/macOS)

```bash
nohup java -Xmx2g -jar vcf-dedup-2.0.0.jar /data/genomics /data/reports \
  > /data/reports/run.log 2>&1 &

echo "PID: $!"

# Tail the log
tail -f /data/reports/run.log
```

---

## Output files

All output files are written to `outputDir`.

| File | Description |
|---|---|
| `report.csv` | Raw duplicate data — one row per file in a duplicate group |
| `review.xlsx` | Excel workbook for manual review (5 sheets, see below) |
| `hash_cache.db` | Persistent hash cache — re-runs skip already-hashed files |

### report.csv columns

| Column | Description |
|---|---|
| `group_id` | Numeric ID shared by all copies of the same file |
| `sha256` | Full SHA-256 hash of file content |
| `file_path` | Absolute path to the file |
| `file_size_bytes` | File size in bytes |
| `last_modified` | Last modified timestamp (UTC) |
| `is_original` | `true` for the suggested file to keep (alphabetically first path) |

### review.xlsx sheets

| Sheet | Contents |
|---|---|
| **Summary** | Scan statistics + live formula counters that update as reviewers fill in decisions |
| **Review** | All duplicate groups — one row per group with Decision dropdown, Reviewer, Notes |
| **High Priority** | Top 500 groups by wasted space |
| **Suspicious** | Groups whose copies span different top-level directories |
| **Raw Data** | Original CSV data verbatim |

The **Decision** dropdown in the Review sheet has four options:

| Decision | Meaning |
|---|---|
| `Pending` | Not yet reviewed (default) |
| `Archive duplicates` | Safe to delete the duplicate copies |
| `Keep all` | All copies are intentional, keep them |
| `Escalate` | Unsure — flag for senior review |

---

## Resume support

If a run is interrupted, the hash cache (`hash_cache.db`) preserves all hashes computed so far. Re-running the same command resumes from where it left off — already-hashed files are skipped.

```
First run:   hashes files 1 – 800,000     → saved to hash_cache.db
Second run:  files 1–800,000  → cache hit (skipped instantly)
             hashes files 800,001–1,600,000 → saved
Third run:   files 1–1,600,000 → cache hit (skipped instantly)
             hashes remaining files → done
```

**Never delete `hash_cache.db`** between runs — it is what makes resume possible. Delete it only if you want to force a full re-scan from scratch.

---

## Memory and performance

- **RAM model**: O(1) — the tool never loads the full file list into memory. It streams to disk at every phase using external sort.
- **Heap**: `-Xmx2g` is sufficient for millions of files. Increase to `-Xmx4g` for very large datasets.
- **Threads**: Default is 8. On a dedicated server with fast storage set to 16–20. On a shared server use fewer threads and add throttle.
- **Speed**: On a local SSD, ~1.67 GB/s with 8 threads. On HDD, use 2–4 threads to avoid head thrashing.

### HDD vs SSD guidance

| Storage | Recommended threads | Throttle | Expected throughput |
|---|---|---|---|
| Local SSD | 8–20 | 0–50ms | 1–4 GB/s |
| Local HDD (sda1) | 2–4 | 100ms | 100–150 MB/s |
| NFS / network | 4–8 | 100ms | 500 MB/s–1 GB/s |
| RAID array (HDD) | 4–8 | 50ms | scales with disk count |

For HDD specifically, more threads hurt performance due to disk head seeking. Use:
```bash
java -Xmx2g -jar vcf-dedup-2.0.0.jar /data /reports 2 100
```

---

## Scheduling (cron)

### Run over weekends only (12 hours per night)

Useful for very large datasets (hundreds of TB on HDD) where a single continuous run is not feasible. The hash cache ensures each weekend session picks up exactly where the previous one left off.

**Step 1 — Create a start script:**

```bash
cat > /opt/genomics/start-dedup.sh << 'EOF'
#!/bin/bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk
PID_FILE=/opt/genomics/dedup.pid
LOG=/opt/genomics/logs/dedup-$(date +%Y-%m-%d).log

# Don't start if already running
if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
    echo "Already running as PID $(cat $PID_FILE)" >> "$LOG"
    exit 0
fi

mkdir -p /opt/genomics/logs
nohup $JAVA_HOME/bin/java -Xmx2g \
  -jar /opt/genomics/vcf-dedup-2.0.0.jar \
  /data/genomics /data/reports 2 100 \
  >> "$LOG" 2>&1 &

echo $! > "$PID_FILE"
echo "Started PID $(cat $PID_FILE) at $(date)" >> "$LOG"
EOF
chmod +x /opt/genomics/start-dedup.sh
```

**Step 2 — Create a stop script:**

```bash
cat > /opt/genomics/stop-dedup.sh << 'EOF'
#!/bin/bash
PID_FILE=/opt/genomics/dedup.pid
LOG=/opt/genomics/logs/dedup-$(date +%Y-%m-%d).log

if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
    kill $(cat "$PID_FILE")
    echo "Stopped PID $(cat $PID_FILE) at $(date)" >> "$LOG"
    rm -f "$PID_FILE"
else
    echo "Not running at $(date)" >> "$LOG"
fi
EOF
chmod +x /opt/genomics/stop-dedup.sh
```

**Step 3 — Schedule via cron (12 hours each night, Fri/Sat/Sun):**

```bash
crontab -e

# Start Friday 8 PM, stop Saturday 8 AM
0 20 * * 5  /opt/genomics/start-dedup.sh
0  8 * * 6  /opt/genomics/stop-dedup.sh

# Start Saturday 8 PM, stop Sunday 8 AM
0 20 * * 6  /opt/genomics/start-dedup.sh
0  8 * * 0  /opt/genomics/stop-dedup.sh

# Start Sunday 8 PM, stop Monday 8 AM
0 20 * * 0  /opt/genomics/start-dedup.sh
0  8 * * 1  /opt/genomics/stop-dedup.sh
```

**Check progress any time:**

```bash
# Live log
tail -f /opt/genomics/logs/dedup-$(date +%Y-%m-%d).log

# Check if running
ps -p $(cat /opt/genomics/dedup.pid)

# Cache size (grows as more files are hashed)
ls -lh /data/reports/hash_cache.db
```

### Run once weekly (simple cron)

```bash
crontab -e

# Every Sunday at 2 AM
0 2 * * 0 java -Xmx2g -jar /opt/genomics/vcf-dedup-2.0.0.jar \
  /data/genomics /data/reports \
  2 100 >> /var/log/genomics-dedup.log 2>&1
```

---

## Troubleshooting

**`Unable to locate a Java Runtime`**
Java is not on PATH. Set it explicitly:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH=$JAVA_HOME/bin:$PATH
java -version
```

**`OutOfMemoryError`**
Increase heap size:
```bash
java -Xmx4g -jar vcf-dedup-2.0.0.jar ...
```

**`ERROR closing hash cache: Cannot read the array length`**
Harmless warning from the MapDB library — does not affect results.

**`Log4j2 could not find a logging implementation`**
Harmless notice — the tool uses Logback, not Log4j2. Results are not affected.

**Run is slow on a shared server**
Reduce threads and add throttle to avoid saturating shared I/O:
```bash
java -Xmx2g -jar vcf-dedup-2.0.0.jar /data/genomics /data/reports 4 200
```
