package com.drzewiecki.shitcloud.data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * @param historyTtl         how long earlier states of a document are kept in {@code doc_history}
 * @param maxDocSize         largest {@code data} of one document, measured as JSON
 * @param maxResults         most documents one query returns; also the default limit
 * @param maxExportDocuments most documents one app's data download holds
 */
@ConfigurationProperties("data")
public record DataProperties(Duration historyTtl, DataSize maxDocSize, int maxResults, int maxExportDocuments) {
}
