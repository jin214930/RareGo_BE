package com.bugzero.rarego.app;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.bugzero.rarego.domain.AccountDto;
import com.bugzero.rarego.domain.OAuth2AttributeMapper;
import com.bugzero.rarego.domain.TokenPairDto;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthOAuth2AccountService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
	private static final String ACCESS_TOKEN_ATTRIBUTE = "accessToken";
	private static final String REFRESH_TOKEN_ATTRIBUTE = "refreshToken";

	private final AuthFacade authFacade;

	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
		OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService = new DefaultOAuth2UserService();
		OAuth2User oauth2User = oAuth2UserService.loadUser(userRequest);

		ClientRegistration registration = userRequest.getClientRegistration();
		String registrationId = registration.getRegistrationId();
		String userNameAttributeName = registration.getProviderDetails()
			.getUserInfoEndpoint()
			.getUserNameAttributeName();

		AccountDto accountDto;
		TokenPairDto tokenPair;
		try {
			accountDto = OAuth2AttributeMapper.toAccountDto(
				registrationId, userNameAttributeName, oauth2User.getAttributes());
			tokenPair = authFacade.login(accountDto.providerId(), accountDto.email(), accountDto.provider());
		} catch (CustomException e) {
			throw toOAuth2AuthenticationException(e);
		}

		Map<String, Object> attributes = new HashMap<>(oauth2User.getAttributes());
		attributes.put(ACCESS_TOKEN_ATTRIBUTE, tokenPair.accessToken());
		attributes.put(REFRESH_TOKEN_ATTRIBUTE, tokenPair.refreshToken());

		return new DefaultOAuth2User(oauth2User.getAuthorities(), attributes, userNameAttributeName);
	}

	private OAuth2AuthenticationException toOAuth2AuthenticationException(CustomException e) {
		ErrorType errorType = e.getErrorType();
		String message = e.getMessage() == null || e.getMessage().isBlank()
			? errorType.getMessage()
			: e.getMessage();
		OAuth2Error oauth2Error = new OAuth2Error(String.valueOf(errorType.getCode()), message, null);
		return new OAuth2AuthenticationException(oauth2Error, message, e);
	}
}
