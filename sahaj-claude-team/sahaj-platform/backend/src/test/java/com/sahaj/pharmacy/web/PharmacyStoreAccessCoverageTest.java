package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.application.StoreAccessGuard;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
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
 * ADR-028 D4, convention test: no store handler can skip the store check.
 *
 * <ol>
 *   <li>Every handler under {@code /stores/{storeId}/**}, and every handler that acts on a store in any other way (the
 *       working store, create store), declares exactly one {@link StoreAccess} kind, MASTER or OPERATION.</li>
 *   <li>The kinds agree with {@code x-store-access} in {@code docs/api/pharmacy.yaml}, and every contract operation
 *       that has the marker has a handler with it (work packages A and B; the later packages carry no marker).</li>
 *   <li>The service behind an OPERATION handler holds the {@link StoreAccessGuard}.</li>
 * </ol>
 */
class PharmacyStoreAccessCoverageTest {

    private static final String BASE = "/api/v1/pharmacy";

    /**
     * The SAH-79 sub-stories whose handlers are built. Each SAH-79 work package adds its sub-stories here when it
     * lands (for example "SAH-79-1"); until then its operations in the contract carry {@code x-store-access} but have no
     * handler, and are not required. The operations of SAH-78 name no SAH-79 sub-story and are always required.
     */
    private static final Set<String> BUILT_SUB_STORIES = Set.of("SAH-79-1", "SAH-79-2", "SAH-79-3", "SAH-79-4", "SAH-79-5", "SAH-79-6", "SAH-79-7", "SAH-79-8",
            "SAH-79-9", "SAH-79-10", "SAH-79-11", "SAH-79-12", "SAH-79-13", "SAH-79-14", "SAH-79-15", "SAH-79-16", "SAH-79-17", "SAH-79-18", "SAH-79-19",
            "SAH-80-1", "SAH-80-2", "SAH-80-3", "SAH-80-4", "SAH-80-5", "SAH-80-6", "SAH-80-7", "SAH-80-8", "SAH-80-9",
            "SAH-80-10", "SAH-80-11", "SAH-80-12", "SAH-80-13", "SAH-80-14", "SAH-80-15", "SAH-80-16", "SAH-80-17", "SAH-80-18", "SAH-80-19", "SAH-80-20", "SAH-80-21");

    /**
     * Contract operations of a built sub-story that are not built yet (method and normalised path). SAH-80-2 names the link of a
     * bill to its patient, which needs the patient port of a later run; it is listed here until it has a handler.
     */
    private static final Set<String> UNBUILT_OPERATIONS = Set.of();

    private static final Pattern SUB_STORY_ID = Pattern.compile("SAH-\\d+(?:-\\d+)?");
    private static final Pattern SAH_79 = Pattern.compile("SAH-79(?:-\\d+)?");
    private static final Pattern SAH_80 = Pattern.compile("SAH-80(?:-\\d+)?");

    /** One discovered handler. */
    private record Handler(String method, String path, Method java, StoreAccess access) {

        String key() {
            return method + " " + path;
        }

        @Override
        public String toString() {
            return key() + " (" + java.getDeclaringClass().getSimpleName() + "." + java.getName() + ")";
        }
    }

    @Test
    void everyStoreHandlerDeclaresMasterOrOperation() {
        List<String> problems = new ArrayList<>();
        for (Handler h : handlers()) {
            boolean storeHandler = h.path().startsWith("/stores/{}") || h.path().equals("/context/working-store");
            if (storeHandler && h.access() == null) {
                problems.add("a store handler with no @StoreAccess: " + h);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theHandlersThatDeclareAKindAreTheContractsStoreOperationsWithTheSameKind() throws IOException {
        Map<String, String> contract = contractKinds();
        Map<String, String> code = new TreeMap<>();
        for (Handler h : handlers()) {
            if (h.access() != null) {
                code.put(h.key(), h.access().value().name());
            }
        }
        assertThat(code).as("handlers with @StoreAccess").isNotEmpty();
        code.forEach((key, kind) -> assertThat(contract).as("contract x-store-access of " + key).containsEntry(key, kind));

        // every built contract operation that carries the marker (SAH-78, plus the SAH-79 sub-stories listed in
        // BUILT_SUB_STORIES) has a handler with the same kind
        Map<String, String> built = builtContractKinds();
        assertThat(built).as("built operations with x-store-access").isNotEmpty();
        Set<String> expected = new TreeSet<>(built.keySet());
        expected.removeAll(UNBUILT_OPERATIONS);
        assertThat(code.keySet()).as("built contract operations with x-store-access").containsAll(expected);
    }

    @Test
    void theUnbuiltListNamesOnlyBuiltSubStoryOperationsThatHaveNoHandlerYet() throws IOException {
        Map<String, String> built = builtContractKinds();
        Set<String> withHandler = new TreeSet<>();
        for (Handler h : handlers()) {
            withHandler.add(h.key());
        }
        for (String key : UNBUILT_OPERATIONS) {
            assertThat(built).as("the contract names " + key + " in a built sub-story").containsKey(key);
            assertThat(withHandler).as(key + " has a handler, so it leaves the unbuilt list").doesNotContain(key);
        }
    }

    @Test
    void operationsOfSah79AreLeftOutUntilTheirSubStoryIsListedAsBuilt() {
        assertThat(isBuilt(null)).isTrue();
        assertThat(isBuilt("SAH-78-3 (touch-up)")).isTrue();
        assertThat(isBuilt("SAH-79-6")).isTrue();
        assertThat(isBuilt("SAH-79-7")).isTrue();
        assertThat(isBuilt("SAH-79-8 (and SAH-79-7 payable)")).isTrue();
        assertThat(isBuilt("SAH-79-9")).isTrue();
        assertThat(isBuilt("SAH-79-9 (RAISED); SAH-79-11 (TO_DISPATCH)")).isTrue();
        assertThat(isBuilt("SAH-79-11")).isTrue();
        assertThat(isBuilt("SAH-79-12 (indents); SAH-79-15 (transfers)")).isTrue();
        assertThat(isBuilt("SAH-79-13, SAH-79-14")).isTrue();
        assertThat(isBuilt("SAH-79-13, SAH-79-15")).isTrue();
        assertThat(isBuilt("SAH-79 common (C-5; OQ-79-15)")).isFalse();
        assertThat(isBuilt("SAH-79-16 (SAH-78-3 touch-up, requirement request 5)")).isTrue();
        assertThat(isBuilt("SAH-79-17")).isTrue();
        assertThat(isBuilt("SAH-79-18")).isTrue();
        assertThat(isBuilt("SAH-79-2 (engine); SAH-79-10 indentLines; used by 79-5")).isTrue();
        assertThat(isBuilt("SAH-79-2 (engine); SAH-79-12")).isTrue();
        assertThat(isBuilt("SAH-79-2 (engine); SAH-79-14")).isTrue();
        assertThat(isBuilt("SAH-79-2 (engine); SAH-79-15")).isTrue();
        assertThat(isBuilt("SAH-79-16")).isTrue();
    }

    @Test
    void everyPharmacyOperationWithAStoreInItsPathIsKnownToTheContract() throws IOException {
        Set<String> contractKeys = contractOperations();
        List<String> unknown = new ArrayList<>();
        for (Handler h : handlers()) {
            if (!contractKeys.contains(h.key())) {
                unknown.add(h.toString());
            }
        }
        assertThat(unknown).as("handlers with no operation in docs/api/pharmacy.yaml").isEmpty();
    }

    @Test
    void theServiceBehindAnOperationHandlerHoldsTheStoreGuard() {
        List<String> problems = new ArrayList<>();
        for (Handler h : handlers()) {
            if (h.access() == null || h.access().value() != StoreAccess.Kind.OPERATION) {
                continue;
            }
            boolean guarded = Arrays.stream(h.java().getDeclaringClass().getDeclaredConstructors())
                    .flatMap(c -> Arrays.stream(c.getParameterTypes()))
                    .anyMatch(service -> Arrays.stream(service.getDeclaredFields())
                            .anyMatch(field -> field.getType() == StoreAccessGuard.class));
            if (!guarded) {
                problems.add("OPERATION handler whose service has no StoreAccessGuard: " + h);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theWorkingStoreAndTheOpeningStockOperationsAreTheOperationKinds() throws IOException {
        Map<String, String> kinds = new TreeMap<>();
        for (Handler h : handlers()) {
            if (h.access() != null) {
                kinds.put(h.key(), h.access().value().name());
            }
        }
        assertThat(kinds).containsEntry("PUT /context/working-store", "OPERATION")
                .containsEntry("GET /stores/{}", "MASTER").containsEntry("PUT /stores/{}", "MASTER")
                .containsEntry("POST /stores", "MASTER").containsEntry("POST /stores/{}/switch-off", "MASTER")
                .containsEntry("GET /stores/{}/staff", "MASTER").containsEntry("POST /stores/{}/staff", "MASTER")
                .containsEntry("PUT /stores/{}/staff/{}", "MASTER")
                .containsEntry("POST /stores/{}/staff/{}/remove", "MASTER");
        String imports = "/stores/{}/opening-stock-imports";
        assertThat(kinds).containsEntry("GET " + imports, "OPERATION").containsEntry("POST " + imports, "OPERATION")
                .containsEntry("GET " + imports + "/{}", "OPERATION")
                .containsEntry("GET " + imports + "/{}/lines", "OPERATION")
                .containsEntry("POST " + imports + "/{}/lines", "OPERATION")
                .containsEntry("POST " + imports + "/{}/submit", "OPERATION")
                .containsEntry("POST " + imports + "/{}/approve", "OPERATION")
                .containsEntry("POST " + imports + "/{}/reject", "OPERATION");
        // the working store and the eight opening-stock operations; every other store handler is a MASTER read or write
        Set<String> sah78 = sah78StoreOperationKeys();
        assertThat(kinds.entrySet().stream().filter(e -> sah78.contains(e.getKey()) && "OPERATION".equals(e.getValue())))
                .hasSize(9);
    }

    // ---- discovery ----

    private static List<Handler> handlers() {
        List<Handler> found = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.sahaj.pharmacy.web")) {
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
                for (String[] mapped : mappings(m)) {
                    found.add(new Handler(mapped[0], normalise(prefix + mapped[1]), m, m.getAnnotation(StoreAccess.class)));
                }
            }
        }
        assertThat(found).as("handlers found under " + BASE).hasSizeGreaterThan(10);
        return found;
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

    /** {@code /api/v1/pharmacy/stores/{storeId}} to {@code /stores/{}}. */
    private static String normalise(String fullPath) {
        String path = fullPath.startsWith(BASE) ? fullPath.substring(BASE.length()) : fullPath;
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() throws IOException {
        Map<String, Object> all = new LinkedHashMap<>();
        for (String name : new String[] {"pharmacy.yaml", "pharmacy-sales.yaml"}) {
            Path file = Path.of("..", "docs", "api", name);
            if (!Files.exists(file)) {
                file = Path.of("docs", "api", name);
            }
            assertThat(file).as("docs/api/" + name).exists();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, Object> root = new Yaml().load(reader);
                all.putAll((Map<String, Object>) root.get("paths"));
            }
        }
        return all;
    }

    /** Every contract operation (method and normalised path). */
    @SuppressWarnings("unchecked")
    private static Set<String> contractOperations() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, Object> path : paths().entrySet()) {
            Map<String, Object> item = (Map<String, Object>) path.getValue();
            for (String method : new String[] {"get", "post", "put", "delete"}) {
                if (item.get(method) instanceof Map) {
                    keys.add(method.toUpperCase(Locale.ROOT) + " " + normalise(BASE + path.getKey()));
                }
            }
        }
        return keys;
    }

    /** Every operation that carries {@code x-store-access}, with the kind: its first word (MASTER, OPERATION, SCOPED). */
    private static Map<String, String> contractKinds() throws IOException {
        return contractKinds(false);
    }

    /** The operations that carry x-store-access and name no sub-story: those of SAH-78. */
    @SuppressWarnings("unchecked")
    private static Set<String> sah78StoreOperationKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, Object> path : paths().entrySet()) {
            Map<String, Object> item = (Map<String, Object>) path.getValue();
            for (String method : new String[] {"get", "post", "put", "delete"}) {
                if (item.get(method) instanceof Map) {
                    Map<String, Object> operation = (Map<String, Object>) item.get(method);
                    if (operation.get("x-store-access") != null && operation.get("x-sub-story") == null) {
                        keys.add(method.toUpperCase(Locale.ROOT) + " " + normalise(BASE + path.getKey()));
                    }
                }
            }
        }
        return keys;
    }

    /** Only the operations of the built work packages (see {@link #BUILT_SUB_STORIES}). */
    private static Map<String, String> builtContractKinds() throws IOException {
        return contractKinds(true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> contractKinds(boolean builtOnly) throws IOException {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (Map.Entry<String, Object> path : paths().entrySet()) {
            Map<String, Object> item = (Map<String, Object>) path.getValue();
            for (String method : new String[] {"get", "post", "put", "delete"}) {
                if (item.get(method) instanceof Map) {
                    Map<String, Object> operation = (Map<String, Object>) item.get(method);
                    Object marker = operation.get("x-store-access");
                    if (marker != null && (!builtOnly || isBuilt(operation.get("x-sub-story")))) {
                        kinds.put(method.toUpperCase(Locale.ROOT) + " " + normalise(BASE + path.getKey()),
                                marker.toString().trim().split("[\\s(]")[0]);
                    }
                }
            }
        }
        assertThat(kinds).as("operations with x-store-access in the contract").isNotEmpty();
        return kinds;
    }

    /**
     * An operation is built when it names no SAH-79 sub-story, or when every SAH-79 sub-story it names is in
     * {@link #BUILT_SUB_STORIES}. The operations of SAH-78 carry no {@code x-sub-story}.
     */
    static boolean isBuilt(Object subStory) {
        if (subStory == null) {
            return true;
        }
        Matcher matcher = SUB_STORY_ID.matcher(subStory.toString());
        while (matcher.find()) {
            String id = matcher.group();
            if ((SAH_79.matcher(id).matches() || SAH_80.matcher(id).matches()) && !BUILT_SUB_STORIES.contains(id)) {
                return false;
            }
        }
        return true;
    }
}
