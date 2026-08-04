package com.ulab.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ulab.agent.services.LegacyImportService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The whole application is the control panel and the two sockets under it.
 *
 * There was a text console here until Phase 5, kept from version 1 for looking
 * at the database without a browser. Everything it could do the panel now does
 * better, and having two ways to change the same row was two ways to be wrong.
 */
@SpringBootApplication
@EnableScheduling
public class Main {

    public static final String VERSION = "2.0.0";
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Where the version-1 JSON files live. Read once by the importer, then ignored. */
    public static final Path DATA_DIRECTORY = Paths.get("data");
    public static final Path BUSINESSES_DIRECTORY = DATA_DIRECTORY.resolve("businesses");

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    /**
     * Brings the old JSON data in on the first start so a fresh install already
     * shows the businesses that existed before the database did. It skips
     * anything already imported, so later starts do nothing.
     */
    @Bean
    public ApplicationRunner legacyImportOnStartup(LegacyImportService importer) {
        return args -> importer.importAll();
    }
}
