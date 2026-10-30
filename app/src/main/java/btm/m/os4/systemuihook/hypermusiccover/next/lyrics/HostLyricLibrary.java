// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import java.util.List;
import java.util.Collections;

/** HyperChanger's user-managed library, preferred over catalogue and session lyrics. */
final class HostLyricLibrary {
    private HostLyricLibrary() {}
    static List<LyricLine> load(android.content.Context context, NcmLyrics.Query wanted) {
        if (context == null) {
            return Collections.<LyricLine>emptyList();
        }
        android.database.Cursor cursor = null;
        try {
            android.net.Uri library = android.net.Uri.parse(
                    "content://btm.m.os4.systemuihook.settingsappearance/lyrics");
            cursor = context.getContentResolver().query(library, null, null, null, null);
            if (cursor == null) {
                return Collections.<LyricLine>emptyList();
            }
            int idColumn = cursor.getColumnIndex("id");
            int titleColumn = cursor.getColumnIndex("title");
            int artistColumn = cursor.getColumnIndex("artist");
            int aliasesColumn = cursor.getColumnIndex("aliases");
            final class LocalCandidate {
                final String id;
                final int score;
                LocalCandidate(String id, int score) {
                    this.id = id;
                    this.score = score;
                }
            }
            LocalCandidate exactCandidate = null;
            LocalCandidate fallbackCandidate = null;
            String wantedTitle = normalize(wanted.title);
            String wantedArtist = normalize(wanted.artist);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idColumn);
                String artist = normalize(cursor.getString(artistColumn));
                if (id == null || artist.isEmpty() || wantedArtist.isEmpty()) continue;
                int artistMatch = artistMatchLength(artist, wantedArtist);
                if (artistMatch < requiredArtistMatchLength(artist, wantedArtist)) continue;

                String candidateTitle = normalize(cursor.getString(titleColumn));
                boolean titleExact = !wantedTitle.isEmpty() && candidateTitle.equals(wantedTitle);
                double titleMatch = titleSimilarity(candidateTitle, wantedTitle);
                String aliases = aliasesColumn < 0 ? "" : cursor.getString(aliasesColumn);
                if (aliases != null && !aliases.trim().isEmpty()) {
                    for (String alias : aliases.split("[,，]")) {
                        String normalizedAlias = normalize(alias);
                        if (!wantedTitle.isEmpty() && normalizedAlias.equals(wantedTitle)) {
                            titleExact = true;
                        }
                        titleMatch = Math.max(titleMatch, titleSimilarity(normalizedAlias, wantedTitle));
                    }
                }
                if (titleMatch < 0.70d) continue;
                int candidateScore = (int) Math.round(titleMatch * 100)
                        + (artist.equals(wantedArtist) ? 50 : 20 + Math.min(artistMatch, 20));
                LocalCandidate candidate = new LocalCandidate(id, candidateScore);
                if (titleExact) {
                    if (exactCandidate == null || candidate.score > exactCandidate.score) {
                        exactCandidate = candidate;
                    }
                } else if (fallbackCandidate == null || candidate.score > fallbackCandidate.score) {
                    fallbackCandidate = candidate;
                }
            }
            LocalCandidate selected = exactCandidate != null ? exactCandidate : fallbackCandidate;
            if (selected == null) {
                return Collections.<LyricLine>emptyList();
            }
            android.net.Uri file = library.buildUpon().appendPath(selected.id).build();
            java.io.InputStream input = context.getContentResolver().openInputStream(file);
            if (input == null) {
                return Collections.<LyricLine>emptyList();
            }
            try (java.io.InputStream owned = input) {
                return LyricParse.parse(new String(owned.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Xp.log("[MCLyric] local library failed: " + t);
            return Collections.<LyricLine>emptyList();
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static double titleSimilarity(String first, String second) {
        if (first.equals(second)) return 1d;
        int longest = Math.max(first.length(), second.length());
        if (longest == 0) return 1d;
        if (first.isEmpty() || second.isEmpty()) return 0d;
        int[] previous = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) previous[j] = j;
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int substitution = previous[j - 1]
                        + (first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1),
                        substitution);
            }
            previous = current;
        }
        return 1d - (double) previous[second.length()] / longest;
    }

    private static int artistMatchLength(String first, String second) {
        if (first.isEmpty() || second.isEmpty()) return 0;
        int[] previous = new int[second.length() + 1];
        int best = 0;
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            for (int j = 1; j <= second.length(); j++) {
                if (first.charAt(i - 1) == second.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    best = Math.max(best, current[j]);
                }
            }
            previous = current;
        }
        return best;
    }

    private static int requiredArtistMatchLength(String first, String second) {
        int shorter = Math.min(first.length(), second.length());
        if (shorter <= 2) return shorter;
        return containsIdeograph(first) || containsIdeograph(second) ? 2 : 3;
    }

    private static boolean containsIdeograph(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]", "");
    }
}
