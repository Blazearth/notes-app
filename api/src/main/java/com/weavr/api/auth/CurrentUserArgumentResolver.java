package com.weavr.api.auth;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        if (!(webRequest.getUserPrincipal() instanceof JwtAuthenticationToken token)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Not authenticated");
        }

        Jwt jwt = token.getToken();
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Token has no subject");
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            // Supabase subs are UUIDs. Anything else is not a user token.
            throw new ResponseStatusException(UNAUTHORIZED, "Token subject is not a user id");
        }
    }
}
