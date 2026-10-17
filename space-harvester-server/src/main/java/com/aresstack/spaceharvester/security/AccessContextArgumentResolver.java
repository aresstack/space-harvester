package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessContext;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Stellt Controllern den {@link AccessContext} der geprüften Anmeldung bereit, ohne dass sie Spring Security kennen.
 * Ohne passende Anmeldung {@code null}; der {@code AccessGuard} verweigert dann alles.
 */
final class AccessContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AccessContext.class.equals(parameter.getParameterType());
    }

    @Override
    public AccessContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                         NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccessContext context) {
            return context;
        }
        return null;
    }
}
