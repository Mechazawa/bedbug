package at.ioexception.mcaddon.clientgametest;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ends a client game test run that has stopped moving, with a thread dump, instead of letting it
 * hold its window and its `minecraft-client` slot forever.
 *
 * Progress is a tick of the client or of any server in the process. Fabric's test thread lets the
 * client tick only from `waitTick`, so every wait a test makes ticks, and the longest of them is a
 * minute. The limit is `mcaddon.clientHangMinutes`, three minutes by default, counted from the
 * first tick so the game's startup is not measured.
 */
public final class HangWatchdog implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Client game test watchdog");
    private static final Duration POLL = Duration.ofSeconds(5);
    private static final int EXIT_CODE = 124;
    private static final String TEST_PACKAGE = HangWatchdog.class.getPackageName() + ".";

    private final AtomicLong ticks = new AtomicLong();
    private final Duration limit = Duration.ofMillis(
        (long) (Double.parseDouble(System.getProperty("mcaddon.clientHangMinutes", "3")) * 60_000));
    private String lastTest = "no test had started";

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> ticks.incrementAndGet());
        ServerTickEvents.END_SERVER_TICK.register(server -> ticks.incrementAndGet());
        Thread watcher = new Thread(this::watch, "Client game test watchdog");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watch() {
        long seen = 0;
        long stillSince = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException interrupted) {
                return;
            }
            runningTest(ManagementFactory.getThreadMXBean().dumpAllThreads(false, false))
                .ifPresent(test -> lastTest = test);
            long now = ticks.get();
            if (now != seen || now == 0) {
                seen = now;
                stillSince = System.nanoTime();
            } else if (Duration.ofNanos(System.nanoTime() - stillSince).compareTo(limit) >= 0) {
                fail(ManagementFactory.getThreadMXBean().dumpAllThreads(true, true));
            }
        }
    }

    private void fail(ThreadInfo[] threads) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = FabricLoader.getInstance().getGameDir().resolve("hang-" + stamp + ".txt").toAbsolutePath();
        String line = "CLIENT GAME TEST HANG: " + file + " during " + lastTest
            + ", no tick for " + limit.toSeconds() + " seconds";
        try {
            Files.writeString(file, line + "\n\n" + Arrays.stream(threads)
                .map(HangWatchdog::describe)
                .collect(Collectors.joining("\n")));
        } catch (IOException unwritten) {
            LOGGER.error("The hang dump could not be written to {}", file, unwritten);
        }
        LOGGER.error(line);
        System.out.flush();
        System.err.flush();
        // Shutdown hooks can wait on the threads that hang, and halting runs none of them.
        Runtime.getRuntime().halt(EXIT_CODE);
    }

    /** The test class on the test thread's stack, found by its package and its name. */
    private static Optional<String> runningTest(ThreadInfo[] threads) {
        return Arrays.stream(threads)
            .flatMap(thread -> Arrays.stream(thread.getStackTrace()))
            .map(frame -> frame.getClassName().split("\\$")[0])
            .filter(name -> name.startsWith(TEST_PACKAGE) && name.endsWith("ClientGameTest"))
            .findFirst()
            .map(name -> name.substring(TEST_PACKAGE.length()));
    }

    /** {@link ThreadInfo#toString} stops after eight frames, which hides where a deadlock starts. */
    private static String describe(ThreadInfo thread) {
        StringBuilder text = new StringBuilder()
            .append('"').append(thread.getThreadName()).append("\" #").append(thread.getThreadId())
            .append(thread.isDaemon() ? " daemon" : "").append(' ').append(thread.getThreadState());
        Optional.ofNullable(thread.getLockInfo()).ifPresent(lock -> text.append(" on ").append(lock));
        Optional.ofNullable(thread.getLockOwnerName())
            .ifPresent(owner -> text.append(" owned by \"").append(owner).append("\" #").append(thread.getLockOwnerId()));
        text.append('\n');
        StackTraceElement[] frames = thread.getStackTrace();
        for (int depth = 0; depth < frames.length; depth++) {
            text.append("\tat ").append(frames[depth]).append('\n');
            int frameDepth = depth;
            Arrays.stream(thread.getLockedMonitors())
                .filter(monitor -> monitor.getLockedStackDepth() == frameDepth)
                .map(MonitorInfo::toString)
                .forEach(monitor -> text.append("\t- locked ").append(monitor).append('\n'));
        }
        Stream.of(thread.getLockedSynchronizers())
            .map(LockInfo::toString)
            .forEach(lock -> text.append("\t- holds ").append(lock).append('\n'));
        return text.toString();
    }
}
