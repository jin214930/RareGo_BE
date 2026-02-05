package com.bugzero.rarego.app;

import org.springframework.stereotype.Component;

import com.bugzero.rarego.domain.Member;
import com.bugzero.rarego.domain.MemberMeResponseDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetMemberUseCase {
	private final MemberSupport memberSupport;

	/**
	 * 본인 정보 조회
	 * @param publicId
	 * @param role
	 * @return MemberMeResponseDto
	 */
	public MemberMeResponseDto getMe(String publicId, String role) {
		Member member = memberSupport.findByPublicId(publicId);
		return MemberMeResponseDto.from(member, role);
	}
}
