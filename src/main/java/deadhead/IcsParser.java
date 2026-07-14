package deadhead;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls events (start time + human text) out of an Apple/Google .ics export. */
public class IcsParser {

    public record Event(LocalDateTime start, String text) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final Pattern TZID = Pattern.compile("TZID=([^;:]+)");

    public static List<Event> parse(String ics) {
        // "unfold": ICS wraps long lines; continuations start with a space/tab
        String flat = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "");

        List<Event> events = new ArrayList<>();
        for (String block : flat.split("BEGIN:VEVENT")) {
            if (!block.contains("DTSTART")) continue;
            LocalDateTime start = null;
            StringBuilder text = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("DTSTART")) start = parseDtStart(line);
                else if (line.startsWith("SUMMARY:")) text.append(unescape(line.substring(8))).append(' ');
                else if (line.startsWith("LOCATION:")) text.append(unescape(line.substring(9))).append(' ');
                else if (line.startsWith("DESCRIPTION:")) text.append(unescape(line.substring(12))).append(' ');
            }
            if (start != null && !text.isEmpty()) events.add(new Event(start, text.toString().strip()));
        }
        events.sort((a, b) -> a.start().compareTo(b.start()));
        return events;
    }

    static LocalDateTime parseDtStart(String line) {
        int colon = line.lastIndexOf(':');
        String value = line.substring(colon + 1).strip();
        String params = line.substring(0, colon);

        if (value.matches("\\d{8}")) {   // all-day event
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atTime(9, 0);
        }
        // Times with a TZID are kept as written — the calendar's wall-clock time IS
        // the pilot's schedule; converting to this machine's timezone would shift it.
        // Only pure-UTC stamps (trailing Z, e.g. Google exports) get converted.
        boolean utc = value.endsWith("Z");
        LocalDateTime ldt = LocalDateTime.parse(utc ? value.substring(0, value.length() - 1) : value, STAMP);
        if (utc) {
            Matcher m = TZID.matcher(params);
            ZoneId target = ZoneId.systemDefault();
            if (m.find()) {
                try { target = ZoneId.of(m.group(1)); } catch (Exception ignored) { }
            }
            return ldt.atZone(ZoneOffset.UTC).withZoneSameInstant(target).toLocalDateTime();
        }
        return ldt;
    }

    private static String unescape(String s) {
        return s.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").strip();
    }
}
