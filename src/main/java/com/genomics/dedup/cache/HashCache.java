package com.genomics.dedup.cache;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentMap;

/**
 * Persistent hash cache backed by MapDB.
 *
 * Key   : absolute file path (String)
 * Value : "lastModifiedEpochMs:sha256hex"
 *
 * A cached entry is only trusted if the stored lastModified timestamp
 * matches the file's current timestamp — any file write auto-invalidates it.
 *
 * Committed to disk every N files and on JVM shutdown.
 */
public class HashCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HashCache.class);

    private final DB db;
    private final ConcurrentMap<String, String> map;

    public HashCache(Path dbPath) {
        db = DBMaker.fileDB(dbPath.toFile())
                .fileMmapEnableIfSupported()
                .transactionEnable()
                .closeOnJvmShutdown()
                .make();

        map = db.hashMap("hashes", Serializer.STRING, Serializer.STRING)
                .createOrOpen();

        log.info("Hash cache opened: {} ({} existing entries)", dbPath, map.size());
    }

    /**
     * Returns cached SHA-256 hex if path + lastModified match, otherwise null.
     */
    public String get(Path filePath, long lastModifiedMs) {
        String raw = map.get(filePath.toString());
        if (raw == null) return null;
        int sep = raw.indexOf(':');
        if (sep < 0) return null;
        try {
            long cachedTs = Long.parseLong(raw.substring(0, sep));
            return cachedTs == lastModifiedMs ? raw.substring(sep + 1) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Stores path → "lastModifiedMs:sha256hex".
     */
    public void put(Path filePath, long lastModifiedMs, String sha256Hex) {
        map.put(filePath.toString(), lastModifiedMs + ":" + sha256Hex);
    }

    /** Commit current batch to disk. */
    public void commit() {
        db.commit();
    }

    public long size() { return map.size(); }

    @Override
    public void close() {
        try {
            db.commit();
            db.close();
            log.info("Hash cache closed and committed ({} entries)", map.size());
        } catch (Exception e) {
            log.warn("Error closing hash cache: {}", e.getMessage());
        }
    }
}
