package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-78 (ADR-028 D4, DEC-061 Answer 2): the Store staff team role is in no existing role list, so every existing role
 * gate of every module answers 403 for it. This sweeps the real {@code @PreAuthorize} expressions of every existing
 * controller, evaluates each as a signed-in Store staff member, and fails if any handler other than the shell's own
 * {@code GET /me} and {@code PATCH /me/preferences} lets it through. A new endpoint with no role gate, or a gate that
 * names the role by mistake, shows up here.
 *
 * <p>Left out on purpose: the pharmacy controllers (they check access in the service, tested there), the Sahaj admin realm
 * (a separate security chain and token), the public, sign-in and error endpoints that need no clinic role, the test-only
 * probe controllers of {@code com.sahaj.platform.support}, and {@code EmrReadOnlyPathsController}, whose one handler
 * throws {@code AccessDeniedException} (403) for every caller.
 */
class StoreStaffRoleGatesTest {

    private static final Set<String> NO_CLINIC_ROLE_NEEDED = Set.of("/api/v1/platform/public", "/api/v1/platform/auth",
            "/api/v1/platform/health", "/error");

    private record Handler(String key, String path, String expression, Method method) {
    }

    @Test
    void everyExistingRoleGateRefusesStoreStaffExceptTheShellsOwnMe() {
        Authentication storeStaff = new TestingAuthenticationToken("test-store-staff", "n/a", "ROLE_STORE_STAFF");
        List<Handler> handlers = handlers();
        assertThat(handlers).as("existing handlers found").hasSizeGreaterThan(200);

        Set<String> reachable = new TreeSet<>();
        for (Handler h : handlers) {
            if (NO_CLINIC_ROLE_NEEDED.stream().anyMatch(h.path()::startsWith)) {
                continue;
            }
            if (h.expression() == null || allows(h.expression(), storeStaff)) {
                reachable.add(h.key());
            }
        }

        assertThat(reachable).as("existing endpoints a Store staff session can reach")
                .containsExactlyInAnyOrder("GET /api/v1/platform/me", "PATCH /api/v1/platform/me/preferences");
    }

    @Test
    void theSweepSeesTheRealGatesSoItWouldCatchOneThatNamedTheRole() {
        Authentication nurse = new TestingAuthenticationToken("test-nurse", "n/a", "ROLE_NURSE");
        Authentication storeStaff = new TestingAuthenticationToken("test-store-staff", "n/a", "ROLE_STORE_STAFF");
        assertThat(allows("hasAnyRole('OWNER','NURSE')", nurse)).isTrue();
        assertThat(allows("hasAnyRole('OWNER','NURSE')", storeStaff)).isFalse();
        assertThat(allows("hasAnyRole('OWNER','NURSE','STORE_STAFF')", storeStaff)).isTrue();
        assertThat(allows("hasRole('OWNER')", storeStaff)).isFalse();

        // the expressions of the application are role lists: none of them names STORE_STAFF
        long named = handlers().stream().filter(h -> h.expression() != null && h.expression().contains("STORE_STAFF")).count();
        assertThat(named).isZero();
        assertThat(handlers().stream().filter(h -> h.expression() != null && h.expression().contains("hasAnyRole")).count())
                .isGreaterThan(100);
    }

    private static boolean allows(String expression, Authentication authentication) {
        SecurityExpressionRoot root = new SecurityExpressionRoot(authentication) {
        };
        StandardEvaluationContext context = new StandardEvaluationContext(root);
        Boolean result = new SpelExpressionParser().parseExpression(expression).getValue(context, Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    private static List<Handler> handlers() {
        List<Handler> found = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.sahaj")) {
            String className = definition.getBeanClassName();
            if (className.startsWith("com.sahaj.pharmacy.") || className.startsWith("com.sahaj.platform.web.admin.")
                    || className.startsWith("com.sahaj.platform.support.") || className.contains(".devtools.")
                    || className.endsWith(".EmrReadOnlyPathsController")) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String prefix = classMapping == null || classMapping.value().length == 0 ? "" : classMapping.value()[0];
            PreAuthorize classGate = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                PreAuthorize gate = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                String expression = gate != null ? gate.value() : classGate != null ? classGate.value() : null;
                String path = prefix + (mapping.value().length == 0 ? "" : mapping.value()[0]);
                String methods = Arrays.stream(mapping.method()).map(Enum::name).reduce((a, b) -> a + "," + b).orElse("ANY");
                found.add(new Handler(methods + " " + path, path, expression, m));
            }
        }
        return found;
    }
}
