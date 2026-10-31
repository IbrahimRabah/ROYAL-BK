package com.velora.api.catalog.imageimport.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an image file name into «which SKU, which place in the gallery».
 *
 * <p>The rule, which the image import screen documents:
 * <ul>
 *   <li>{@code SKU.ext} is the main image (and the only one, for most products).</li>
 *   <li>{@code SKU_2.ext}, {@code SKU_3.ext} … are extra gallery images, in that order.</li>
 * </ul>
 * Only the LAST extension is dropped and whatever it is does not matter: the extension is the one
 * part of a file name nobody can rely on. What the file really is gets decided later, from its bytes.
 *
 * <p>Pure functions, no database: the caller says which SKUs exist ({@code isKnownSku}), so the
 * rules can be tested exhaustively.
 */
public final class ImageNameMatcher {

    /** The gallery holds at most this many images, so a suffix above it is not an image number. */
    public static final int MAX_POSITION = 20;

    /** {@code SKU_2}: the suffix is {@code _} and one or two digits. A hyphen would clash with SKUs that have one. */
    private static final Pattern POSITION_SUFFIX = Pattern.compile("^(.+)_(\\d{1,2})$");

    /** What Drive and file managers append to a copy: «CH101 (1)», «CH101 - Copy», «CH101 copy (2)». */
    private static final Pattern COPY_NOISE =
            Pattern.compile("(?i)(\\s*[-_]?\\s*(copy|نسخة)(\\s*\\(\\d+\\))?|\\s*\\(\\d+\\))+$");

    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]");

    /** Formats a phone produces that the browser cannot turn into a web image. */
    private static final Set<String> UNSUPPORTED_FORMATS =
            Set.of("heic", "heif", "tif", "tiff", "svg", "psd", "raw", "cr2", "nef", "arw", "dng");

    /** Files that sit in a folder next to the pictures and are not pictures. */
    private static final Set<String> NOT_IMAGES = Set.of("db", "ini", "tmp", "txt", "pdf", "zip", "rar", "7z",
            "xls", "xlsx", "csv", "doc", "docx", "json", "lnk", "url", "ds_store");

    private ImageNameMatcher() {
    }

    /** A file name taken apart. {@code stem} is normalised for comparison; {@code extension} is lower case. */
    public record ParsedName(String original, String stem, String extension) {

        public boolean hasExtension() {
            return !extension.isEmpty();
        }
    }

    public enum Kind { IMAGE, UNSUPPORTED_FORMAT, NOT_AN_IMAGE }

    /** The SKU a name points at and the gallery place it asks for (1 = main). */
    public record Match(String sku, int position) {
    }

    public static ParsedName parse(String fileName) {
        String name = fileName == null ? "" : fileName;
        // A browser may hand over «folder/CH101.jpg»; only the file name counts.
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFKC);
        name = INVISIBLE.matcher(name).replaceAll("").trim();
        name = toAsciiDigits(name);

        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot + 1).trim().toLowerCase(Locale.ROOT) : "";
        return new ParsedName(fileName == null ? "" : fileName, stem.trim().toUpperCase(Locale.ROOT), extension);
    }

    /** What the extension says the file is. An unknown extension is given the benefit of the doubt. */
    public static Kind kind(ParsedName name) {
        if (name.original().isBlank() || name.stem().isEmpty()
                || name.original().startsWith(".") || name.original().startsWith("~$")) {
            return Kind.NOT_AN_IMAGE;
        }
        if (UNSUPPORTED_FORMATS.contains(name.extension())) {
            return Kind.UNSUPPORTED_FORMAT;
        }
        return NOT_IMAGES.contains(name.extension()) ? Kind.NOT_AN_IMAGE : Kind.IMAGE;
    }

    /**
     * The SKUs this name could stand for, to look up in one query: the whole stem, and the stem without a
     * {@code _N} suffix. The matcher itself decides which one is meant.
     */
    public static List<String> candidates(ParsedName name) {
        List<String> list = new ArrayList<>();
        list.add(name.stem());
        Matcher m = POSITION_SUFFIX.matcher(name.stem());
        if (m.matches()) {
            list.add(m.group(1).trim());
        }
        return list;
    }

    /**
     * @return the SKU and position, or empty when the name matches no known SKU. A name that fits two
     *         readings (a SKU {@code X_2} AND a SKU {@code X}) is {@link Ambiguous}, never guessed.
     */
    public static Optional<Match> resolve(ParsedName name, Predicate<String> isKnownSku) {
        Optional<Match> whole = isKnownSku.test(name.stem()) ? Optional.of(new Match(name.stem(), 1)) : Optional.empty();
        Optional<Match> suffixed = suffixed(name, isKnownSku);
        if (whole.isPresent() && suffixed.isPresent()) {
            throw new Ambiguous(name.original());
        }
        return whole.isPresent() ? whole : suffixed;
    }

    private static Optional<Match> suffixed(ParsedName name, Predicate<String> isKnownSku) {
        Matcher m = POSITION_SUFFIX.matcher(name.stem());
        if (!m.matches()) {
            return Optional.empty();
        }
        int position = Integer.parseInt(m.group(2));
        String sku = m.group(1).trim();
        if (position < 1 || position > MAX_POSITION || !isKnownSku.test(sku)) {
            return Optional.empty();
        }
        return Optional.of(new Match(sku, position));
    }

    /**
     * A near miss worth showing the person («CH101 (1).jpg» is probably CH101), never attached by itself.
     * Looks past Drive's copy markers, a doubled extension («CH101.jpg.jpg») and stray spaces.
     */
    public static Optional<Match> suggest(ParsedName name, Predicate<String> isKnownSku) {
        String stem = name.stem();
        // «CH101.jpg.jpg»: the stem still ends in an image extension.
        int dot = stem.lastIndexOf('.');
        if (dot > 0 && Kind.IMAGE == kind(new ParsedName(stem, stem.substring(0, dot),
                stem.substring(dot + 1).toLowerCase(Locale.ROOT)))) {
            stem = stem.substring(0, dot);
        }
        stem = COPY_NOISE.matcher(stem).replaceAll("").trim();
        String squashed = stem.replaceAll("\\s+", "");
        for (String attempt : new String[] {stem, squashed}) {
            if (attempt.isEmpty()) {
                continue;
            }
            ParsedName retry = new ParsedName(name.original(), attempt, name.extension());
            try {
                Optional<Match> found = resolve(retry, isKnownSku);
                if (found.isPresent()) {
                    return found;
                }
            } catch (Ambiguous ignored) {
                // an ambiguous guess is no suggestion
            }
        }
        return Optional.empty();
    }

    /** Every SKU an unmatched name might be a near miss of, so the caller can look them all up at once. */
    public static List<String> suggestionCandidates(ParsedName name) {
        String stem = name.stem();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        stem = COPY_NOISE.matcher(stem).replaceAll("").trim();
        List<String> list = new ArrayList<>();
        for (String s : new String[] {stem, stem.replaceAll("\\s+", "")}) {
            if (s.isEmpty()) {
                continue;
            }
            list.add(s);
            Matcher m = POSITION_SUFFIX.matcher(s);
            if (m.matches()) {
                list.add(m.group(1).trim());
            }
        }
        return list;
    }

    private static String toAsciiDigits(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '٠' && c <= '٩') {
                sb.append((char) ('0' + (c - '٠')));
            } else if (c >= '۰' && c <= '۹') {
                sb.append((char) ('0' + (c - '۰')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The name reads as two different SKUs; the person has to rename one. */
    public static final class Ambiguous extends RuntimeException {

        public Ambiguous(String fileName) {
            super("«" + fileName + "» fits two SKUs", null, false, false);
        }
    }
}
