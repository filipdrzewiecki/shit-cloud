package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The safety net for the one failure that authorization rules cannot catch themselves: a controller added
 * later with no rule at all. It would be open to every signed-in guest and look ordinary in review.
 *
 * <p>There is no list of exempt controllers. Public endpoints declare {@code permitAll()} explicitly, so
 * "open on purpose" is written in the source.
 */
class ControllerAuthorizationIT extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void everyController_declaresAnAuthorizationRule() {
        Map<String, Object> controllers = context.getBeansWithAnnotation(RestController.class);

        List<String> unguarded = controllers.values().stream()
                // The user class: method security wraps controllers in CGLIB proxies whose generated
                // methods carry none of the annotations looked for here.
                .map(ClassUtils::getUserClass)
                .filter(type -> !AnnotatedElementUtils.hasAnnotation(type, PreAuthorize.class))
                .filter(type -> !everyMappingGuarded(type))
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(unguarded)
                .as("every @RestController must declare @PreAuthorize on the class or on every request mapping")
                .isEmpty();
    }

    @Test
    void theSuiteSeesEveryController() {
        Map<String, Object> controllers = context.getBeansWithAnnotation(RestController.class);

        // Guards the test above from passing vacuously on an empty context.
        assertThat(controllers).hasSizeGreaterThanOrEqualTo(4);
    }

    private static boolean everyMappingGuarded(Class<?> type) {
        List<Method> mappings = Arrays.stream(type.getMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();
        return !mappings.isEmpty() && mappings.stream()
                .allMatch(method -> AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class));
    }
}
