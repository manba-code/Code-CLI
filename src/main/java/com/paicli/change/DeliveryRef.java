package com.paicli.change;

/** Local Mock publication, including the exact judgment and approval it used. */
public record DeliveryRef(String pullRequestId, String changeId, String specDigest,
                          String headSha, String conclusion, String evidencePath,
                          String runId, long judgmentRevision, String approvalId, long taskVersion,
                          String publicationKey, String publishedAt) { }
