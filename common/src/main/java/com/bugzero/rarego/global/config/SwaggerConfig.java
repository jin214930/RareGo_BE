package com.bugzero.rarego.global.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;

@Configuration
@OpenAPIDefinition(info = @Info(title = "RareGo API", version = "1.0.0", description = "경매 플랫폼 RareGo REST API"), servers = {
	@Server(url = "/", description = "현재 서버")
})
public class SwaggerConfig {
}
