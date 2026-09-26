package com.example.MigrosBackend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused architecture guard: a controller method whose name begins with a
 * state-changing verb must not be exposed only as an idempotent GET. A GET can
 * be triggered by a cross-site link, an image tag, or a browser prefetch, so
 * allowing such a mapping would silently bypass CSRF protection.
 */
class StateChangingHttpGetMappingTest {

    private static final List<String> STATE_CHANGING_PREFIXES = List.of(
            "add", "update", "delete", "remove", "cancel", "upload",
            "reset", "ban", "unban", "clear", "charge");

    /**
     * Explicit exceptions to the rule above. Each entry must be documented with
     * the reason the GET is safe. Empty today: every state-changing handler is
     * exposed through a non-GET mapping.
     */
    private static final Map<String, String> ALLOWED_GET_EXCEPTIONS = Map.of();

    @Test
    void stateChangingControllerMethodsAreNotMappedToGetOnly() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Class<?> controller : findRestControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!startsWithStateChangingPrefix(method.getName())) {
                    continue;
                }
                Set<RequestMethod> mappedMethods = mappedHttpMethods(method);
                if (mappedMethods.isEmpty()) {
                    continue;
                }
                if (mappedMethods.stream().anyMatch(verb -> verb != RequestMethod.GET)) {
                    continue;
                }

                String key = controller.getName() + "#" + method.getName();
                if (ALLOWED_GET_EXCEPTIONS.containsKey(key)) {
                    continue;
                }
                violations.add(key);
            }
        }

        assertTrue(violations.isEmpty(),
                "State-changing controller methods must not be mapped only to GET: " + violations);
    }

    private boolean startsWithStateChangingPrefix(String methodName) {
        String lowerCased = methodName.toLowerCase();
        return STATE_CHANGING_PREFIXES.stream().anyMatch(lowerCased::startsWith);
    }

    private Set<RequestMethod> mappedHttpMethods(Method method) {
        Set<RequestMethod> methods = EnumSet.noneOf(RequestMethod.class);

        if (method.getAnnotationsByType(GetMapping.class).length > 0) {
            methods.add(RequestMethod.GET);
        }
        if (method.getAnnotationsByType(PostMapping.class).length > 0) {
            methods.add(RequestMethod.POST);
        }
        if (method.getAnnotationsByType(PutMapping.class).length > 0) {
            methods.add(RequestMethod.PUT);
        }
        if (method.getAnnotationsByType(PatchMapping.class).length > 0) {
            methods.add(RequestMethod.PATCH);
        }
        if (method.getAnnotationsByType(DeleteMapping.class).length > 0) {
            methods.add(RequestMethod.DELETE);
        }

        RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
        if (requestMapping != null) {
            if (requestMapping.method().length == 0) {
                methods.addAll(EnumSet.allOf(RequestMethod.class));
            } else {
                methods.addAll(List.of(requestMapping.method()));
            }
        }

        return methods;
    }

    private List<Class<?>> findRestControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition candidate :
                scanner.findCandidateComponents("com.example.MigrosBackend.controller")) {
            controllers.add(Class.forName(candidate.getBeanClassName()));
        }
        return controllers;
    }
}
