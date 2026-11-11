package com.sahaj.platform.web;

import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Spring fills the RFC 9457 {@code instance} of a problem with the request path just before the
 * body is written, and that path can hold a value the caller submitted (an id, a malformed token).
 * The contract in {@code docs/api/platform.yaml} has no {@code instance}, so it is cleared here, at
 * the last moment before serialisation, for every problem body, except those of a controller marked
 * {@link KeepProblemInstance}, which sets a fixed {@code instance} itself.
 */
@RestControllerAdvice
public class ProblemInstanceScrubber implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return !AnnotatedElementUtils.hasAnnotation(returnType.getContainingClass(), KeepProblemInstance.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem) {
            problem.setInstance(null);
        }
        return body;
    }
}
