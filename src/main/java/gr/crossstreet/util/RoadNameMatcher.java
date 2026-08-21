package gr.crossstreet.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared fuzzy matching logic for Greek road names.
 * Handles accent stripping, abbreviations, and last-word suffix matching.
 */
public final class RoadNameMatcher {

    private RoadNameMatcher() {}

    /**
     * Fuzzy road name match handling Greek transliteration variants, abbreviations,
     * and last-word suffix matching.
     *
     * <p>When exactly one side is written in Greek script (e.g. an OSM {@code name} tag
     * compared against a Latin-script benchmark annotation), both sides are transliterated
     * to Latin and folded to a phonetic skeleton before matching. Same-script comparisons
     * are unaffected.</p>
     */
    public static boolean fuzzyMatch(String a, String b) {
        if (a == null || b == null) return false;

        if (matchSameScript(a, b)) return true;

        if (hasGreek(a) != hasGreek(b)) {
            return matchSameScript(
                    foldLatinVariants(GreekTransliterator.transliterate(a)),
                    foldLatinVariants(GreekTransliterator.transliterate(b)));
        }
        return false;
    }

    /**
     * Collapses informal "greeklish" spelling variants onto a coarse phonetic skeleton.
     *
     * <p>ELOT 743 transliteration and hand-written Latin annotations disagree on several
     * letters — ELOT renders η as {@code i} and χ as {@code ch}, where informal spelling
     * commonly uses {@code h} and {@code x}. Folding both sides removes that disagreement
     * (e.g. {@code Tilemachou} and {@code Thlemaxou} both become {@code tlemaku}).</p>
     *
     * <p>Applied only to the cross-script comparison path, never to Greek-script matching.</p>
     */
    private static String foldLatinVariants(String s) {
        String r = s.toLowerCase();
        // Digraphs first — order matters to avoid double-substitution
        r = r.replace("mp", "b");
        r = r.replace("mb", "b");
        r = r.replace("nt", "d");
        r = r.replace("gk", "g");
        r = r.replace("ou", "u");
        r = r.replace("ch", "k");
        r = r.replace("th", "t");
        r = r.replace("ph", "f");
        r = r.replace("ai", "e");
        r = r.replace("ei", "i");
        r = r.replace("oi", "i");
        // Single characters
        r = r.replace("j", "i");
        r = r.replace("y", "i");
        r = r.replace("x", "k");
        r = r.replace("w", "o");
        return r;
    }

    /** True if the string contains at least one character from the Greek Unicode block. */
    private static boolean hasGreek(String s) {
        return s.codePoints().anyMatch(cp -> Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.GREEK);
    }

    private static boolean matchSameScript(String a, String b) {
        String normA = normalize(a);
        String normB = normalize(b);

        if (normA.equals(normB)) return true;

        // Same words, different order (e.g., "Τσαλδάρη Κωνσταντίνου" vs "Κωνσταντίνου Τσαλδάρη")
        Set<String> setA = new HashSet<>(Arrays.asList(normA.split(" ")));
        Set<String> setB = new HashSet<>(Arrays.asList(normB.split(" ")));
        if (setA.equals(setB)) return true;

        if (normA.contains(normB) || normB.contains(normA)) return true;
        if (abbreviationMatch(normA, normB)) return true;

        // Last word often the most distinctive part of Greek street names
        String lastA = normA.contains(" ") ? normA.substring(normA.lastIndexOf(' ') + 1) : normA;
        String lastB = normB.contains(" ") ? normB.substring(normB.lastIndexOf(' ') + 1) : normB;
        if (levenshteinDistance(lastA, lastB) <= 2) return true;

        // Proportional edit distance: allow ~30% character differences
        int maxAllowed = Math.max(2, (int) (Math.min(normA.length(), normB.length()) * 0.3));
        return levenshteinDistance(normA, normB) <= maxAllowed;
    }

    /**
     * Lowercases and strips Greek tonos/accent marks for canonical comparison.
     */
    public static String normalize(String s) {
        // Decompose Unicode (e.g., ά → α + combining tonos) then strip combining marks
        String r = Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return r.replaceAll("\\s+", " ");
    }

    /**
     * Checks if one name is an abbreviation of the other.
     */
    private static boolean abbreviationMatch(String a, String b) {
        String[] wordsA = a.split(" ");
        String[] wordsB = b.split(" ");
        return abbreviationMatchDirectional(wordsA, wordsB) || abbreviationMatchDirectional(wordsB, wordsA);
    }

    private static boolean abbreviationMatchDirectional(String[] shorter, String[] longer) {
        if (shorter.length >= longer.length) return false;
        // Order-independent: each shorter word must match a distinct longer word
        // either as a prefix (abbreviation) or within edit distance 1 (spelling variant)
        boolean[] used = new boolean[longer.length];
        for (String sw : shorter) {
            boolean matched = false;
            for (int i = 0; i < longer.length; i++) {
                if (!used[i] && (longer[i].startsWith(sw) || levenshteinDistance(sw, longer[i]) <= 1)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    public static int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length(), len2 = s2.length();
        int[][] dp = new int[len1 + 1][len2 + 1];
        for (int i = 0; i <= len1; i++) dp[i][0] = i;
        for (int j = 0; j <= len2; j++) dp[0][j] = j;
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[len1][len2];
    }
}