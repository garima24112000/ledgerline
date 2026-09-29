package com.ledgerline.audit;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Writes reports to the audit bucket. The IAM role may only PutObject under audits/. */
final class S3ReportStore implements ReportStore {

    private final S3Client s3;
    private final String bucket;

    S3ReportStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, String json) {
        s3.putObject(request -> request.bucket(bucket).key(key).contentType("application/json"),
                RequestBody.fromString(json));
    }
}
