package com.bugzero.rarego.global.inbox.domain;

import com.bugzero.rarego.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
	name = "INBOX_EVENT",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "unq_inbox_message_group",
			columnNames = {"message_id", "consumer_group"} // 동일 그룹 내 중복 방지 핵심
		)
	},
	indexes = {
		@Index(name = "idx_inbox_created_at", columnList = "createdAt")
	}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class InboxEvent extends BaseIdAndTime {

	@Column(name = "message_id", length = 150, nullable = false)
	private String messageId; // 형식: AggregateType-OutboxID (ex: Product-101)

	@Column(nullable = false, length = 100)
	private String consumerGroup; // 중복 체크를 수행하는 서비스 그룹명

	// 정적 팩토리 메서드
	public static InboxEvent createInboxEvent(String messageId, String consumerGroup) {
		return InboxEvent.builder()
			.messageId(messageId)
			.consumerGroup(consumerGroup)
			.build();
	}
}
