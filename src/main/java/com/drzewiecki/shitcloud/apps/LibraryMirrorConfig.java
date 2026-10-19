package com.drzewiecki.shitcloud.apps;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * The libraries apps may use, served from the apps origin itself: the CSP lets apps load nothing from
 * anywhere else. Pinned versions in {@code apps-lib/LICENSES.md}. {@link PortFence} keeps this off the API port.
 */
@Configuration
public class LibraryMirrorConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/_lib/**")
                .addResourceLocations("classpath:/apps-lib/")
                // A day, not forever: the file names do not change when a library is upgraded.
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
    }
}
