package com.sahaj.platform.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-003 D8 convention: every handler under {@code /api/v1/admin} carries
 * {@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")}, except the four sign-in endpoints that carry
 * {@code @PublicEndpoint}. Fails the build when a new admin handler forgets its permission check, and
 * when an admin route is declared outside the admin web package.
 */
class AdminEndpointConventionTest {

    private static final Set<String> PUBLIC = Set.of("login", "enrol", "verify", "refresh");

    private static List<Class<?>> restControllers(String basePackage) throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        // The admin classes are conditional on the feature switch; switch it on so the scan sees them.
        scanner.setEnvironment(new MockEnvironment()
                .withProperty("sahaj.admin.enabled", "true")
                .withProperty("sahaj.admin.jwt-secret", "x")
                .withProperty("sahaj.admin.totp-key", "y"));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            classes.add(Class.forName(definition.getBeanClassName()));
        }
        return classes;
    }

    private static boolean isAdminController(Class<?> type) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        if (mapping == null) {
            return false;
        }
        for (String path : mapping.path().length > 0 ? mapping.path() : mapping.value()) {
            if (path.startsWith("/api/v1/admin")) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> handlers(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                methods.add(method);
            }
        }
        return methods;
    }

    @Test
    void everyAdminHandlerHasTheRoleCheckOrIsOneOfTheFourPublicSignInEndpoints() throws Exception {
        List<Class<?>> controllers = restControllers("com.sahaj.platform.web.admin");
        assertThat(controllers).as("admin controllers found").isNotEmpty();
        List<String> publicHandlers = new ArrayList<>();
        for (Class<?> controller : controllers) {
            assertThat(isAdminController(controller)).as(controller.getSimpleName() + " maps under /api/v1/admin").isTrue();
            for (Method method : handlers(controller)) {
                PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                boolean isPublic = method.isAnnotationPresent(PublicEndpoint.class);
                String name = controller.getSimpleName() + "." + method.getName();
                if (isPublic) {
                    assertThat(preAuthorize).as(name + " is public and must not also carry @PreAuthorize").isNull();
                    publicHandlers.add(method.getName());
                } else {
                    assertThat(preAuthorize).as(name + " needs @PreAuthorize(\"hasRole('PLATFORM_ADMIN')\")")
                            .isNotNull();
                    assertThat(preAuthorize.value()).as(name).isEqualTo("hasRole('PLATFORM_ADMIN')");
                }
            }
        }
        assertThat(publicHandlers).containsExactlyInAnyOrderElementsOf(PUBLIC);
    }

    @Test
    void noAdminRouteIsDeclaredOutsideTheAdminWebPackage() throws Exception {
        for (Class<?> controller : restControllers("com.sahaj")) {
            if (isAdminController(controller)) {
                assertThat(controller.getPackageName()).as(controller.getName()).isEqualTo("com.sahaj.platform.web.admin");
            }
        }
    }

    @Test
    void noClinicControllerMapsUnderTheAdminPrefix() throws Exception {
        for (Class<?> controller : restControllers("com.sahaj")) {
            if (!controller.getPackageName().equals("com.sahaj.platform.web.admin")) {
                assertThat(isAdminController(controller)).as(controller.getName()).isFalse();
            }
        }
    }
}
