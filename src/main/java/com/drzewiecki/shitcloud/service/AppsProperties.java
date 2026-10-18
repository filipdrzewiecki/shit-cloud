package com.drzewiecki.shitcloud.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * @param versionTtl  how long earlier versions of an app are kept in {@code _app_versions}
 * @param maxHtmlSize largest app HTML a deploy accepts
 * @param seedSamples put the sample apps into an empty registry at startup
 */
@ConfigurationProperties("apps")
public record AppsProperties(Duration versionTtl, DataSize maxHtmlSize, boolean seedSamples) {
}
