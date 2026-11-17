package service.transfer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Reads the output of WinSCP's {@code ls} (Unix {@code ls -l} style) into file sizes and times.
 * Pure text processing, kept apart from the process handling so it can be tested without WinSCP.
 */
final class WinScpListing {

    private WinScpListing() {}

    /**
     * @param rawLines output lines of {@code ls "dirPath"}; anything that is not a file line is ignored
     * @param dirPath  the directory that was listed, prepended to each name to form the key
     * @param now      "now", used to resolve the year of entries shown as {@code Mon DD HH:MM}
     */
    static Map<String, RemoteFileStat> parse(List<String> rawLines, String dirPath, LocalDateTime now) {
        java.util.Map<String, Integer> MONTH_MAP = new java.util.HashMap<>();
        MONTH_MAP.put("jan",1); MONTH_MAP.put("feb",2); MONTH_MAP.put("mar",3);
        MONTH_MAP.put("apr",4); MONTH_MAP.put("may",5); MONTH_MAP.put("jun",6);
        MONTH_MAP.put("jul",7); MONTH_MAP.put("aug",8); MONTH_MAP.put("sep",9);
        MONTH_MAP.put("oct",10);MONTH_MAP.put("nov",11);MONTH_MAP.put("dec",12);

        int currentYear = now.getYear();
        java.util.Map<String, RemoteFileStat> fileStats = new java.util.LinkedHashMap<>();

        for (String line : rawLines) {
            String t = line.trim();
            if (t.isEmpty() || !t.startsWith("-")) continue;
            String[] parts = t.split("\\s+", 10);
            if (parts.length < 9) continue;

            long sizeBytes;
            try { sizeBytes = Long.parseLong(parts[4]); } catch (NumberFormatException e) { sizeBytes = 0L; }

            String monthStr   = parts[5].toLowerCase(java.util.Locale.ENGLISH);
            String dayStr     = parts[6].trim();
            String timeOrYear = parts[7];
            String fileName   = afterTokens(t, 8);   // everything after 8 columns: names may contain spaces

            Integer monthNum = MONTH_MAP.get(monthStr);
            if (monthNum == null) continue;
            int dayNum;
            try { dayNum = Integer.parseInt(dayStr); } catch (NumberFormatException e) { continue; }

            // NOTE: these timestamps are parsed as if `ls -l` reported them in this
            // JVM's local zone (java.time.ZoneId.systemDefault()) rather than UTC.
            // That has to match the zone used later to bucket files into day-folders
            // (see enumerateLocalBackupCandidates/enumerateRemoteBackupCandidates,
            // which also use ZoneId.systemDefault()) — otherwise a file near a day
            // boundary gets parsed in one zone and bucketed in another, and ends up
            // filed under the wrong calendar day (e.g. a D-1 file landing in the D
            // folder). If the remote server's clock is known to run in a different
            // zone than this machine, that zone should become a configurable setting
            // here instead of an assumption.
            long epochMillis;
            try {
                if (timeOrYear.contains(":")) {
                    int colons = (int) timeOrYear.chars().filter(c -> c == ':').count();
                    if (colons == 2) {
                        if (parts.length < 10) continue;
                        int year = Integer.parseInt(parts[8]);
                        fileName = afterTokens(t, 9);
                        String[] tp = timeOrYear.split(":");
                        epochMillis = LocalDateTime.of(year, monthNum, dayNum,
                                        Integer.parseInt(tp[0]), Integer.parseInt(tp[1]),
                                        Integer.parseInt(tp[2]))
                                .atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli();
                    } else {
                        String[] tp = timeOrYear.split(":");
                        LocalDateTime ldt = LocalDateTime.of(currentYear, monthNum, dayNum,
                                Integer.parseInt(tp[0]), Integer.parseInt(tp[1]), 0);
                        if (ldt.isAfter(now)) ldt = ldt.minusYears(1);
                        epochMillis = ldt.atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli();
                    }
                } else {
                    LocalDate ld = LocalDate.of(Integer.parseInt(timeOrYear), monthNum, dayNum);
                    epochMillis = ld.atStartOfDay(java.time.ZoneId.systemDefault())
                            .toInstant().toEpochMilli();
                }
            } catch (Exception ex) { continue; }

            fileStats.put(dirPath + fileName, new RemoteFileStat(epochMillis, sizeBytes));
        }

        return fileStats;
    }

    /** The text left after skipping {@code n} whitespace-separated tokens (keeps inner spaces of the remainder). */
    static String afterTokens(String t, int n) {
        int i = 0, len = t.length();
        for (int k = 0; k < n; k++) {
            while (i < len && Character.isWhitespace(t.charAt(i))) i++;
            while (i < len && !Character.isWhitespace(t.charAt(i))) i++;
        }
        while (i < len && Character.isWhitespace(t.charAt(i))) i++;
        return t.substring(i);
    }
}
