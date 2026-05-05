package com.nexus.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC 配置
 * 配置静态资源访问路径
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${companion.assets.models-dir:../nexus-chat-frontend/public/models}")
    private String modelsDir;

    @Value("${companion.assets.motions-dir:../nexus-chat-frontend/public/motions}")
    private String motionsDir;

    /**
     * 配置静态资源处理器
     * 使 /uploads/** 路径可以访问 uploads/ 目录下的文件
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 同时注册当前目录和父目录的 uploads/，兼容不同启动方式的工作目录
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/", "file:../uploads/")
                .setCachePeriod(3600);

        registry.addResourceHandler("/apk/**")
                .addResourceLocations("file:apk/", "file:../apk/")
                .setCachePeriod(86400);

        registry.addResourceHandler("/models/**")
                .addResourceLocations(asFileLocation(modelsDir))
                .setCachePeriod(3600);

        registry.addResourceHandler("/motions/**")
                .addResourceLocations(asFileLocation(motionsDir))
                .setCachePeriod(3600);
    }

    private String asFileLocation(String path) {
        Path dir = Paths.get(path).toAbsolutePath().normalize();
        String normalized = dir.toString();
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return "file:" + normalized;
    }
}
