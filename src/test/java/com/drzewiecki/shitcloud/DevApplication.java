package com.drzewiecki.shitcloud;

import org.springframework.boot.SpringApplication;

/**
 * Local development entry point (`npm run dev` → `mvn spring-boot:test-run`). Runs the real application
 * on the test classpath, where an embedded PostgreSQL replaces the database, so nothing has to be installed.
 */
public class DevApplication {

    public static void main(String[] args) {
        SpringApplication.from(Application::main).run(args);
    }
}
