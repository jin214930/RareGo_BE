package com.bugzero.rarego.bounded_context.auth.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.bounded_context.auth.app.AuthOAuth2AccountService;
import com.bugzero.rarego.bounded_context.auth.security.CustomOAuth2SuccessHandler;
import com.bugzero.rarego.global.security.OAuth2SecurityConfigurer;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuthOAuth2SecurityConfigurer implements OAuth2SecurityConfigurer {
	private final AuthOAuth2AccountService authOAuth2AccountService;
	private final CustomOAuth2SuccessHandler customOAuth2SuccessHandler;

	@Override
	public void configure(HttpSecurity http) throws Exception {
		http.oauth2Login(oauth2 -> oauth2
			.userInfoEndpoint(userInfo -> userInfo.userService(authOAuth2AccountService))
			.successHandler(customOAuth2SuccessHandler)
		);
	}
}
