package pl.chrisitstyle.product;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ReleaseVersionHeaderFilter
        extends OncePerRequestFilter {

    private final String releaseVersion;

    public ReleaseVersionHeaderFilter(
            @Value("${app.release-version:stable}")
            String releaseVersion
    ) {
        this.releaseVersion = releaseVersion;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        response.setHeader(
                "X-Release-Version",
                releaseVersion
        );

        filterChain.doFilter(
                request,
                response
        );
    }
}