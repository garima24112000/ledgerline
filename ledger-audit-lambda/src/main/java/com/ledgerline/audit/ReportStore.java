package com.ledgerline.audit;

/** Where audit reports go. S3 in production; a mock in unit tests. */
public interface ReportStore {
    void put(String key, String json);
}
