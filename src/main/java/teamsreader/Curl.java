package teamsreader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper that shells out to the curl-impersonate binary, exactly like the
 * AutoIt _RunCurl / _CurlWithCode helpers.
 *
 * <p>Unlike AutoIt we build an ARGUMENT LIST (ProcessBuilder), so there is no
 * command-line quoting to get wrong - each header / data field is a separate
 * element and curl receives it verbatim. The response body is written with
 * {@code -o <tmp>} and read back as bytes (UTF-8 for text), and the HTTP status
 * is captured with {@code -w "%{http_code}"} on stdout.
 */
public final class Curl {
    private final Config cfg;

    public Curl(Config cfg) { this.cfg = cfg; }

    public static final class Result {
        public final byte[] body;
        public final int httpCode;   // 0 if curl never got a response
        public final int exitCode;
        Result(byte[] body, int httpCode, int exitCode) {
            this.body = body; this.httpCode = httpCode; this.exitCode = exitCode;
        }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    /** Run curl with the given args (flags/headers/data). Returns body + status. */
    public Result run(List<String> args) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile(cfg.home, "_curl", ".tmp");
            List<String> cmd = new ArrayList<>();
            cmd.add(cfg.curl);
            cmd.addAll(args);
            cmd.add("-o");  cmd.add(tmp.toString());
            cmd.add("-w");  cmd.add("%{http_code}");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cfg.home.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();

            String out;
            try (InputStream is = p.getInputStream()) {
                out = new String(is.readAllBytes(), StandardCharsets.US_ASCII).trim();
            }
            // drain stderr so the process can't block on a full pipe
            try (InputStream es = p.getErrorStream()) { es.readAllBytes(); }

            boolean done = p.waitFor(240, TimeUnit.SECONDS);
            int exit = done ? p.exitValue() : -1;
            if (!done) p.destroyForcibly();

            int code = 0;
            try {
                // -w may print several codes across redirects; take the last 3-digit run
                String digits = out.replaceAll("[^0-9]", " ").trim();
                if (!digits.isEmpty()) {
                    String[] parts = digits.split("\\s+");
                    code = Integer.parseInt(parts[parts.length - 1]);
                }
            } catch (NumberFormatException ignored) { }

            byte[] body = Files.exists(tmp) ? Files.readAllBytes(tmp) : new byte[0];
            return new Result(body, code, exit);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.info("curl invocation failed: " + e.getMessage());
            return new Result(new byte[0], 0, -1);
        } finally {
            if (tmp != null) { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
        }
    }

    /** Convenience: run and return the body decoded as UTF-8 text. */
    public String text(List<String> args) {
        return run(args).text();
    }
}
