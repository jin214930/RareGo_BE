package bugzero.productservice.shared.member.event;

import bugzero.productservice.shared.member.domain.MemberDto;

public record MemberJoinedEvent(
	MemberDto memberDto
) {
}