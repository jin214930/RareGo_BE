-- MySQL 8.4 수동 전환 스크립트. 자동 실행/운영 적용하지 않는다.
-- 대상: 구 two-stage-settlement.sql의 settlement_id 1:1 지급 대기 테이블.
-- 신규 설치에는 현재 two-stage-settlement.sql만 사용한다. 두 파일을 연속 실행하지 않는다.
-- 배치/정산 관련 쓰기를 중지하고 DB 백업 및 복원 절차를 확보한 뒤 적용한다.
-- DDL은 암묵 커밋이므로 전체 파일이 하나의 트랜잭션으로 롤백되지 않는다.
-- SHOW CREATE TABLE로 실제 FK/UNIQUE 이름을 확인한다. Hibernate 생성 이름이면 아래 이름을 맞춘다.
-- 실패한 JobInstance 메타데이터와 run_id는 보존한다. 전환 중 구버전 배치를 재시작하지 않는다.
-- 새 지급 트랜잭션은 READ COMMITTED이다. MySQL binlog_format은 ROW 또는 MIXED여야 한다.

-- 사전 대사: 아래 결과가 0인지 확인한 뒤 다음 단계로 진행한다.
SELECT COUNT(*) AS inconsistent_sources
FROM payment_settlement_payout p
LEFT JOIN payment_settlement s ON s.id = p.settlement_id
WHERE s.id IS NULL OR p.amount <> s.settlement_amount OR p.recipient_id <> s.recipient_id
   OR (p.paid = false AND s.status <> 'PENDING') OR (p.paid = true AND s.status <> 'DONE');

SELECT COUNT(*) AS payout_rows, SUM(amount) AS payout_amount FROM payment_settlement_payout;

-- 기존 지급 대기 ID/금액/paid/run_id를 보존하고, 기존 행은 원천 1건짜리 부분합으로 취급한다.
ALTER TABLE payment_settlement_payout
    MODIFY COLUMN amount BIGINT NOT NULL,
    ADD COLUMN chunk_id BIGINT NULL,
    ADD COLUMN source_count INT NOT NULL DEFAULT 1;

UPDATE payment_settlement_payout SET chunk_id = settlement_id;

ALTER TABLE payment_settlement
    ADD COLUMN payout_id BIGINT NULL,
    ADD INDEX idx_settlement_payout_status_id (payout_id, status, id);

UPDATE payment_settlement s
JOIN payment_settlement_payout p ON p.settlement_id = s.id
SET s.payout_id = p.id;

-- 역연결 대사: 결과가 0인지 반드시 확인한 뒤 기존 settlement_id를 제거한다.
SELECT COUNT(*) AS missing_or_wrong_links
FROM payment_settlement_payout p
LEFT JOIN payment_settlement s ON s.id = p.settlement_id
WHERE s.id IS NULL OR s.payout_id IS NULL OR s.payout_id <> p.id;

ALTER TABLE payment_settlement
    ADD CONSTRAINT fk_settlement_payout FOREIGN KEY (payout_id) REFERENCES payment_settlement_payout (id);

ALTER TABLE payment_settlement_payout
    MODIFY COLUMN chunk_id BIGINT NOT NULL,
    ADD CONSTRAINT uk_payout_run_chunk_recipient UNIQUE (run_id, chunk_id, recipient_id),
    ADD CONSTRAINT ck_payout_source_count CHECK (source_count > 0),
    ALTER COLUMN source_count DROP DEFAULT,
    DROP FOREIGN KEY fk_payout_settlement,
    DROP INDEX uk_payout_settlement,
    DROP COLUMN settlement_id;

-- 사전 기록과 행 수/금액이 같고 source_count 합이 기존 원천 수와 같아야 한다.
SELECT COUNT(*) AS payout_rows, SUM(amount) AS payout_amount, SUM(source_count) AS source_count
FROM payment_settlement_payout;

-- 이전 실패 JobInstance는 새 코드로 재시작한다. 이미 paid인 부분합은 재입금하지 않는다.
-- 새로운 준비 청크부터 실제 합산 행이 생성된다. 과거 지급 이력은 다시 묶거나 삭제하지 않는다.
