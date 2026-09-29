package com.example.MigrosBackend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup outside exact-local development when the upload directory is
 * missing or blank.
 *
 * <p>Product images are written under this directory, and a blank value would
 * quietly resolve to the process working directory, which is neither stable nor
 * durable across restarts. There is no usable non-local fallback for the same
 * reason the mail sender address has none: a deployment has to say where its
 * persistent storage is.
 */
@Component
public class UploadDirStartupValidation {

    private final AdminStartupProfilePolicy profilePolicy;
    private final String uploadDir;

    public UploadDirStartupValidation(AdminStartupProfilePolicy profilePolicy,
                                      @Value("${app.upload-dir:}") String uploadDir) {
        this.profilePolicy = profilePolicy;
        this.uploadDir = uploadDir;
    }

    @PostConstruct
    public void validate() {
        boolean missing = uploadDir == null || uploadDir.isBlank();
        if (missing && !profilePolicy.isLocalDevelopment()) {
            throw new IllegalStateException(
                    "APP_UPLOAD_DIR must be configured outside exact-local development");
        }
    }
}
