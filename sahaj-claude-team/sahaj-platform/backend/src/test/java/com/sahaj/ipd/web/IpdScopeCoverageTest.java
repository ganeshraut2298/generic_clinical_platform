package com.sahaj.ipd.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * SAH-34 (ADR-017 D3, AC 22): no IPD endpoint can skip the patient scope guard.
 *
 * <ol>
 *   <li>Marker scan: every handler under {@code /api/v1/ipd} has exactly one of {@link ScopeGuarded} and
 *       {@link ScopeExempt}.</li>
 *   <li>Contract cross-check: the operations of {@code docs/api/ipd.yaml} that carry {@code x-scope-guard} are exactly the
 *       {@link ScopeGuarded} handlers, and every contract operation has a handler (and the other way round), so the
 *       contract and the code cannot drift.</li>
 *   <li>Behaviour: every guarded handler that takes an admission, an order or an escalation id is named in the table
 *       below with the service test that proves it refuses an out-of-scope admission and writes nothing. The named method
 *       must exist, be a {@code @Test} or {@code @ParameterizedTest} and not be {@code @Disabled}. A new guarded
 *       endpoint with no named test fails here.</li>
 * </ol>
 */
class IpdScopeCoverageTest {

    private static final String BASE = "/api/v1/ipd";

    /** One discovered handler: HTTP method, full path (with {@code {}} for path variables) and its markers. */
    private record Handler(String method, String path, Method java, ScopeGuarded guarded, ScopeExempt exempt) {

        String key() {
            return method + " " + path;
        }

        @Override
        public String toString() {
            return key() + " (" + java.getDeclaringClass().getSimpleName() + "." + java.getName() + ")";
        }
    }

    /**
     * (HTTP method and path) of every guarded handler that takes an admission, order or escalation id, to the unit test that
     * proves a refused request writes nothing (class in {@code com.sahaj.ipd.application}, then the test method).
     */
    private static final Map<String, String[]> REFUSAL_TESTS = Map.ofEntries(
            Map.entry("GET /admissions/{}", new String[] {"AdmissionServiceTest", "detailOutsideScopeIsRefusedWithNoRecordAccess"}),
            Map.entry("POST /admissions/{}/deposits", new String[] {"AdmissionServiceTest", "depositOutsideScopeWritesNothing"}),
            Map.entry("POST /admissions/{}/charges", new String[] {"AdmissionServiceTest", "chargeOutsideScopeWritesNothing"}),
            Map.entry("POST /admissions/{}/transfer", new String[] {"BedTransferServiceTest", "outsideScopeMovesNothing"}),
            Map.entry("GET /admissions/{}/discharge", new String[] {"DischargeServiceTest", "sheetOutsideScopeIsRefused"}),
            Map.entry("PUT /admissions/{}/discharge/signoff", new String[] {"DischargeServiceTest", "signoffOutsideScopeWritesNothing"}),
            Map.entry("POST /admissions/{}/discharge", new String[] {"DischargeServiceTest", "dischargeOutsideScopeWritesNothing"}),
            Map.entry("GET /admissions/{}/nursing", new String[] {"VitalsServiceTest", "sheetOutsideScopeWritesNoRecordAccess"}),
            Map.entry("POST /admissions/{}/vitals", new String[] {"VitalsServiceTest", "recordOutsideScopeWritesNothing"}),
            Map.entry("GET /admissions/{}/emr", new String[] {"MedicineOrderServiceTest", "emrOutsideScopeWritesNothing"}),
            Map.entry("POST /admissions/{}/medicine-orders", new String[] {"MedicineOrderServiceTest", "orderOutsideScopeWritesNothing"}),
            Map.entry("POST /medicine-orders/{}/stop", new String[] {"MedicineOrderServiceTest", "stopOutsideScopeWritesNothing"}),
            Map.entry("GET /admissions/{}/mar", new String[] {"MarServiceTest", "marOutsideScopeWritesNoRecordAccess"}),
            Map.entry("POST /medicine-orders/{}/doses", new String[] {"DoseServiceTest", "doseOutsideScopeChecksNoPinAndWritesNothing"}),
            Map.entry("POST /escalations/{}/acknowledge", new String[] {"EscalationServiceTest", "acknowledgeOutsideScopeWritesNothing"}),
            Map.entry("POST /escalations/{}/respond", new String[] {"EscalationServiceTest", "respondOutsideScopeWritesNothing"}),
            Map.entry("POST /escalations/{}/call-senior", new String[] {"EscalationServiceTest", "callSeniorOutsideScopeWritesNothing"}));

    @Test
    void everyHandlerHasExactlyOneOfGuardedAndExempt() {
        List<String> problems = new ArrayList<>();
        for (Handler h : handlers()) {
            if (h.guarded() == null && h.exempt() == null) {
                problems.add("neither @ScopeGuarded nor @ScopeExempt: " + h);
            }
            if (h.guarded() != null && h.exempt() != null) {
                problems.add("both @ScopeGuarded and @ScopeExempt: " + h);
            }
            if (h.exempt() != null && h.exempt().value().isBlank()) {
                problems.add("@ScopeExempt without a reason: " + h);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theContractAndTheCodeListTheSameOperations() throws IOException {
        Map<String, Boolean> contract = contractOperations();
        Set<String> contractKeys = new TreeSet<>(contract.keySet());
        Set<String> codeKeys = new TreeSet<>();
        Set<String> guardedInCode = new TreeSet<>();
        for (Handler h : handlers()) {
            codeKeys.add(h.key());
            if (h.guarded() != null) {
                guardedInCode.add(h.key());
            }
        }
        Set<String> guardedInContract = new TreeSet<>();
        contract.forEach((key, guarded) -> {
            if (guarded) {
                guardedInContract.add(key);
            }
        });
        assertThat(codeKeys).as("handlers without a contract operation, and operations without a handler")
                .isEqualTo(contractKeys);
        assertThat(guardedInCode).as("@ScopeGuarded handlers against x-scope-guard operations")
                .isEqualTo(guardedInContract);
        assertThat(guardedInContract).isNotEmpty();
    }

    @Test
    void everyGuardedAdmissionOrderAndEscalationEndpointNamesItsRefusalTest() throws Exception {
        Set<String> needed = new TreeSet<>();
        for (Handler h : handlers()) {
            if (h.guarded() == null) {
                continue;
            }
            ScopeGuarded.Kind kind = h.guarded().value();
            if (kind == ScopeGuarded.Kind.ADMISSION || kind == ScopeGuarded.Kind.ORDER
                    || kind == ScopeGuarded.Kind.ESCALATION) {
                needed.add(h.key());
            }
        }
        assertThat(REFUSAL_TESTS.keySet()).as("a refusal test is named for each guarded by-id endpoint, and only those")
                .containsExactlyInAnyOrderElementsOf(needed);
        assertThat(refusalTestProblems(REFUSAL_TESTS, "com.sahaj.ipd.application."))
                .as("named refusal tests that do not exist, are not tests, or are disabled").isEmpty();
    }

    /**
     * ADR-017 D3: the named refusal test must be a real, running test, so a rename, a deleted annotation or a
     * {@code @Disabled} cannot leave an endpoint "covered" on paper. Returns one line per bad entry.
     */
    private static List<String> refusalTestProblems(Map<String, String[]> table, String packagePrefix) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String[]> e : table.entrySet()) {
            String type = packagePrefix + e.getValue()[0];
            String name = e.getValue()[1];
            String label = e.getKey() + " -> " + type + "#" + name;
            Class<?> test;
            try {
                test = Class.forName(type);
            } catch (ClassNotFoundException ex) {
                problems.add(label + " (class not found)");
                continue;
            }
            List<Method> named = Arrays.stream(test.getDeclaredMethods()).filter(m -> m.getName().equals(name)).toList();
            if (named.isEmpty()) {
                problems.add(label + " (method not found)");
                continue;
            }
            if (isDisabledClass(test)) {
                problems.add(label + " (class is @Disabled)");
                continue;
            }
            boolean runs = named.stream().anyMatch(m -> isTestMethod(m) && !AnnotatedElementUtils.isAnnotated(m, Disabled.class));
            if (!runs) {
                boolean anyTest = named.stream().anyMatch(IpdScopeCoverageTest::isTestMethod);
                problems.add(label + (anyTest ? " (method is @Disabled)" : " (method is not a @Test or @ParameterizedTest)"));
            }
        }
        return problems;
    }

    private static boolean isTestMethod(Method m) {
        int modifiers = m.getModifiers();
        return !java.lang.reflect.Modifier.isPrivate(modifiers) && !java.lang.reflect.Modifier.isStatic(modifiers)
                && (AnnotatedElementUtils.isAnnotated(m, Test.class)
                        || AnnotatedElementUtils.isAnnotated(m, ParameterizedTest.class));
    }

    private static boolean isDisabledClass(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (AnnotatedElementUtils.isAnnotated(c, Disabled.class)) {
                return true;
            }
        }
        return false;
    }

    /** Fixtures for the check's own test; static nested classes are not discovered as tests by JUnit. */
    static class RefusalFixture {
        @Test
        void runs() {
        }

        @ParameterizedTest
        @ValueSource(ints = 1)
        void runsParameterized(int value) {
        }

        void noAnnotation() {
        }

        @Test
        @Disabled("fixture")
        void disabledMethod() {
        }

        @Test
        private void privateMethod() {
        }
    }

    @Disabled("fixture")
    static class DisabledFixture {
        @Test
        void inADisabledClass() {
        }
    }

    @Test
    void theRefusalCheckAcceptsRealTestsAndRejectsEveryKindOfBadEntry() {
        String prefix = "com.sahaj.ipd.web.";
        Map<String, String[]> good = Map.of(
                "A", new String[] {"IpdScopeCoverageTest$RefusalFixture", "runs"},
                "B", new String[] {"IpdScopeCoverageTest$RefusalFixture", "runsParameterized"});
        assertThat(refusalTestProblems(good, prefix)).isEmpty();

        Map<String, String[]> bad = new java.util.LinkedHashMap<>();
        bad.put("noClass", new String[] {"NoSuchTest", "runs"});
        bad.put("noMethod", new String[] {"IpdScopeCoverageTest$RefusalFixture", "missing"});
        bad.put("noAnnotation", new String[] {"IpdScopeCoverageTest$RefusalFixture", "noAnnotation"});
        bad.put("disabledMethod", new String[] {"IpdScopeCoverageTest$RefusalFixture", "disabledMethod"});
        bad.put("privateMethod", new String[] {"IpdScopeCoverageTest$RefusalFixture", "privateMethod"});
        bad.put("disabledClass", new String[] {"IpdScopeCoverageTest$DisabledFixture", "inADisabledClass"});
        List<String> problems = refusalTestProblems(bad, prefix);
        assertThat(problems).hasSize(6);
        assertThat(problems).anyMatch(p -> p.startsWith("noClass") && p.contains("class not found"))
                .anyMatch(p -> p.startsWith("noMethod") && p.contains("method not found"))
                .anyMatch(p -> p.startsWith("noAnnotation") && p.contains("not a @Test"))
                .anyMatch(p -> p.startsWith("disabledMethod") && p.contains("method is @Disabled"))
                .anyMatch(p -> p.startsWith("privateMethod") && p.contains("not a @Test"))
                .anyMatch(p -> p.startsWith("disabledClass") && p.contains("class is @Disabled"));
    }

    @Test
    void listEndpointsAreTheFiveScopedReads() {
        Set<String> lists = new TreeSet<>();
        for (Handler h : handlers()) {
            if (h.guarded() != null && h.guarded().value() == ScopeGuarded.Kind.LIST) {
                lists.add(h.key());
            }
        }
        assertThat(lists).containsExactly("GET /admissions", "GET /board", "GET /clinical-events", "GET /safety",
                "GET /safety/summary");
    }

    // ---- discovery ----

    private static List<Handler> handlers() {
        List<Handler> found = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.sahaj.ipd.web")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            String prefix = classPath(type);
            if (!prefix.startsWith(BASE)) {
                continue;
            }
            for (Method m : type.getDeclaredMethods()) {
                collect(found, prefix, m);
            }
        }
        assertThat(found).as("handlers found under " + BASE).hasSizeGreaterThan(50);
        return found;
    }

    private static void collect(List<Handler> found, String prefix, Method m) {
        for (String[] mapped : mappings(m)) {
            found.add(new Handler(mapped[0], normalise(prefix + mapped[1]), m, m.getAnnotation(ScopeGuarded.class),
                    m.getAnnotation(ScopeExempt.class)));
        }
    }

    private static List<String[]> mappings(Method m) {
        List<String[]> list = new ArrayList<>();
        GetMapping get = m.getAnnotation(GetMapping.class);
        if (get != null) {
            list.add(new String[] {"GET", first(get.value())});
        }
        PostMapping post = m.getAnnotation(PostMapping.class);
        if (post != null) {
            list.add(new String[] {"POST", first(post.value())});
        }
        PutMapping put = m.getAnnotation(PutMapping.class);
        if (put != null) {
            list.add(new String[] {"PUT", first(put.value())});
        }
        DeleteMapping delete = m.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            list.add(new String[] {"DELETE", first(delete.value())});
        }
        return list;
    }

    private static String first(String[] values) {
        return values.length == 0 ? "" : values[0];
    }

    private static String classPath(Class<?> type) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        return mapping == null || mapping.value().length == 0 ? "" : mapping.value()[0];
    }

    /** {@code /api/v1/ipd/admissions/{id}} to {@code /admissions/{}}: the base removed, path variable names dropped. */
    private static String normalise(String fullPath) {
        String path = fullPath.startsWith(BASE) ? fullPath.substring(BASE.length()) : fullPath;
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    /** Every operation of the contract (method and normalised path) with whether it carries {@code x-scope-guard}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> contractOperations() throws IOException {
        Path file = Path.of("..", "docs", "api", "ipd.yaml");
        if (!Files.exists(file)) {
            file = Path.of("docs", "api", "ipd.yaml");
        }
        assertThat(file).as("docs/api/ipd.yaml").exists();
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        }
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        Map<String, Boolean> operations = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) path.getValue();
            for (String method : new String[] {"get", "post", "put", "delete"}) {
                Object operation = item.get(method);
                if (operation instanceof Map) {
                    boolean guarded = ((Map<String, Object>) operation).containsKey("x-scope-guard");
                    operations.put(method.toUpperCase(java.util.Locale.ROOT) + " " + normalise(BASE + path.getKey()),
                            guarded);
                }
            }
        }
        return operations;
    }
}
