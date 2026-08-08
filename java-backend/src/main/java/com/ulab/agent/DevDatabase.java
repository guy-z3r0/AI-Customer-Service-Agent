package com.ulab.agent;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A database for people who have not got one.
 *
 * Running the stack properly means Docker, which brings its own PostgreSQL.
 * This is the other way in: with the "dev" profile active, a real PostgreSQL
 * server is unpacked into a folder next to the project and started as a child
 * process. Same engine, same extensions, same migrations — it is a genuine
 * Postgres, not a stand-in that behaves almost like one, which matters because
 * this app leans on pgcrypto and jsonb.
 *
 * Start it with:
 *     mvn spring-boot:run -Dspring-boot.run.profiles=dev
 *
 * The data directory survives restarts, so a business edited in the panel is
 * still there tomorrow. Delete it to start over.
 *
 * None of this reaches production: the dependency is "provided" scope, so it is
 * not inside the shipped jar, and nothing here runs unless the profile is on.
 */
@Configuration
@Profile("dev")
public class DevDatabase {

    private static final Logger log = LoggerFactory.getLogger(DevDatabase.class);

    /** Kept out of target/ so a clean build does not wipe the demo data. */
    private static final Path DATA_DIRECTORY = Paths.get(".embedded-postgres");

    /** How long a leftover server gets to shut down politely. */
    private static final int SHUTDOWN_WAIT_SECONDS = 10;

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        Files.createDirectories(DATA_DIRECTORY);
        clearStaleLock();
        log.info("Starting an embedded PostgreSQL in {} — first run downloads it, "
                + "later runs are quick", DATA_DIRECTORY.toAbsolutePath());

        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setDataDirectory(DATA_DIRECTORY.resolve("data"))
                .setCleanDataDirectory(false)
                .start();

        log.info("Embedded PostgreSQL is listening on port {}", postgres.getPort());
        return postgres;
    }

    /**
     * Removes the lock file a killed server leaves behind.
     *
     * PostgreSQL refuses to start if postmaster.pid is already there, which is
     * exactly what happens after closing the console window instead of stopping
     * the app. The first line of that file is the process id, so a server that
     * outlived its backend can be stopped before the new one starts.
     */
    private static void clearStaleLock() {
        Path lock = DATA_DIRECTORY.resolve("data").resolve("postmaster.pid");
        if (!Files.exists(lock)) return;

        try {
            String firstLine = Files.readAllLines(lock).stream().findFirst().orElse("").trim();
            long pid = Long.parseLong(firstLine);
            ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter(candidate -> isTheServerThisFileNames(candidate, lock))
                    .ifPresent(DevDatabase::stop);
            Files.deleteIfExists(lock);
            log.info("Cleared a lock file left behind by a previous run");
        } catch (IOException | NumberFormatException e) {
            // An unreadable lock file is itself the leftover of a bad exit.
            try {
                Files.deleteIfExists(lock);
                log.info("Cleared an unreadable lock file from a previous run");
            } catch (IOException stubborn) {
                log.warn("Could not clear {}: {}", lock, stubborn.toString());
            }
        }
    }

    /**
     * Whether the process holding this id really is the server that wrote the
     * lock file.
     *
     * The comment here used to say the process was ours by construction. It is
     * not: the id is read off a disk file that may be days old, and an id is
     * reused as soon as the machine has got through the rest of them. After a
     * reboot, or on a busy machine, that number names something else entirely —
     * and on Windows a low one can name a system process. Two things have to
     * agree before anything is killed: the process must be a postgres, and it
     * must have started before the lock file was last written, because a
     * process that started afterwards cannot be the one that wrote it.
     */
    static boolean isTheServerThisFileNames(ProcessHandle candidate, Path lock) {
        return looksLikeAPostgres(candidate) && startedBeforeTheLockWasWritten(candidate, lock);
    }

    private static boolean looksLikeAPostgres(ProcessHandle candidate) {
        String command = candidate.info().command().orElse("");
        if (command.toLowerCase().contains("postgres")) return true;

        log.warn("Process {} holds the lock file but is '{}', not a PostgreSQL — leaving it "
                + "alone. Delete the lock file by hand if the database will not start.",
                candidate.pid(), command.isEmpty() ? "unreadable" : command);
        return false;
    }

    /**
     * A process that started after the lock file was last written cannot be the
     * process that wrote it — so it is somebody else wearing a reused id.
     */
    static boolean startedBeforeTheLockWasWritten(ProcessHandle candidate, Path lock) {
        try {
            Instant startedAt = candidate.info().startInstant().orElse(Instant.MIN);
            if (!startedAt.isAfter(Files.getLastModifiedTime(lock).toInstant())) return true;

            log.warn("Process {} started after {} was written, so it is not the server that "
                    + "wrote it — leaving it alone.", candidate.pid(), lock);
        } catch (IOException unreadable) {
            log.warn("Could not read the age of {}, so process {} is being left alone: {}",
                    lock, candidate.pid(), unreadable.toString());
        }
        return false;
    }

    /**
     * Stops the server still holding our data directory.
     *
     * Killing the backend orphans it and it takes a few seconds to notice, so
     * give it that long before insisting; without the wait, restarting twice
     * quickly fails on a race.
     */
    private static void stop(ProcessHandle postgres) {
        log.info("Stopping a PostgreSQL left running by a previous run (process {})",
                postgres.pid());
        postgres.destroy();
        try {
            postgres.onExit().get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            postgres.destroyForcibly();
        }
    }

    /**
     * Hands Spring the embedded server's connection instead of the one in
     * application.yml. Flyway and JPA then behave exactly as they do against
     * the Docker database, because it is the same kind of database.
     *
     * stringtype=unspecified has to be repeated here. It is set on the Hikari
     * pool in application.yml, which this bean replaces, and without it every
     * write to a jsonb column is rejected as "column is of type jsonb but
     * expression is of type character varying".
     */
    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase(Map.of("stringtype", "unspecified"));
    }
}
