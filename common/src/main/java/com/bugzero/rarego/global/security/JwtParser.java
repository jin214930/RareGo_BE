package com.bugzero.rarego.global.security;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.global.response.ErrorType;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JwtParser {
	private final SecretKey jwtSecretKey;

	// 인코딩 고정
	public JwtParser(@Value("${jwt.secret}") String jwtSecretKey) {
		this.jwtSecretKey = Keys.hmacShaKeyFor(jwtSecretKey.getBytes(StandardCharsets.UTF_8));
	}

	// 토큰 원문 노출 방지용 처음 10글자 + 마지막 6글자 마스킹
	private static String mask(String jwt) {
		if (jwt == null)
			return "null";
		int len = jwt.length();
		if (len <= 20)
			return "***";
		return jwt.substring(0, 10) + "..." + jwt.substring(len - 6);
	}

	private static String toRoleString(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String stringValue) {
			if (stringValue.startsWith("ROLE_"))
				return stringValue.substring("ROLE_".length());
			return stringValue;
		}
		return value.toString();
	}

	// MemberPrincipal 형태로 전환, 유효하지 않으면 null
	public MemberPrincipal parsePrincipal(String jwtStr) {
		Claims claims = claimsOrNull(jwtStr, "principal");
		if (claims == null)
			return null;

		String publicId = extractPublicId(claims);
		String role = toRoleString(claims.get("role"));
		if (publicId == null || role == null || role.isBlank())
			return null;

		return new MemberPrincipal(publicId, role);
	}

	// MemberPrincipal 형태로 전환 (예외 던짐)
	public MemberPrincipal parsePrincipalOrThrow(String jwtStr) {
		Claims claims = claimsOrThrow(jwtStr, "principal");
		String publicId = extractPublicId(claims);
		String role = toRoleString(claims.get("role"));
		if (publicId == null || role == null || role.isBlank()) {
			throw new JwtAuthenticationException(ErrorType.AUTH_ACCESS_TOKEN_INVALID);
		}
		return new MemberPrincipal(publicId, role);
	}

	// Refresh token에서 publicId만 파싱
	public String parseRefreshPublicId(String jwtStr) {
		Claims claims = claimsOrNull(jwtStr, "refresh-publicId");
		if (claims == null)
			return null;

		String type = claims.get("typ", String.class);
		if (type == null || !"REFRESH".equals(type))
			return null;

		return extractPublicId(claims);
	}

	// 만료시간 가져오기
	public LocalDateTime expiresAt(String jwtStr) {
		Claims claims = claimsOrNull(jwtStr, "expiresAt");
		if (claims == null)
			return null;

		Date exp = claims.getExpiration(); // 표준 exp
		if (exp == null)
			return null;

		return LocalDateTime.ofInstant(exp.toInstant(), ZoneId.systemDefault());
	}

	// 본문을 가져오거나 문제가 생기면 null로 반환
	private Claims claimsOrNull(String jwtStr, String purpose) {
		try {
			Object payload = Jwts.parser()
				.verifyWith(jwtSecretKey)
				.build()
				.parse(jwtStr)
				.getPayload();

			if (payload instanceof Claims claims)
				return claims;

			// Map으로 들어오는 경우도 고려
			if (payload instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> m = (Map<String, Object>)map;
				return Jwts.claims().add(m).build(); // Map -> Claims로 래핑
			}

			log.debug("JWT payload type is not supported. purpose={}, payloadType={}",
				purpose, payload == null ? "null" : payload.getClass().getName());
			return null;
		// 만료됨
		} catch (ExpiredJwtException e) {
			log.debug("JWT expired. purpose={}, token={}, msg={}", purpose, mask(jwtStr), e.getMessage());
			return null;
		// 서명 불일치
		} catch (SecurityException e) {
			log.debug("JWT signature/security error. purpose={}, token={}, msg={}", purpose, mask(jwtStr),
				e.getMessage());
			return null;
		// 형식 오류, claim 오류 등 JJWT 계열 전반
		} catch (JwtException e) {
			log.debug("JWT invalid. purpose={}, token={}, ex={}, msg={}",
				purpose, mask(jwtStr), e.getClass().getSimpleName(), e.getMessage());
			return null;
		// null, blank
		} catch (IllegalArgumentException e) {
			log.debug("JWT illegal argument. purpose={}, token={}, msg={}", purpose, mask(jwtStr), e.getMessage());
			return null;
		// 그 외 예외 오류
		} catch (Exception e) {
			log.warn("JWT unexpected error. purpose={}, token={}, ex={}",
				purpose, mask(jwtStr), e.getClass().getName(), e);
			return null;
		}
	}

	// 본문을 가져오거나 문제가 생기면 특정 에러로 변환 (filter용)
	private Claims claimsOrThrow(String jwtStr, String purpose) {
		try {
			Object payload = Jwts.parser()
				.verifyWith(jwtSecretKey)
				.build()
				.parse(jwtStr)
				.getPayload();

			if (payload instanceof Claims claims)
				return claims;

			if (payload instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> m = (Map<String, Object>)map;
				return Jwts.claims().add(m).build();
			}

			log.debug("JWT payload type is not supported. purpose={}, payloadType={}",
				purpose, payload == null ? "null" : payload.getClass().getName());
			throw new JwtAuthenticationException(ErrorType.AUTH_ACCESS_TOKEN_INVALID);
		} catch (ExpiredJwtException e) {
			log.debug("JWT expired. purpose={}, token={}, msg={}", purpose, mask(jwtStr), e.getMessage());
			throw new JwtAuthenticationException(ErrorType.AUTH_ACCESS_TOKEN_EXPIRED);
		} catch (SecurityException e) {
			log.debug("JWT signature/security error. purpose={}, token={}, msg={}", purpose, mask(jwtStr),
				e.getMessage());
			throw new JwtAuthenticationException(ErrorType.AUTH_ACCESS_TOKEN_INVALID);
		} catch (JwtException e) {
			log.debug("JWT invalid. purpose={}, token={}, ex={}, msg={}",
				purpose, mask(jwtStr), e.getClass().getSimpleName(), e.getMessage());
			throw new JwtAuthenticationException(ErrorType.AUTH_ACCESS_TOKEN_INVALID);
		} catch (IllegalArgumentException e) {
			log.debug("JWT illegal argument. purpose={}, token={}, msg={}", purpose, mask(jwtStr), e.getMessage());
			throw new JwtAuthenticationException(ErrorType.AUTH_ACCESS_TOKEN_INVALID);
		} catch (Exception e) {
			log.warn("JWT unexpected error. purpose={}, token={}, ex={}",
				purpose, mask(jwtStr), e.getClass().getName(), e);
			throw new JwtAuthenticationException(ErrorType.AUTH_ACCESS_TOKEN_INVALID);
		}
	}

	private static String extractPublicId(Claims claims) {
		if (claims == null)
			return null;
		String publicId = claims.get("publicId", String.class);
		if (publicId == null)
			publicId = claims.get("id", String.class);
		if (publicId == null || publicId.isBlank())
			return null;
		return publicId;
	}
}
