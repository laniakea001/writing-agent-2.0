package com.writingagent.v2.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** During D2 migration, old write adapters cannot bypass JSON/CAS/proposal contracts.
 * Existing application modules are retained until their AI adapters use the shared capability.
 */
@Component
public class LegacyWriteGuard extends OncePerRequestFilter {
    private static final Pattern LEGACY=Pattern.compile(
            "^/api/writing/v2/projects/[^/]+/(turns|plans/[^/]+/(confirm|edit)|articles/[^/]+/(complete|chapters/[^/]+/edit))/?$");

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                                               FilterChain chain) throws ServletException,IOException {
        if("POST".equals(request.getMethod())&&LEGACY.matcher(request.getRequestURI()).matches()) {
            response.setStatus(409);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"LEGACY_WRITE_ENDPOINT_DISABLED\",\"message\":\"旧写入口尚未迁移，请使用新文档保存和确认入口。\",\"retryable\":false}");
            return;
        }
        chain.doFilter(request,response);
    }
}
