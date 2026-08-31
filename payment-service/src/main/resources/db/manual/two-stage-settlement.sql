-- MySQL용 수동 적용 스크립트. 애플리케이션 시작 시 자동 실행하지 않는다.
-- 선행 조건: 정산 원천 분리(recipient_id, type, auction_id/type 유니크 제약) 적용 완료.
-- 구버전 배치를 중지하고 실행 중인 정산이 없을 때 적용한다.
-- 기존 settlement_fee 미처리분과 수수료 원천이 없는 과거 데이터는 별도 대사/전환 후 배포한다.

ALTER TABLE payment_settlement
    MODIFY COLUMN status ENUM('READY', 'PENDING', 'DONE', 'CANCELED', 'FAILED') NOT NULL;

CREATE TABLE payment_settlement_payout (
    id BIGINT NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    amount INT NOT NULL,
    paid BIT(1) NOT NULL DEFAULT 0,
    deleted BIT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_settlement UNIQUE (settlement_id),
    CONSTRAINT fk_payout_settlement FOREIGN KEY (settlement_id) REFERENCES payment_settlement (id),
    CONSTRAINT ck_payout_amount CHECK (amount >= 0),
    INDEX idx_payout_run_paid_recipient (run_id, paid, recipient_id)
) ENGINE = InnoDB;

-- run_id는 Spring Batch JOB_INSTANCE_ID이다. 미지급 건이 있는 배치 메타데이터를 삭제하지 않는다.
-- 지급 대기 행은 중복 방지/감사 기록이므로 입금 후에도 삭제하지 않는다.
