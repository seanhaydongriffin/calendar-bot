package teamsreader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Instant;

/** Console + file logger, mirroring the AutoIt _Log / _Fatal pair. */
public final class Log {
    // Local time (the Pi's own zone), so log lines read in the operator's time
    // rather than UTC.
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static Path logFile;

    private Log() {}

    public static void init(Path file) { logFile = file; }

    public static void info(String s) {
        String line = STAMP.format(Instant.now()) + "  " + s;
        System.out.println(line);
        if (logFile != null) {
            try {
                Files.writeString(logFile, line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        }
    }

    /** Fatal error: log and exit(1), like _Fatal. */
    public static RuntimeException fatal(String s) {
        info("FATAL: " + s);
        System.err.println("FATAL: " + s);
        System.exit(1);
        return new RuntimeException(s); // unreachable; lets callers write `throw Log.fatal(...)`
    }
}
