package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * M-1 (review round 5, defect D-6): PostgreSQL refuses {@code SELECT ... FOR UPDATE} in a read-only transaction (25006), so a
 * read path must never take a row lock. Worked from the source, with no database:
 *
 * <ul>
 *   <li>no method named {@code summary} (the approval handlers' read, called from the inbox and the request read) may call a
 *       {@code ...ForUpdate...} repository method;</li>
 *   <li>no method annotated {@code @Transactional(readOnly = true)} may call one, directly or through the methods of the same
 *       class it calls by name (one class at a time; a call into another class is not followed, so the other class's own
 *       read-only methods are checked by this same rule).</li>
 * </ul>
 */
class ReadPathsTakeNoLockGuardTest {

    private static final Pattern LOCK_CALL = Pattern.compile("\\w*ForUpdate\\w*\\s*\\(|ensureAndLock\\s*\\(|lockUnit\\s*\\(");
    private static final Pattern METHOD_HEAD = Pattern.compile(
            "(?m)^    (?:(?:public|protected|private|static|final|synchronized)\\s+)*[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\([^;{]*\\)"
                    + "(?:\\s+throws [\\w., ]+)?\\s*\\{");

    @Test
    void noSummaryMethodAndNoReadOnlyMethodTakesARowLock() throws IOException {
        List<String> breaches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root())) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                TreeMap<String, List<String>> methods = methods(source);
                for (String name : methods.keySet()) {
                    boolean summary = name.equals("summary");
                    boolean readOnly = methods.get(name).stream().anyMatch(ReadPathsTakeNoLockGuardTest::isReadOnlyMethod);
                    if ((summary || readOnly) && reaches(name, methods, new HashSet<>())) {
                        breaches.add(file.getFileName() + "#" + name);
                    }
                }
            }
        }
        assertThat(breaches).as("read paths that reach a FOR UPDATE call").isEmpty();
    }

    @Test
    void theGuardSeesALockInsideASummaryAndInAReadOnlyMethodThroughAHelper() {
        String source = """
                class Sample {
                    public Summary summary(UUID t) {
                        return orders.findForUpdateById(t, id);
                    }

                    @Transactional(readOnly = true)
                    public View view(UUID t) {
                        return load(t);
                    }

                    private View load(UUID t) {
                        return orders.findForUpdate(t, s, id);
                    }

                    @Transactional
                    public void write(UUID t) {
                        orders.findForUpdate(t, s, id);
                    }
                }
                """;
        TreeMap<String, List<String>> methods = methods(source);
        assertThat(reaches("summary", methods, new HashSet<>())).isTrue();
        assertThat(reaches("view", methods, new HashSet<>())).isTrue();
        assertThat(methods.get("view").stream().anyMatch(ReadPathsTakeNoLockGuardTest::isReadOnlyMethod)).isTrue();
        assertThat(methods.get("write").stream().anyMatch(ReadPathsTakeNoLockGuardTest::isReadOnlyMethod)).isFalse();
    }

    private static boolean isReadOnlyMethod(String text) {
        return text.contains("@Transactional(readOnly = true)");
    }

    /** True when the method's body, or the body of a same-class method it calls by name, holds a lock call. */
    private static boolean reaches(String name, TreeMap<String, List<String>> methods, Set<String> seen) {
        if (!seen.add(name)) {
            return false;
        }
        for (String text : methods.getOrDefault(name, List.of())) {
            if (LOCK_CALL.matcher(text).find()) {
                return true;
            }
            for (String other : methods.keySet()) {
                if (!other.equals(name) && Pattern.compile("(?<![\\w.])" + other + "\\s*\\(").matcher(text).find()
                        && reaches(other, methods, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Method name to the text of each of its declarations (annotations before the head, then the braced body). */
    private static TreeMap<String, List<String>> methods(String source) {
        TreeMap<String, List<String>> out = new TreeMap<>();
        Matcher m = METHOD_HEAD.matcher(source);
        int from = 0;
        while (m.find(from)) {
            String name = m.group(1);
            int open = m.end() - 1;
            int depth = 0;
            int end = open;
            for (; end < source.length(); end++) {
                char c = source.charAt(end);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            int start = annotationsStart(source, m.start());
            if (!List.of("if", "for", "while", "switch", "catch", "return", "new", "else", "synchronized").contains(name)) {
                out.computeIfAbsent(name, k -> new ArrayList<>()).add(source.substring(start, Math.min(end + 1, source.length())));
            }
            from = Math.min(end + 1, source.length());
            if (from >= source.length()) {
                break;
            }
        }
        return out;
    }

    /** The first line of the annotations that sit directly above a method head. */
    private static int annotationsStart(String source, int head) {
        int start = head;
        while (start > 0) {
            int prevEnd = start - 1;
            int prevStart = source.lastIndexOf('\n', prevEnd - 1) + 1;
            String line = source.substring(prevStart, prevEnd).stripLeading();
            if (line.startsWith("@")) {
                start = prevStart;
            } else {
                break;
            }
        }
        return start;
    }

    private static Path root() {
        Path dir = Path.of("src", "main", "java", "com", "sahaj", "pharmacy");
        return Files.isDirectory(dir) ? dir : Path.of("backend").resolve(dir);
    }
}
