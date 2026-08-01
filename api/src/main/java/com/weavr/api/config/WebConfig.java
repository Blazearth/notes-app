package com.weavr.api.config;

import java.util.List;

import com.weavr.api.auth.CurrentUserArgumentResolver;
import com.weavr.api.common.DbEnumConverterFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    WebConfig(CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /**
     * So {@code ?lifecycle=planned} binds. Without it Spring falls back to
     * {@code Enum.valueOf}, which wants {@code PLANNED} — see
     * {@code DbEnumConverterFactory}.
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new DbEnumConverterFactory());
    }
}
