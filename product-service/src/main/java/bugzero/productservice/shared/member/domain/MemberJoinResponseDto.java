package bugzero.productservice.shared.member.domain;

public record MemberJoinResponseDto(
	String nickname,
	String memberPublicId
) {
}
