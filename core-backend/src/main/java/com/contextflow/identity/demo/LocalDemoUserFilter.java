package com.contextflow.identity.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a fixed server-side USER principal when the local-demo profile is active. Client-supplied
 * identity headers or bearer tokens cannot override this principal.
 */
public final class LocalDemoUserFilter extends OncePerRequestFilter {
    public static final String MODE_HEADER = "X-ContextFlow-Mode";
    public static final String MODE_VALUE = "LOCAL_DEMO";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new LocalDemoIdentity.DemoPrincipal(LocalDemoIdentity.USER_ID),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        response.setHeader(MODE_HEADER, MODE_VALUE);
        chain.doFilter(request, response);
    }
}
