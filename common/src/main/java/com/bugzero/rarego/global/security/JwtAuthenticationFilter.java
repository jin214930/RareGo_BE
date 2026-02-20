package com.bugzero.rarego.global.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bugzero.rarego.global.response.ErrorType;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
	private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
	private final JwtParser jwtParser;
	private final RedisAccessTokenBlacklistChecker accessTokenBlacklistChecker;
	private final AuthenticationEntryPoint authenticationEntryPoint;

	public JwtAuthenticationFilter(JwtParser jwtParser, RedisAccessTokenBlacklistChecker accessTokenBlacklistChecker,
		AuthenticationEntryPoint authenticationEntryPoint) {
		this.jwtParser = jwtParser;
		this.accessTokenBlacklistChecker = accessTokenBlacklistChecker;
		this.authenticationEntryPoint = authenticationEntryPoint;
	}

	private static String
	resolveToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith("Bearer "))
			return null;
		String token = header.substring(7);
		return token.isBlank() ? null : token;
	}

	private static List<GrantedAuthority> toAuthorities(String role) {
		if (role == null || role.isBlank())
			return Collections.emptyList();
		return List.of(new SimpleGrantedAuthority("ROLE_" + role));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		String token = resolveToken(request);
		boolean isPublic = isPublicRequest(request);
		if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				// 토큰이 유효한지 검사, 유효하지 않으면 예외
				MemberPrincipal principal = jwtParser.parsePrincipalOrThrow(token);
				if (accessTokenBlacklistChecker.isBlacklisted(token)) {
					if (!isPublic) {
						authenticationEntryPoint.commence(request, response,
							new JwtAuthenticationException(ErrorType.AUTH_ACCESS_TOKEN_BLACKLISTED));
						return;
					}
					filterChain.doFilter(request, response);
					return;
				}
				// Security에서 권한은 GrantedAuthority 리스트 형태
				List<GrantedAuthority> authorities = toAuthorities(principal.role());
				// “로그인 성공한 사용자”를 표현하는 Security 표준 객체 생성
				Authentication authentication = new UsernamePasswordAuthenticationToken(
					principal,
					null,
					authorities
				);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			} catch (AuthenticationException e) {
				if (!isPublic) {
					authenticationEntryPoint.commence(request, response, e);
					return;
				}
			}
		}
		filterChain.doFilter(request, response);
	}

	// public이라면 토큰이 유효하지 않아도 인증 객체 만들지 않고(비인증으로) 통과
	private boolean isPublicRequest(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (uri == null) {
			return false;
		}
		for (String pattern : SecurityPaths.PUBLIC) {
			if (PATH_MATCHER.match(pattern, uri)) {
				return true;
			}
		}
		if ("GET".equalsIgnoreCase(request.getMethod())) {
			for (String pattern : SecurityPaths.PUBLIC_GET) {
				if (PATH_MATCHER.match(pattern, uri)) {
					return true;
				}
			}
		}
		return false;
	}
}
