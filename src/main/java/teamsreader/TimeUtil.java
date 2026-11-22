package teamsreader;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Time helpers. The AutoIt version hand-rolled civil-date math because it had no
 * date library; Java has {@code java.time}, so this is far smaller and more
 * correct. All "local" times use the configured zone (Australia/Brisbane) rather
 * than the machine's TZ, so behaviour is identical on Windows and the Pi.
 */
public final class TimeUtil {
    private final ZoneId zone;
    private static final DateTimeFormatter STAMP_MIN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter STAMP_SEC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public TimeUtil(ZoneId zone) { this.zone = zone; }

    /** Parse a Teams/ISO timestamp (e.g. "2026-11-15T10:00:00.123Z" or with
     *  an offset) to an Instant. Returns null on failure. */
    public static Instant parseIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);                       // ...Z form, with/without fraction
        } catch (Exception ignore) {
            try { return OffsetDateTime.parse(s).toInstant(); } // ...+10:00 form
            catch (Exception ignore2) { return null; }
        }
    }

    public Instant now() { return Instant.now(); }

    /** Numeric offset label for "now" in the configured zone, e.g. "+10:00". */
    public String tzLabel() {
        return ZonedDateTime.now(zone).getOffset().getId().replace("Z", "+00:00");
    }

    /** "yyyy-MM-dd HH:mm:ss" of an instant in the configured zone (seconds). */
    public String localStampSec(Instant i) {
        return STAMP_SEC.format(i.atZone(zone));
    }

    /** "yyyy-MM-dd HH:mm" of an instant in the configured zone (minutes). */
    public String localStampMin(Instant i) {
        return STAMP_MIN.format(i.atZone(zone));
    }

    /** Local stamp + tz label, e.g. "2026-11-15 13:52:00 +10:00" (Teams heartbeat). */
    public String teamsStamp(Instant i) {
        return localStampSec(i) + " " + tzLabel();
    }

    /** "DD-Mon-YYYY" for an IMAP SEARCH SINCE date (not needed with JavaMail,
     *  kept for completeness / debugging). */
    public String imapDate(Instant i) {
        return DateTimeFormatter.ofPattern("d-MMM-yyyy").format(i.atZone(zone));
    }
}
