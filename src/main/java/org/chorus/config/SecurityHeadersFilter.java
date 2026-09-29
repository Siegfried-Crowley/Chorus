package org.chorus.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为所有响应追加安全头,降低 XSS / MIME 嗅探 / 点击劫持风险:
 * <ul>
 *   <li>X-Content-Type-Options: nosniff — 禁止浏览器嗅探响应 MIME(上传附件攻击面)</li>
 *   <li>X-Frame-Options: DENY — 禁止页面被嵌入第三方 iframe</li>
 *   <li>Referrer-Policy: same-origin — 限制 Referer 泄露</li>
 * </ul>
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        chain.doFilter(request, response);
    }
}
