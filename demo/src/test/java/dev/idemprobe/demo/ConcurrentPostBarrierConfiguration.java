package dev.idemprobe.demo;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

@TestConfiguration(proxyBeanMethods = false)
class ConcurrentPostBarrierConfiguration {

    @Bean
    ConcurrentPostBarrier concurrentPostBarrier() {
        return new ConcurrentPostBarrier();
    }

    @Bean
    OncePerRequestFilter concurrentPostBarrierFilter(ConcurrentPostBarrier barrier) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                if ("POST".equals(request.getMethod())
                        && "/reservations".equals(request.getRequestURI())) {
                    try {
                        if (!barrier.enterAndAwaitRelease(10, SECONDS)) {
                            throw new ServletException("server barrier timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ServletException("server barrier interrupted", interrupted);
                    }
                }
                filterChain.doFilter(request, response);
            }
        };
    }
}
