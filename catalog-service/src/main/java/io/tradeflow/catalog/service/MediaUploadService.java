package io.tradeflow.catalog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class  MediaUploadService {

    private final S3Client s3Client;

    @Value("${aws.s3.media-bucket}")
    private String mediaBucket;

    @Value("${aws.cloudfront.domain:cdn.tradeflow.io}")
    private String cdnDomain;

    public UploadResult upload(String productId, MultipartFile file, String mediaType) {
        validateFile(file);

        String ext = getExtension(file.getOriginalFilename());
        String s3Key = "products/" + productId + "/" + mediaType.toLowerCase() + "_" + UUID.randomUUID() + "." + ext;

        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(mediaBucket)
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();
            s3Client.putObject(req, RequestBody.fromBytes(file.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("S3 upload failed: " + e.getMessage(), e);
        }

        String s3Url = "https://" + mediaBucket + ".s3.amazonaws.com/" + s3Key;
        String cdnUrl = "https://" + cdnDomain + "/" + s3Key;

        log.debug("Media uploaded: productId={}, key={}", productId, s3Key);
        return new UploadResult(s3Url, cdnUrl, s3Key);
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) throw new BadRequestException("File is empty");
        if (file.getSize() > 10 * 1024 * 1024) throw new BadRequestException("File exceeds 10MB limit");
        String ct = file.getContentType();
        if (ct == null || (!ct.startsWith("image/jpeg") && !ct.startsWith("image/png") && !ct.startsWith("image/webp"))) {
            throw new BadRequestException("Only JPEG, PNG, WebP allowed");
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    record UploadResult(String s3Url, String cdnUrl, String s3Key) {}
}
