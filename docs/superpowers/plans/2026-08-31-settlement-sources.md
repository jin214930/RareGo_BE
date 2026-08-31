# 정산 원천 분리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.
> 이 저장소에서는 사용자의 현재 승인 범위가 우선한다. 이번 작업은 이슈 #3의 1번 구현만이며 커밋·푸시·배포는 수행하지 않는다.

**Goal:** 정상 결제에서 판매자 지급과 시스템 수수료 원천을 각각 저장하고, 동일한 규칙을 Saga 복구에 적용한다.

**Architecture:** Settlement에 실제 수취인과 지급 유형을 추가한다. 기존 seller는 원거래 판매자 정보와 판매자 조회 응답을 위해 유지한다. 공통 생성 UseCase는 호출자의 로컬 트랜잭션에 반드시 참여하며, 이미 존재하는 유형은 금액·수취인·상태를 검증하고 누락된 유형만 저장한다.

**Tech Stack:** Java 21, Spring Boot 4.0.2, Spring Data JPA, JUnit 5, Mockito, H2.

## Global Constraints

- 브랜치: feat/payment/#3 (저장소 CI 규칙: 타입/도메인/#이슈번호).
- 기존 package-lock.json 변경은 보존한다.
- 정산 배치 2단계 전환 및 환불 수정은 이번 범위에서 제외한다.
- 새 원천 모델은 기존 배치와 호환되지 않는다. 이 브랜치를 단독으로 운영에 배포하거나 신규 원천에 기존 지급 배치를 실행하지 않는다.
- 기존 경매당 한 건 스키마의 운영 데이터 변환은 2번 작업과 함께 별도 검증 후 수행한다. Hibernate ddl-auto=update가 기존 단독 유일 제약을 안전하게 제거한다고 가정하지 않는다.

## Task 1: 원천 모델과 금액 계약

**Files:** payment-service/src/main/java/com/bugzero/rarego/domain/Settlement.java, SettlementType.java; payment-service/src/test/java/com/bugzero/rarego/domain/SettlementSourceTest.java.

**Interfaces:**

```java
List<Settlement> Settlement.createPaymentSources(Long auctionId, String productName,
    PaymentMember seller, PaymentMember systemMember, int salesAmount);
Settlement Settlement.createFromForfeit(Long auctionId, String productName,
    PaymentMember seller, int forfeitAmount);
```

- [x] 판매자/시스템 수취인, 10% 절사, 0원 수수료, int 최대 금액의 보존, 음수 거절, 몰수 상품명 테스트를 작성한다.
- [x] 구현 전 테스트가 누락된 팩토리/필드 때문에 실패하는지 확인한다.
- [x] recipient와 type을 추가하고 (auction_id, type) 유일 제약을 정의한다. 유형은 SELLER_PROCEEDS, PLATFORM_FEE, DEPOSIT_FORFEIT이다.
- [x] 정상 결제 두 원천에는 동일한 원거래 금액·수수료 메타데이터를 저장하되, 각 settlementAmount는 해당 수취인에게 지급할 금액만 갖는다.

## Task 2: 트랜잭션 내 공통 생성과 호출부

**Files:** app/PaymentCreateSettlementUseCase.java, PaymentAuctionFinalUseCase.java, PaymentAuctionFinalSagaRecoveryUseCase.java, PaymentAuctionTimeoutUseCase.java, out/SettlementRepository.java. 기본 위치는 payment-service/src/main/java/com/bugzero/rarego/이다.

**Interfaces:**

```java
List<Settlement> createForPayment(Long auctionId, String productName, PaymentMember seller, int salesAmount);
boolean hasCompletePaymentSources(Long auctionId, String productName, PaymentMember seller, int salesAmount);
void validateNotCreated(Long auctionId);
Settlement createFromForfeit(Long auctionId, String productName, PaymentMember seller, int forfeitAmount);
List<Settlement> SettlementRepository.findAllByAuctionIdForUpdate(Long auctionId);
```

- [x] 공통 생성은 Propagation.MANDATORY로 호출자의 트랜잭션에 참여한다.
- [x] 정상 결제와 몰수는 지갑 락 획득 후 validateNotCreated로 중복 원천을 확인하고, 이미 처리된 요청이면 구매자 잔액/보증금을 다시 차감하지 않는다.
- [x] 기존 원천의 유형·수취인·판매자·금액·상품명·상태를 검증한다. 동일 원천은 재사용하고, 다른 금액/수취인 및 취소·실패 원천은 덮어쓰지 않고 예외로 중단한다.
- [x] 정상 결제와 Saga 복구가 createForPayment를 호출한다. 복구 완료 판단은 필요한 두 유형과 금액을 모두 확인한다.
- [x] SETTLEMENT_READY 체크포인트가 있어도 일부 원천이 누락되었다면 실제 원천 기준으로 복구한다. 이미 차감된 보증금/잔금은 다시 차감하지 않는다.
- [x] 몰수 경로는 상품명을 전달해 단일 판매자 원천을 만든다.
- [x] 판매자 내역 조회는 PLATFORM_FEE를 제외하여 기존 응답 의미를 유지한다. 환불 단건 조회는 변경하지 않는다.

## Task 3: 검증과 후속 배포 조건

**Files:** payment-service/src/test/java/com/bugzero/rarego/app/PaymentCreateSettlementUseCaseTest.java, PaymentCreateSettlementUseCaseIntegrationTest.java, PaymentAuctionFinalSagaRecoveryUseCaseTest.java 및 기존 결제/몰수 테스트.

- [x] 생성 요청 반복·한 유형 누락·금액 불일치·취소 원천을 검증한다.
- [x] JPA 테스트에서 경매당 서로 다른 두 유형 저장, 동일 유형 중복 제약, 몰수 필수 필드 저장, 판매자 조회 제외 조건을 검증한다.
- [x] 두 번째 원천 저장 실패를 주입하고 구매자 지갑 변경과 첫 번째 저장이 함께 롤백되는지 확인한다.
- [x] Saga의 완료 체크포인트/원천 실재 여부 조합을 검증한다.
- [x] 선택 테스트 후 payment-service 회귀 테스트를 실행하고, 외부 인프라 문제와 실제 코드 실패를 구분하여 보고한다.

```powershell
.\gradlew.bat :payment-service:test --tests '*SettlementSourceTest' --tests '*PaymentCreateSettlementUseCase*' --tests '*PaymentAuctionFinal*' --tests '*PaymentAuctionTimeoutUseCaseTest' --console=plain
.\gradlew.bat :payment-service:test --console=plain
```

운영 전 필수 후속 작업: 기존 단독 auction_id 유일 제약 제거, recipient/type 데이터 전환, 기지급 수수료 대사, 새 배치 연결, 경매당 두 원천에 대한 환불 호환성 처리. 이번 작업에서는 운영 DB를 변경하지 않는다.
## 검증 결과 (2026-08-31)

- payment-service 전체 테스트: 27개 suite, 131개 테스트, 실패 0, 오류 0, 건너뜀 0.
- JPA 테스트는 격리된 H2 테스트 DB에서 수행했다. 운영 MySQL 스키마 및 기존 데이터에는 변경을 적용하지 않았다.
- checkstyleMain/checkstyleTest 실행 완료. 이번 변경 파일에는 경고가 없으며, 수정하지 않은 파일의 기존 경고 10개는 유지했다.
- git diff --check 통과. 환불 및 기존 배치/지급 프로세서는 수정하지 않았다.
- 코드 커밋·푸시·배포는 수행하지 않았다.
