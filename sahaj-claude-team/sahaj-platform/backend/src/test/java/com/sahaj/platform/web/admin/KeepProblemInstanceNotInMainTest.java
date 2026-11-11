package com.sahaj.platform.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-018 (SAH-42) Revision 3 (g), rule for the marker: {@code com.sahaj.platform.web.KeepProblemInstance} may be
 * carried only by a dev-tools controller that sets a constant {@code instance} on every problem document. No class in
 * {@code src/main} (the source set of the normal jar) may carry it. Source-text scan, like
 * {@link DevToolsAbsentFromMainTest}: it needs no database and no class loading.
 */
class KeepProblemInstanceNotInMainTest {

    /** The annotation used on a type, simple or fully qualified. {@code @interface} and {@code {@link ...}} do not match. */
    private static final Pattern USE = Pattern.compile("@(?:com\\.sahaj\\.platform\\.web\\.)?KeepProblemInstance\\b");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\r\\n]*");

    private static Path javaRoot() {
        return Paths.get(System.getProperty("basedir", ".")).resolve("src").resolve("main").resolve("java");
    }

    private static String withoutComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    @Test
    void noMainClassCarriesTheKeepProblemInstanceMarker() throws IOException {
        Path root = javaRoot();
        assertThat(root).as("src/main/java found from " + Paths.get("").toAbsolutePath()).isDirectory();
        List<String> offenders = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources = files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        for (Path path : sources) {
            String code = withoutComments(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            if (USE.matcher(code).find()) {
                offenders.add(root.relativize(path).toString());
            }
        }
        assertThat(sources).as("scanned main sources").isNotEmpty();
        assertThat(offenders)
                .as("@KeepProblemInstance is allowed only on dev-tools controllers that set a constant instance"
                        + " (ADR-018 Revision 3 (g)); any use in src/main needs a revision of the ADR")
                .isEmpty();
    }

    @Test
    void theScanSeesAnAnnotatedTypeAndIgnoresCommentsAndTheDeclaration() {
        assertThat(USE.matcher(withoutComments("@KeepProblemInstance\nclass A {}")).find()).isTrue();
        assertThat(USE.matcher(withoutComments("@com.sahaj.platform.web.KeepProblemInstance class A {}")).find())
                .isTrue();
        assertThat(USE.matcher(withoutComments("/** @KeepProblemInstance */ class A {}")).find()).isFalse();
        assertThat(USE.matcher(withoutComments("// @KeepProblemInstance\nclass A {}")).find()).isFalse();
        assertThat(USE.matcher(withoutComments("public @interface KeepProblemInstance {}")).find()).isFalse();
        assertThat(USE.matcher(withoutComments("hasAnnotation(c, KeepProblemInstance.class)")).find()).isFalse();
    }
}
