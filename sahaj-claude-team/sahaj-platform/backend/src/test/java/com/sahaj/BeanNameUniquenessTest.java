package com.sahaj;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/** Two stereotype classes in different modules must never share a default bean name (the app would not start). */
class BeanNameUniquenessTest {

    @Test
    void noTwoComponentsShareABeanName() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true);
        AnnotationBeanNameGenerator generator = AnnotationBeanNameGenerator.INSTANCE;
        BeanDefinitionRegistry registry = new DefaultListableBeanFactory();
        Map<String, List<String>> byName = new HashMap<>();
        scanner.findCandidateComponents("com.sahaj").forEach(definition -> {
            if (definition instanceof AnnotatedBeanDefinition) {
                String name = generator.generateBeanName(definition, registry);
                byName.computeIfAbsent(name, key -> new ArrayList<>()).add(definition.getBeanClassName());
            }
        });
        Map<String, List<String>> clashes = new HashMap<>();
        byName.forEach((name, classes) -> {
            if (classes.size() > 1) {
                clashes.put(name, classes);
            }
        });
        assertThat(clashes).as("bean names used by more than one class").isEmpty();
    }
}
