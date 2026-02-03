package bugzero.productservice.shared.member.event;

import bugzero.productservice.shared.member.domain.MemberDto;

/**
 * 회원 수정 발생 이벤트
 * @param memberDto
 */
public record MemberUpdatedEvent(
	MemberDto memberDto
) {}