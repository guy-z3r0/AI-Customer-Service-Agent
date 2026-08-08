package com.ulab.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What the dev database is allowed to kill (BUG-001).
 *
 * PostgreSQL leaves a postmaster.pid behind when it is not shut down properly,
 * and this application reads the process id out of it and stops that process so
 * a new server can start. The comment used to say the process was ours by
 * construction. It is not: the number came off a disk file that may be days
 * old, and an operating system reuses process ids. After a reboot, or on a busy
 * machine, it names something else — and on Windows a low one can name a system
 * process.
 *
 * There is no test here for the case that does kill something, because writing
 * one means starting a process in order to kill it. What is worth pinning down
 * is the refusals.
 */
class DevDatabaseTest {

    @Test
    void aProcessThatIsNotAPostgresIsLeftAlone(@TempDir Path folder) throws IOException {
        Path lock = folder.resolve("postmaster.pid");
        Files.writeString(lock, String.valueOf(ProcessHandle.current().pid()));

        // This JVM. It is alive, and its id is what the lock file says — which
        // is exactly the situation a reused process id produces.
        assertFalse(DevDatabase.isTheServerThisFileNames(ProcessHandle.current(), lock),
                "the running test suite is not a database and must not be killed");
    }

    @Test
    void aProcessYoungerThanTheLockFileIsLeftAlone(@TempDir Path folder) throws IOException {
        Path lock = folder.resolve("postmaster.pid");
        Files.writeString(lock, String.valueOf(ProcessHandle.current().pid()));
        // Backdated to before this JVM started, so nothing running now can be
        // the process that wrote it — which is what a reboot leaves behind.
        Files.setLastModifiedTime(lock,
                FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        assertFalse(DevDatabase.startedBeforeTheLockWasWritten(ProcessHandle.current(), lock));
    }

    @Test
    void aLockFileThatCannotBeReadIsNotAReasonToKillAnything(@TempDir Path folder) {
        assertFalse(DevDatabase.startedBeforeTheLockWasWritten(ProcessHandle.current(),
                folder.resolve("there-is-no-such-file.pid")));
    }
}
