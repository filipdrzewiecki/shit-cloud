package com.drzewiecki.shitcloud.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param maxDocuments   most documents one migration converts
 * @param timeout        how long one run over all documents (the check or the apply) may take
 * @param statementLimit JavaScript statements the transform may execute for one document
 * @param copyTtl        how long the documents as they were before are kept, and undo is possible
 * @param proposalTtl    how long a proposal nobody applied is kept
 */
@ConfigurationProperties("migration")
public record MigrationProperties(int maxDocuments, Duration timeout, long statementLimit, Duration copyTtl,
                                  Duration proposalTtl) {
}
