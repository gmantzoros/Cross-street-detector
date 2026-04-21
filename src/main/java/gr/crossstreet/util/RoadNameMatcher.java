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
     */
    public static boolean fuzzyMatch(String a, String b) {
        if (a == null || b == null) return false;

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
        int li = 0;
        for (String sw : shorter) {
            boolean matched = false;
            while (li < longer.length) {
                if (longer[li].startsWith(sw)) {
                    matched = true;
                    li++;
                    break;
                }
                li++;
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