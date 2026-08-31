package com.bugzero.rarego.benchmark;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/** 준비/검증 쿼리는 타이밍에 포함하지 않는다. 설정 변경으로 다른 DB를 지우지 못하게 제한한다. */
final class SettlementBenchmarkFixture {
	private final JdbcTemplate jdbc;
	private final Map<Long, Long> expected = new HashMap<>();

	SettlementBenchmarkFixture(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("settlement_benchmark");
	}

	void seed(int payments, boolean skewed) {
		for (String table : List.of("payment_transaction", "payment_settlement_fee", "payment_settlement",
			"payment_settlement_payout", "outbox_event", "payment_wallet", "payment_member")) {
			jdbc.update("DELETE FROM " + table);
		}
		expected.clear();
		List<Object[]> members = new ArrayList<>();
		members.add(member(2));
		expected.put(2L, payments * 100L);
		for (int i = 0; i < 10000; i++) {
			members.add(member(101 + i));
			expected.put(101L + i, 0L);
		}
		jdbc.batchUpdate("""
			INSERT INTO payment_member (id, public_id, email, nickname, deleted, created_at, updated_at)
			VALUES (?, ?, ?, ?, false, '2026-01-01', '2026-01-01')
			""", members);
		jdbc.update("""
			INSERT INTO payment_wallet (member_id, balance, holding_amount, deleted, created_at, updated_at)
			SELECT id, 0, 0, false, '2026-01-01', '2026-01-01' FROM payment_member
			""");
		List<Object[]> rows = new ArrayList<>();
		for (int i = 0; i < payments; i++) {
			long seller = skewed ? (i % 10 < 9 ? 101 : 102 + (i / 10) % 9999) : 101 + i % 10000;
			expected.merge(seller, 900L, Long::sum);
			rows.add(new Object[] {2L * i + 1, i + 1L, seller, seller, "SELLER_PROCEEDS", 900});
			rows.add(new Object[] {2L * i + 2, i + 1L, seller, 2L, "PLATFORM_FEE", 100});
			if (rows.size() == 2000) {
				insertSources(rows);
				rows.clear();
			}
		}
		if (!rows.isEmpty()) {
			insertSources(rows);
		}
		assertThat(count("SELECT COUNT(*) FROM payment_settlement")).isEqualTo(2L * payments);
		assertThat(expected.values()).allMatch(amount -> amount <= Integer.MAX_VALUE);
	}

	private Object[] member(long id) {
		return new Object[] {id, "benchmark-" + id, "bench" + id + "@example.invalid", "bench" + id};
	}

	private void insertSources(List<Object[]> rows) {
		jdbc.batchUpdate("""
			INSERT INTO payment_settlement
			(id, auction_id, seller_id, recipient_id, type, settlement_amount, sales_amount, fee_amount,
			product_name, status, try_count, deleted, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, 1000, 100, 'benchmark product', 'READY', 0, false,
			'2026-01-01', '2026-01-01')
			""", rows);
	}

	void verify(int payments, boolean twoStage) {
		assertThat(count("SELECT COUNT(*) FROM payment_settlement WHERE status='DONE'")).isEqualTo(2L * payments);
		assertThat(count("SELECT COUNT(*) FROM payment_transaction")).isEqualTo(2L * payments);
		assertThat(count("SELECT COUNT(DISTINCT reference_id) FROM payment_transaction")).isEqualTo(2L * payments);
		assertThat(count("SELECT SUM(balance_delta) FROM payment_transaction")).isEqualTo(1000L * payments);
		assertThat(count("SELECT COUNT(*) FROM payment_settlement_fee")).isZero();
		assertThat(count("SELECT COALESCE(SUM(source_count), 0) FROM payment_settlement_payout"))
			.isEqualTo(twoStage ? 2L * payments : 0);
		assertThat(count("SELECT COUNT(*) FROM payment_settlement WHERE payout_id IS NOT NULL"))
			.isEqualTo(twoStage ? 2L * payments : 0);
		assertThat(count("""
			SELECT COUNT(*) FROM payment_settlement_payout p LEFT JOIN (
				SELECT payout_id, COUNT(*) AS cnt, SUM(settlement_amount) AS amount
				FROM payment_settlement WHERE payout_id IS NOT NULL GROUP BY payout_id
			) s ON p.id=s.payout_id
			WHERE s.payout_id IS NULL OR p.source_count <> s.cnt OR p.amount <> s.amount
			""")).isZero();
		assertThat(count("SELECT COUNT(*) FROM payment_settlement_payout WHERE paid=false")).isZero();
		assertThat(count("SELECT COUNT(*) FROM payment_wallet")).isEqualTo(expected.size());
		jdbc.query("SELECT member_id, balance, holding_amount FROM payment_wallet", rs -> {
			assertThat(rs.getLong("balance")).isEqualTo(expected.get(rs.getLong("member_id")));
			assertThat(rs.getInt("holding_amount")).isZero();
		});
		assertThat(count("""
			SELECT COUNT(*) FROM payment_transaction t JOIN payment_settlement s ON s.id=t.reference_id
			WHERE t.reference_type <> 'SETTLEMENT' OR t.member_id <> s.recipient_id
			OR t.balance_delta <> s.settlement_amount
			OR t.transaction_type <> IF(s.type='PLATFORM_FEE','SETTLEMENT_FEE','SETTLEMENT_PAID')
			""")).isZero();
		assertThat(count("SELECT SUM(JSON_EXTRACT(payload, '$.totalCount')) FROM outbox_event")).isEqualTo(payments);
		assertThat(count("SELECT SUM(JSON_EXTRACT(payload, '$.totalAmount')) FROM outbox_event"))
			.isEqualTo(900L * payments);
	}

	long count(String sql) {
		return jdbc.queryForObject(sql, Long.class);
	}
}
