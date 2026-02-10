package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.domain.NotificationMember;
import com.bugzero.rarego.out.NotificationMemberRepository;
import com.bugzero.rarego.shared.member.domain.MemberDto;

@ExtendWith(MockitoExtension.class)
class NotificationSyncMemberUseCaseTest {

	@Mock
	private NotificationMemberRepository notificationMemberRepository;

	@InjectMocks
	private NotificationSyncMemberUseCase notificationSyncMemberUseCase;

	@Test
	@DisplayName("replica에서 이미 업데이트 된 날짜보다 늦은 변경은 무시")
	void syncMember_SkipDelayedEvent() {
		// given
		LocalDateTime existedUpdatedAt = LocalDateTime.now();
		NotificationMember existed = NotificationMember.builder()
			.id(1L)
			.updatedAt(existedUpdatedAt)
			.build();

		MemberDto member = new MemberDto(
			1L,
			"public-id",
			"user@example.com",
			"nick",
			"intro",
			"address",
			"address detail",
			"12345",
			"01000000000",
			"real name",
			existedUpdatedAt.minusDays(1),
			existedUpdatedAt.minusMinutes(1),
			false
		);

		given(notificationMemberRepository.findById(1L)).willReturn(Optional.of(existed));

		// when
		NotificationMember result = notificationSyncMemberUseCase.syncMember(member);

		// then
		assertThat(result).isSameAs(existed);
		verify(notificationMemberRepository, never()).save(any(NotificationMember.class));
	}

	@Test
	@DisplayName("replica에서 이미 업데이트 된 날짜보다 새로운 업데이트는 반영")
	void syncMember_UpdateWhenNewerEvent() {
		// given
		LocalDateTime existedUpdatedAt = LocalDateTime.now().minusHours(2);
		NotificationMember existed = NotificationMember.builder()
			.id(1L)
			.updatedAt(existedUpdatedAt)
			.build();

		LocalDateTime eventUpdatedAt = LocalDateTime.now();
		MemberDto member = new MemberDto(
			1L,
			"public-id",
			"user@example.com",
			"nick",
			"intro",
			"address",
			"address detail",
			"12345",
			"01000000000",
			"real name",
			eventUpdatedAt.minusDays(1),
			eventUpdatedAt,
			false
		);

		given(notificationMemberRepository.findById(1L)).willReturn(Optional.of(existed));

		// when
		NotificationMember result = notificationSyncMemberUseCase.syncMember(member);

		// then
		assertThat(result).isSameAs(existed);
		assertThat(result.getUpdatedAt()).isEqualTo(eventUpdatedAt);
		assertThat(result.getEmail()).isEqualTo("user@example.com");
		verify(notificationMemberRepository, never()).save(any(NotificationMember.class));
	}

	@Test
	@DisplayName("기존 회원이 없으면 신규 저장")
	void syncMember_SaveWhenNotExists() {
		// given
		LocalDateTime now = LocalDateTime.now();
		MemberDto member = new MemberDto(
			1L,
			"public-id",
			"user@example.com",
			"nick",
			"intro",
			"address",
			"address detail",
			"12345",
			"01000000000",
			"real name",
			now.minusDays(1),
			now,
			false
		);
		given(notificationMemberRepository.findById(1L)).willReturn(Optional.empty());

		// when
		NotificationMember result = notificationSyncMemberUseCase.syncMember(member);

		// then
		assertThat(result.getId()).isEqualTo(1L);
		assertThat(result.getEmail()).isEqualTo("user@example.com");
		verify(notificationMemberRepository).save(any(NotificationMember.class));
	}
}
