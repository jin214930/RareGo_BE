-- MySQL용 수동 적용 스크립트. 애플리케이션 시작 시 자동 실행하지 않는다.
-- 선행 조건: 정산 원천 분리(recipient_id, type, auction_id/type 유니크 제약) 적용 완료.
-- 구버전 배치를 중지하고 실행 중인 정산이 없을 때 적용한다.
-- 기존 settlement_fee 미처리분과 수수료 원천이 없는 과거 데이터는 별도 대사/전환 후 배포한다.
-- 이 파일은 지급 대기 테이블이 없는 환경의 신규 설치용이다.
-- 기존 1:1 지급 대기 테이블이 있으면 partial-sum-settlement.sql로 전환한다.
-- 지급 트랜잭션은 READ COMMITTED이다. MySQL binlog_format은 ROW 또는 MIXED여야 한다.

ALTER TABLE payment_settlement
    MODIFY COLUMN status ENUM('READY', 'PENDING', 'DONE', 'CANCELED', 'FAILED') NOT NULL;

CREATE TABLE payment_settlement_payout (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    source_count INT NOT NULL,
    paid BIT(1) NOT NULL DEFAULT 0,
    deleted BIT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_run_chunk_recipient UNIQUE (run_id, chunk_id, recipient_id),
    CONSTRAINT ck_payout_amount CHECK (amount >= 0),
    CONSTRAINT ck_payout_source_count CHECK (source_count > 0),
    INDEX idx_payout_run_paid_recipient (run_id, paid, recipient_id)
) ENGINE = InnoDB;

ALTER TABLE payment_settlement
    ADD COLUMN payout_id BIGINT NULL,
    ADD CONSTRAINT fk_settlement_payout FOREIGN KEY (payout_id) REFERENCES payment_settlement_payout (id),
    ADD INDEX idx_settlement_payout_status_id (payout_id, status, id);

-- run_id는 Spring Batch JOB_INSTANCE_ID이다. 미지급 건이 있는 배치 메타데이터를 삭제하지 않는다.
-- 지급 대기 행은 중복 방지/감사 기록이므로 입금 후에도 삭제하지 않는다.
