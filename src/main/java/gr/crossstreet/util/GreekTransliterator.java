package gr.crossstreet.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transliterates Greek text to Latin script using ELOT 743-like rules.
 * Handles digraphs, diphthongs, accented vowels, and final sigma.
 */
public final class GreekTransliterator {

    private GreekTransliterator() {}

    // Ordered map: multi-char mappings first to avoid partial matches
    private static final Map<String, String> GREEK_TO_LATIN = new LinkedHashMap<>();
    static {
        // Diphthongs and digraphs (must come before single chars)
        GREEK_TO_LATIN.put("\u03bc\u03c0", "b");    // μπ → b
        GREEK_TO_LATIN.put("\u039c\u03c0", "B");    // Μπ → B
        GREEK_TO_LATIN.put("\u039c\u03a0", "B");    // ΜΠ → B
        GREEK_TO_LATIN.put("\u03bd\u03c4", "nt");   // ντ → nt
        GREEK_TO_LATIN.put("\u039d\u03c4", "Nt");   // Ντ → Nt
        GREEK_TO_LATIN.put("\u03b3\u03ba", "gk");   // γκ → gk
        GREEK_TO_LATIN.put("\u0393\u03ba", "Gk");   // Γκ → Gk
        GREEK_TO_LATIN.put("\u03b3\u03b3", "ng");   // γγ → ng
        GREEK_TO_LATIN.put("\u03bf\u03c5", "ou");   // ου → ou
        GREEK_TO_LATIN.put("\u039f\u03c5", "Ou");   // Ου → Ou
        GREEK_TO_LATIN.put("\u039f\u03a5", "OU");   // ΟΥ → OU
        GREEK_TO_LATIN.put("\u03b1\u03c5", "av");   // αυ → av
        GREEK_TO_LATIN.put("\u0391\u03c5", "Av");   // Αυ → Av
        GREEK_TO_LATIN.put("\u03b5\u03c5", "ev");   // ευ → ev
        GREEK_TO_LATIN.put("\u0395\u03c5", "Ev");   // Ευ → Ev
        GREEK_TO_LATIN.put("\u03b1\u03b9", "ai");   // αι → ai
        GREEK_TO_LATIN.put("\u0391\u03b9", "Ai");   // Αι → Ai
        GREEK_TO_LATIN.put("\u03b5\u03b9", "ei");   // ει → ei
        GREEK_TO_LATIN.put("\u0395\u03b9", "Ei");   // Ει → Ei
        GREEK_TO_LATIN.put("\u03bf\u03b9", "oi");   // οι → oi
        GREEK_TO_LATIN.put("\u039f\u03b9", "Oi");   // Οι → Oi
        GREEK_TO_LATIN.put("\u03c4\u03c3", "ts");   // τσ → ts
        GREEK_TO_LATIN.put("\u03c4\u03b6", "tz");   // τζ → tz

        // Single uppercase
        GREEK_TO_LATIN.put("\u0391", "A");     // Α
        GREEK_TO_LATIN.put("\u0392", "V");     // Β
        GREEK_TO_LATIN.put("\u0393", "G");     // Γ
        GREEK_TO_LATIN.put("\u0394", "D");     // Δ
        GREEK_TO_LATIN.put("\u0395", "E");     // Ε
        GREEK_TO_LATIN.put("\u0396", "Z");     // Ζ
        GREEK_TO_LATIN.put("\u0397", "I");     // Η
        GREEK_TO_LATIN.put("\u0398", "Th");    // Θ
        GREEK_TO_LATIN.put("\u0399", "I");     // Ι
        GREEK_TO_LATIN.put("\u039a", "K");     // Κ
        GREEK_TO_LATIN.put("\u039b", "L");     // Λ
        GREEK_TO_LATIN.put("\u039c", "M");     // Μ
        GREEK_TO_LATIN.put("\u039d", "N");     // Ν
        GREEK_TO_LATIN.put("\u039e", "X");     // Ξ
        GREEK_TO_LATIN.put("\u039f", "O");     // Ο
        GREEK_TO_LATIN.put("\u03a0", "P");     // Π
        GREEK_TO_LATIN.put("\u03a1", "R");     // Ρ
        GREEK_TO_LATIN.put("\u03a3", "S");     // Σ
        GREEK_TO_LATIN.put("\u03a4", "T");     // Τ
        GREEK_TO_LATIN.put("\u03a5", "Y");     // Υ
        GREEK_TO_LATIN.put("\u03a6", "F");     // Φ
        GREEK_TO_LATIN.put("\u03a7", "Ch");    // Χ
        GREEK_TO_LATIN.put("\u03a8", "Ps");    // Ψ
        GREEK_TO_LATIN.put("\u03a9", "O");     // Ω

        // Single lowercase
        GREEK_TO_LATIN.put("\u03b1", "a");     // α
        GREEK_TO_LATIN.put("\u03b2", "v");     // β
        GREEK_TO_LATIN.put("\u03b3", "g");     // γ
        GREEK_TO_LATIN.put("\u03b4", "d");     // δ
        GREEK_TO_LATIN.put("\u03b5", "e");     // ε
        GREEK_TO_LATIN.put("\u03b6", "z");     // ζ
        GREEK_TO_LATIN.put("\u03b7", "i");     // η
        GREEK_TO_LATIN.put("\u03b8", "th");    // θ
        GREEK_TO_LATIN.put("\u03b9", "i");     // ι
        GREEK_TO_LATIN.put("\u03ba", "k");     // κ
        GREEK_TO_LATIN.put("\u03bb", "l");     // λ
        GREEK_TO_LATIN.put("\u03bc", "m");     // μ
        GREEK_TO_LATIN.put("\u03bd", "n");     // ν
        GREEK_TO_LATIN.put("\u03be", "x");     // ξ
        GREEK_TO_LATIN.put("\u03bf", "o");     // ο
        GREEK_TO_LATIN.put("\u03c0", "p");     // π
        GREEK_TO_LATIN.put("\u03c1", "r");     // ρ
        GREEK_TO_LATIN.put("\u03c2", "s");     // ς (final sigma)
        GREEK_TO_LATIN.put("\u03c3", "s");     // σ
        GREEK_TO_LATIN.put("\u03c4", "t");     // τ
        GREEK_TO_LATIN.put("\u03c5", "y");     // υ
        GREEK_TO_LATIN.put("\u03c6", "f");     // φ
        GREEK_TO_LATIN.put("\u03c7", "ch");    // χ
        GREEK_TO_LATIN.put("\u03c8", "ps");    // ψ
        GREEK_TO_LATIN.put("\u03c9", "o");     // ω

        // Accented vowels
        GREEK_TO_LATIN.put("\u03ac", "a");     // ά
        GREEK_TO_LATIN.put("\u03ad", "e");     // έ
        GREEK_TO_LATIN.put("\u03ae", "i");     // ή
        GREEK_TO_LATIN.put("\u03af", "i");     // ί
        GREEK_TO_LATIN.put("\u03cc", "o");     // ό
        GREEK_TO_LATIN.put("\u03cd", "y");     // ύ
        GREEK_TO_LATIN.put("\u03ce", "o");     // ώ
        GREEK_TO_LATIN.put("\u0390", "i");     // ΐ
        GREEK_TO_LATIN.put("\u03b0", "y");     // ΰ
        GREEK_TO_LATIN.put("\u03ca", "i");     // ϊ
        GREEK_TO_LATIN.put("\u03cb", "y");     // ϋ
        GREEK_TO_LATIN.put("\u0386", "A");     // Ά
        GREEK_TO_LATIN.put("\u0388", "E");     // Έ
        GREEK_TO_LATIN.put("\u0389", "I");     // Ή
        GREEK_TO_LATIN.put("\u038a", "I");     // Ί
        GREEK_TO_LATIN.put("\u038c", "O");     // Ό
        GREEK_TO_LATIN.put("\u038e", "Y");     // Ύ
        GREEK_TO_LATIN.put("\u038f", "O");     // Ώ
    }

    /**
     * Transliterates Greek text to Latin script.
     * Non-Greek characters (spaces, digits, punctuation) pass through unchanged.
     *
     * @param greek the input text (may contain mixed Greek/Latin/other characters)
     * @return the transliterated string
     */
    public static String transliterate(String greek) {
        if (greek == null || greek.isEmpty()) return greek;

        StringBuilder sb = new StringBuilder(greek.length());
        int i = 0;
        while (i < greek.length()) {
            boolean matched = false;
            // Try 2-char digraphs first
            if (i + 1 < greek.length()) {
                String pair = greek.substring(i, i + 2);
                String mapped = GREEK_TO_LATIN.get(pair);
                if (mapped != null) {
                    sb.append(mapped);
                    i += 2;
                    matched = true;
                }
            }
            if (!matched) {
                String single = greek.substring(i, i + 1);
                String mapped = GREEK_TO_LATIN.get(single);
                sb.append(mapped != null ? mapped : single);
                i++;
            }
        }
        return sb.toString();
    }
}