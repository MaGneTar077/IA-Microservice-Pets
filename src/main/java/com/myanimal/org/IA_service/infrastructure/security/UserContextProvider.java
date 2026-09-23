package com.myanimal.org.IA_service.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.model.UserContext;

@Component
public class UserContextProvider {

    public UserContext current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserContext userContext)) {
            throw new IllegalStateException("No hay un UserContext autenticado en el contexto de seguridad.");
        }
        return userContext;
    }
}
