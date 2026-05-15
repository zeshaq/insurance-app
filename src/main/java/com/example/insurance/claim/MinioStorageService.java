package com.example.insurance.claim;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

import java.io.InputStream;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MinIO-backed object storage for claim photos. Auto-creates the
 * {@code claims} bucket on startup so the demo never asks students to
 * pre-create one through the console.
 *
 * Endpoint, credentials, and bucket name are hardcoded for the demo —
 * a real deployment would source them from mpConfig + Liberty
 * appProperties. The DEFAULT MinIO credentials minioadmin/minioadmin
 * are intentionally not externalized: the compose stack uses those same
 * defaults, so coupling them keeps SETUP.md short.
 */
@ApplicationScoped
public class MinioStorageService {

    private static final Logger LOG = Logger.getLogger(MinioStorageService.class.getName());

    private static final String ENDPOINT   = "http://minio:9000";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String BUCKET     = "claims";

    private MinioClient client;

    @PostConstruct
    void init() {
        client = MinioClient.builder()
                .endpoint(ENDPOINT)
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
                LOG.info("Created MinIO bucket " + BUCKET);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "MinIO bucket bootstrap failed (will retry on first upload): " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void close() {
        try { if (client != null) client.close(); } catch (Exception ignored) {}
    }

    /**
     * Streams a payload to MinIO and returns the assigned object key. The
     * key is a UUID — claim metadata in Postgres records the key so the
     * binary and the row reference each other without leaking PII into
     * the object name.
     */
    public String upload(InputStream content, long size, String contentType, String originalName) throws Exception {
        String baseKey = UUID.randomUUID().toString(); String key = baseKey;
        if (originalName != null && originalName.contains(".")) {
            key = baseKey + originalName.substring(originalName.lastIndexOf('.'));
        }
        long objectSize = size > 0 ? size : -1;
        long partSize   = size > 0 ? -1 : 5L * 1024 * 1024;   // 5MB parts when size unknown
        PutObjectArgs args = PutObjectArgs.builder()
                .bucket(BUCKET)
                .object(key)
                .stream(content, objectSize, partSize)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .build();
        client.putObject(args);
        final String savedKey = key;
        LOG.fine(() -> "Uploaded " + size + " bytes to " + BUCKET + "/" + savedKey);
        return key;
    }

    public boolean exists(String key) {
        try {
            client.statObject(io.minio.StatObjectArgs.builder().bucket(BUCKET).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(key).build());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "MinIO delete failed for " + key, e);
        }
    }

    public String bucket() { return BUCKET; }
}
