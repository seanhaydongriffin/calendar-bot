package teamsreader;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeUtility;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * IMAP email source via JavaMail (Sean's choice over curl imaps://).
 *
 * <p>Reads recent INBOX messages (real emails + forwarded phone texts tagged
 * {@code [SMS]} in the subject) and returns them as text entries for the Gemini
 * call. The folder is opened READ_ONLY, which guarantees the \Seen flag is never
 * set - the JavaMail equivalent of the old BODY.PEEK. The UIDVALIDITY +
 * high-water lastuid logic is preserved exactly, so no message is reprocessed.
 */
public final class EmailClient {
    private final Config cfg;
    private final IniState state;
    private final TimeUtil time;

    public EmailClient(Config cfg, IniState state, TimeUtil time) {
        this.cfg = cfg; this.state = state; this.time = time;
    }

    public static final class Result {
        public final List<String> entries = new ArrayList<>();
        public final List<Long> smsUids = new ArrayList<>();
        public long maxUid;
    }

    public Result collect() {
        Result r = new Result();
        String pass = Util.readFile(cfg.imapPassFile).strip();
        if (pass.isEmpty()) {
            Log.info("Email: no IMAP password in " + cfg.imapPassFile + " - skipping email source.");
            return r;
        }

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", cfg.imapHost);
        props.put("mail.imaps.port", Integer.toString(cfg.imapPort));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "20000");
        props.put("mail.imaps.timeout", "60000");

        Store store = null;
        try {
            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(cfg.imapHost, cfg.imapPort, cfg.imapUser, pass);

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            UIDFolder uf = (UIDFolder) inbox;

            long uidValidity = uf.getUIDValidity();
            String prevUv = state.read("email", "uidvalidity", "");
            long lastUid = state.readLong("email", "lastuid", 0);
            if (!Long.toString(uidValidity).equals(prevUv)) {
                lastUid = 0;
                state.write("email", "uidvalidity", Long.toString(uidValidity));
            }
            r.maxUid = lastUid;

            Date since = Date.from(Instant.now().minusSeconds((long) cfg.eventHours * 3600));
            Message[] found = inbox.search(new ReceivedDateTerm(ComparisonTerm.GE, since));

            int used = 0;
            for (Message msg : found) {
                if (used >= cfg.maxEmails) break;
                long uid = uf.getUID(msg);
                if (uid <= lastUid) continue;

                String from = firstFrom(msg);
                String subj = safeSubject(msg);
                Date sent = sentDate(msg);
                String body = bodyText(msg);

                if (uid > r.maxUid) r.maxUid = uid;
                if (subj.isEmpty() && body.isEmpty()) continue;

                Instant when = sent != null ? sent.toInstant() : Instant.now();
                String stamp = time.localStampMin(when);

                boolean sms = subj.contains(cfg.smsTag);
                String label = sms ? "forwarded TEXT" : "EMAIL";
                StringBuilder entry = new StringBuilder("[" + stamp + " " + time.tzLabel() + "] "
                        + label + " from " + from + "  Subject: " + subj);
                if (!body.isEmpty()) entry.append("\n").append(body);

                r.entries.add(entry.toString());
                used++;
                Log.info("  + email uid " + uid + " from " + from + (sms ? " " + cfg.smsTag : "")
                        + " @ " + stamp);
                if (sms) r.smsUids.add(uid);
            }
            inbox.close(false);
            Log.info("Email: added " + used + " message(s) from the inbox.");
        } catch (Exception e) {
            Log.info("Email: IMAP read failed (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") - continuing without the email source.");
        } finally {
            if (store != null) try { store.close(); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Move processed forwarded-text emails to Trash (copy + \Deleted + expunge). */
    public void sweep(List<Long> uids) {
        if (uids == null || uids.isEmpty()) return;
        String pass = Util.readFile(cfg.imapPassFile).strip();
        if (pass.isEmpty()) return;

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.ssl.enable", "true");

        Store store = null;
        int ok = 0, fail = 0;
        try {
            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(cfg.imapHost, cfg.imapPort, cfg.imapUser, pass);

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            Folder trash = store.getFolder(cfg.imapTrash);
            UIDFolder uf = (UIDFolder) inbox;

            long[] arr = uids.stream().mapToLong(Long::longValue).toArray();
            Message[] msgs = uf.getMessagesByUID(arr);
            List<Message> real = new ArrayList<>();
            for (Message m : msgs) if (m != null) real.add(m);
            if (!real.isEmpty()) {
                Message[] m = real.toArray(new Message[0]);
                try { inbox.copyMessages(m, trash); } catch (Exception e) {
                    Log.info("  ! copy to Trash failed: " + e.getMessage());
                }
                for (Message x : m) { try { x.setFlag(Flags.Flag.DELETED, true); ok++; } catch (Exception e) { fail++; } }
                inbox.expunge();
            }
            inbox.close(true);
            Log.info("Email: swept " + ok + " forwarded text(s) to " + cfg.imapTrash
                    + (fail > 0 ? (", " + fail + " failed") : ""));
        } catch (Exception e) {
            Log.info("Email: sweep failed (" + e.getMessage() + ").");
        } finally {
            if (store != null) try { store.close(); } catch (Exception ignored) {}
        }
    }

    // ---- helpers ------------------------------------------------------------
    private static String firstFrom(Message m) {
        try {
            javax.mail.Address[] a = m.getFrom();
            if (a != null && a.length > 0) return MimeUtility.decodeText(a[0].toString());
        } catch (Exception ignored) {}
        return "";
    }
    private static String safeSubject(Message m) {
        try { String s = m.getSubject(); return s == null ? "" : s; } catch (Exception e) { return ""; }
    }
    private static Date sentDate(Message m) {
        try { Date d = m.getSentDate(); if (d != null) return d; } catch (Exception ignored) {}
        try { return m.getReceivedDate(); } catch (Exception e) { return null; }
    }

    /** Best-effort plain text from a message, then the same cleanup/caps as
     *  _EmailBodyText (strip HTML, decode entities, collapse, cap 3000). */
    private String bodyText(Message m) {
        String raw;
        try { raw = extract(m); } catch (Exception e) { raw = ""; }
        if (raw == null) raw = "";
        if (raw.length() > 20000) raw = raw.substring(0, 20000);
        String s = raw
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        s = Util.decodeEntities(s);
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("(\\r?\\n[ \\t]*){2,}", "\n").strip();
        if (s.length() > 3000) s = s.substring(0, 3000) + " ...[truncated]";
        return s;
    }

    /** Recursively pull text: prefer text/plain, else text/html, else join parts. */
    private String extract(Part p) throws Exception {
        if (p.isMimeType("text/plain")) {
            Object c = p.getContent();
            return c == null ? "" : c.toString();
        }
        if (p.isMimeType("text/html")) {
            Object c = p.getContent();
            return c == null ? "" : c.toString();
        }
        if (p.isMimeType("multipart/alternative")) {
            Multipart mp = (Multipart) p.getContent();
            String html = "";
            for (int i = 0; i < mp.getCount(); i++) {
                Part bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) {
                    Object c = bp.getContent();
                    if (c != null && !c.toString().isBlank()) return c.toString();  // plain wins
                } else if (bp.isMimeType("text/html")) {
                    Object c = bp.getContent();
                    if (c != null) html = c.toString();
                } else {
                    String nested = extract(bp);
                    if (!nested.isBlank()) html = nested;
                }
            }
            return html;
        }
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                String part = extract(mp.getBodyPart(i));
                if (!part.isBlank()) sb.append(part).append("\n");
            }
            return sb.toString();
        }
        return "";
    }
}
