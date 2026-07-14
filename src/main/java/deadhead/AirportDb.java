package deadhead;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up airports in the OurAirports public-domain database
 * (data/airports.csv) so any code dad writes in his calendar just works:
 * ICAO ("KADS"), local ("ADS"), or IATA ("DAL").
 */
public class AirportDb {

    public record Info(String ident, String name, String municipality, double lat, double lon) {}

    private final Map<String, Info> byCode = new HashMap<>();
    private final List<Info> all = new ArrayList<>();

    public AirportDb(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        String[] header = parseCsvLine(lines.get(0));
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) col.put(header[i], i);

        int ident = col.get("ident"), type = col.get("type"), name = col.get("name"),
            lat = col.get("latitude_deg"), lon = col.get("longitude_deg"),
            country = col.get("iso_country"), muni = col.get("municipality"),
            iata = col.get("iata_code"), local = col.get("local_code");

        for (int i = 1; i < lines.size(); i++) {
            String[] f = parseCsvLine(lines.get(i));
            if (f.length <= local) continue;
            if (f[type].contains("heliport") || f[type].equals("closed")) continue;

            Info info = new Info(f[ident], f[name], f[muni],
                Double.parseDouble(f[lat]), Double.parseDouble(f[lon]));
            all.add(info);

            byCode.putIfAbsent(f[ident].toUpperCase(), info);   // ICAO, e.g. KADS
            // local + IATA codes collide worldwide; only trust them for US fields
            if (f[country].equals("US")) {
                if (!f[local].isEmpty()) byCode.putIfAbsent(f[local].toUpperCase(), info);
                if (!f[iata].isEmpty()) byCode.putIfAbsent(f[iata].toUpperCase(), info);
            }
        }
    }

    public Info resolve(String code) {
        Info info = byCode.get(code.toUpperCase().trim());
        if (info == null) {
            throw new IllegalArgumentException(
                "unknown airport code: " + code + " (try the ICAO code, e.g. KADS)");
        }
        return info;
    }

    /** Closest airport to a position — turns an ADS-B fix into "the plane is at X". */
    public Info nearest(double lat, double lon) {
        Info best = null;
        double bestH = Double.MAX_VALUE;
        for (Info i : all) {
            double dLat = Math.toRadians(i.lat() - lat);
            double dLon = Math.toRadians(i.lon() - lon);
            double h = Math.pow(Math.sin(dLat / 2), 2)
                     + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(i.lat()))
                     * Math.pow(Math.sin(dLon / 2), 2);
            if (h < bestH) { bestH = h; best = i; }
        }
        return best;
    }

    /** Minimal CSV field splitter that respects quoted fields ("Dallas, TX"). */
    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); i++;               // escaped quote
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }
}
