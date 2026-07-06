package com.wish.rd.bootstrap.storage.impl;

import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 基于 AWS SDK v2 S3Client 的对象存储实现。
 *
 * <p>使用 path-style 访问的 {@link S3Client} 连接 RustFS 等 S3 兼容对象存储，
 * 实现 {@link ObjectStorageService} 的上传（自动建桶、随机 key）与按
 * {@code s3://} URL 下载流的能力，供摄取文件上传/下载使用。
 */
@Component
@ConditionalOnProperty(name = "rd.storage.mode", havingValue = "s3", matchIfMissing = true)
public final class S3ObjectStorageService implements ObjectStorageService {

    private final S3Client s3Client;

    public S3ObjectStorageService(
            @Value("${rustfs.url:http://localhost:9000}") String endpoint,
            @Value("${rustfs.access-key-id:rustfsadmin}") String accessKeyId,
            @Value("${rustfs.secret-access-key:rustfsadmin}") String secretAccessKey
    ) {
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Override
    public StoredIngestionFile upload(
            String bucketName,
            InputStream content,
            long size,
            String originalFilename,
            String contentType
    ) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        if (content == null || size <= 0) {
            throw new IllegalArgumentException("upload file must not be empty");
        }
        ensureBucket(bucketName);
        String safeFilename = originalFilename == null || originalFilename.isBlank() ? "uploaded-file" : originalFilename;
        String key = randomKey(safeFilename);
        String detectedType = detectType(safeFilename, contentType);
        s3Client.putObject(
                builder -> builder.bucket(bucketName).key(key).contentType(detectedType),
                RequestBody.fromInputStream(content, size)
        );
        return new StoredIngestionFile("s3://" + bucketName + "/" + key, detectedType, size, safeFilename);
    }

    @Override
    public InputStream openStream(String url) {
        S3Location location = parseS3Url(url);
        ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(builder -> builder
                .bucket(location.bucket())
                .key(location.key()));
        return stream;
    }

    private void ensureBucket(String bucketName) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404 || "NoSuchBucket".equals(exception.awsErrorDetails().errorCode())) {
                s3Client.createBucket(builder -> builder.bucket(bucketName));
                return;
            }
            throw exception;
        }
    }

    private S3Location parseS3Url(String url) {
        URI uri = URI.create(url);
        if (!"s3".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("invalid s3 url: " + url);
        }
        String path = uri.getPath();
        String key = path != null && path.startsWith("/") ? path.substring(1) : path;
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("invalid s3 url key: " + url);
        }
        return new S3Location(uri.getHost(), key);
    }

    private String randomKey(String originalFilename) {
        String suffix = suffix(originalFilename);
        String key = UUID.randomUUID().toString().replace("-", "");
        return suffix.isBlank() ? key : key + "." + suffix;
    }

    private String suffix(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        return filename.substring(index + 1).strip();
    }

    private String detectType(String filename, String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            return contentType;
        }
        String normalized = filename.toLowerCase();
        if (normalized.endsWith(".md") || normalized.endsWith(".markdown")) {
            return "text/markdown";
        }
        return "text/plain";
    }

    private record S3Location(String bucket, String key) {
    }
}
