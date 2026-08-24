// SPDX-License-Identifier: AGPL-3.0-or-later

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs one HEV preparation command while holding an OS-backed file lock. */
public final class HevPrepareLock {
    private static final int RUNNER_FAILURE = 70;

    private HevPrepareLock() {}

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: HevPrepareLock LOCK_FILE COMMAND [ARG ...]");
            System.exit(64);
        }

        Path lockPath = Path.of(args[0]);
        String[] command = Arrays.copyOfRange(args, 1, args.length);

        try {
            Path parent = lockPath.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("lock path has no parent");
            }
            Files.createDirectories(parent);
            rejectMalformedExistingLock(lockPath);

            Set<OpenOption> options =
                    Set.of(
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            int childStatus;
            try (FileChannel channel = FileChannel.open(lockPath, options);
                    FileLock lock = channel.lock()) {
                if (!lock.isValid()) {
                    throw new IOException("exclusive lock was not acquired");
                }
                childStatus = runChild(command);
            }
            System.exit(childStatus);
        } catch (Throwable error) {
            System.err.println(
                    "HEV preparation lock failed: " + error.getClass().getSimpleName());
            System.exit(RUNNER_FAILURE);
        }
    }

    private static void rejectMalformedExistingLock(Path lockPath) throws IOException {
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(lockPath)
                || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("lock path is not a regular file");
        }
    }

    private static int runChild(String[] command) throws IOException, InterruptedException {
        AtomicReference<Process> child = new AtomicReference<>();
        Thread shutdownHook =
                new Thread(() -> terminateTree(child.get()), "hev-prepare-lock-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
            builder.environment().put("SUBSPACE_HEV_PREPARE_LOCKED", "1");
            Process started = builder.start();
            child.set(started);
            int status = started.waitFor();
            child.set(null);
            return status;
        } catch (InterruptedException error) {
            terminateTree(child.get());
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // Shutdown already owns the hook and will terminate the child.
            }
        }
    }

    private static void terminateTree(Process process) {
        if (process == null) {
            return;
        }

        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants().toList();
        } catch (RuntimeException ignored) {
            // Some macOS sandboxes deny the sysctl used for descendant lookup.
            // The direct child must still be terminated so its shell trap can
            // clean up any subprocesses it owns.
            descendants = List.of();
        }
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroy();
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                for (int index = descendants.size() - 1; index >= 0; index--) {
                    descendants.get(index).destroyForcibly();
                }
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
