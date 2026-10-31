package com.velora.api.catalog.imageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.catalog.imageimport.service.ImageNameMatcher;
import com.velora.api.catalog.imageimport.service.ImageNameMatcher.Kind;
import com.velora.api.catalog.imageimport.service.ImageNameMatcher.Match;
import com.velora.api.catalog.imageimport.service.ImageNameMatcher.ParsedName;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The naming rule is a contract with the people who name the files, so every corner of it is pinned here. */
class ImageNameMatcherTest {

    private static final Predicate<String> KNOWN = Set.of("CH101", "CH102", "CH-7", "X", "X_2", "A_B")::contains;

    private static Optional<Match> match(String fileName) {
        return ImageNameMatcher.resolve(ImageNameMatcher.parse(fileName), KNOWN);
    }

    @Test
    @DisplayName("The extension does not matter: only the last one is dropped, whatever it is")
    void anyExtension() {
        for (String name : new String[] {"CH101.jpg", "CH101.JPEG", "CH101.png", "CH101.webp", "CH101.avif",
                "CH101.xyz", "CH101", "CH101."}) {
            assertThat(match(name)).as(name).contains(new Match("CH101", 1));
        }
    }

    @Test
    @DisplayName("Case, spaces and invisible characters do not matter")
    void caseAndNoise() {
        assertThat(match("ch101.jpg")).contains(new Match("CH101", 1));
        assertThat(match("  Ch101 .jpg")).contains(new Match("CH101", 1));
        assertThat(match("CH​101.jpg")).contains(new Match("CH101", 1));
        // Full-width letters and Arabic-Indic digits, as typed on some keyboards.
        assertThat(match("ＣＨ１０１.jpg")).contains(new Match("CH101", 1));
        assertThat(match("CH١٠١.jpg")).contains(new Match("CH101", 1));
        // A path from a folder picker: only the file name counts.
        assertThat(match("كراسي بار/CH101.jpg")).contains(new Match("CH101", 1));
    }

    @Test
    @DisplayName("SKU_N is the Nth gallery image; the SKU itself may contain a hyphen")
    void extraImages() {
        assertThat(match("CH101_2.jpg")).contains(new Match("CH101", 2));
        assertThat(match("CH101_12.png")).contains(new Match("CH101", 12));
        assertThat(match("CH101_20.png")).contains(new Match("CH101", 20));
        assertThat(match("CH-7.jpg")).contains(new Match("CH-7", 1));
        assertThat(match("CH-7_3.jpg")).contains(new Match("CH-7", 3));
    }

    @Test
    @DisplayName("A suffix outside 1-20, a hyphen suffix or an unknown SKU matches nothing")
    void notAMatch() {
        assertThat(match("CH101_21.jpg")).isEmpty();
        assertThat(match("CH101_0.jpg")).isEmpty();
        assertThat(match("CH101-2.jpg")).isEmpty();
        assertThat(match("CH999.jpg")).isEmpty();
        assertThat(match("CH999_2.jpg")).isEmpty();
        assertThat(match("")).isEmpty();
        assertThat(match(null)).isEmpty();
    }

    @Test
    @DisplayName("A name that reads as two SKUs is refused, not guessed")
    void ambiguous() {
        // SKU "X" with image 2, or the SKU "X_2" itself.
        assertThatThrownBy(() -> match("X_2.jpg")).isInstanceOf(ImageNameMatcher.Ambiguous.class);
        // A SKU that merely contains an underscore is fine when no shorter SKU competes.
        assertThat(match("A_B.jpg")).contains(new Match("A_B", 1));
    }

    @Test
    @DisplayName("Candidates cover the whole stem and the stem without its _N suffix")
    void candidates() {
        assertThat(ImageNameMatcher.candidates(ImageNameMatcher.parse("ch101_3.jpg"))).containsExactly("CH101_3", "CH101");
        assertThat(ImageNameMatcher.candidates(ImageNameMatcher.parse("ch101.jpg"))).containsExactly("CH101");
    }

    @Test
    @DisplayName("What Drive does to a name is suggested, never attached: copies, doubled extension, stray spaces")
    void suggestions() {
        assertThat(suggest("CH101 (1).jpg")).contains(new Match("CH101", 1));
        assertThat(suggest("CH101 - Copy.jpg")).contains(new Match("CH101", 1));
        assertThat(suggest("CH101 copy (2).jpg")).contains(new Match("CH101", 1));
        assertThat(suggest("CH101.jpg.jpg")).contains(new Match("CH101", 1));
        assertThat(suggest("CH 101.jpg")).contains(new Match("CH101", 1));
        assertThat(suggest("CH101_2 (1).jpg")).contains(new Match("CH101", 2));
        assertThat(suggest("something else.jpg")).isEmpty();
        // The plain name is not a «suggestion»: it is a match, found by resolve.
        assertThat(match("CH101 (1).jpg")).isEmpty();
    }

    private static Optional<Match> suggest(String fileName) {
        return ImageNameMatcher.suggest(ImageNameMatcher.parse(fileName), KNOWN);
    }

    @Test
    @DisplayName("Phone formats are reported as unsupported, non-pictures as not images, the rest is tried")
    void kinds() {
        assertThat(kind("CH101.HEIC")).isEqualTo(Kind.UNSUPPORTED_FORMAT);
        assertThat(kind("CH101.heif")).isEqualTo(Kind.UNSUPPORTED_FORMAT);
        assertThat(kind("CH101.tiff")).isEqualTo(Kind.UNSUPPORTED_FORMAT);
        assertThat(kind("Thumbs.db")).isEqualTo(Kind.NOT_AN_IMAGE);
        assertThat(kind("desktop.ini")).isEqualTo(Kind.NOT_AN_IMAGE);
        assertThat(kind(".DS_Store")).isEqualTo(Kind.NOT_AN_IMAGE);
        assertThat(kind("~$sheet.xlsx")).isEqualTo(Kind.NOT_AN_IMAGE);
        assertThat(kind("CH101.jpg")).isEqualTo(Kind.IMAGE);
        assertThat(kind("CH101.gif")).isEqualTo(Kind.IMAGE);
        assertThat(kind("CH101")).isEqualTo(Kind.IMAGE);
        assertThat(kind("CH101.whatever")).isEqualTo(Kind.IMAGE);
    }

    private static Kind kind(String fileName) {
        ParsedName parsed = ImageNameMatcher.parse(fileName);
        return ImageNameMatcher.kind(parsed);
    }
}
