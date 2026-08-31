# 정산 성능 비교

결제 건수와 분포를 동일하게 맞춰 세 가지 정산 방식을 비교한다. 이 소스 세트는 운영 클래스패스와 일반 `test`/`build`/CI 실행에서 제외되며, 전용 Gradle 태스크로만 실행한다.

| 모드 | 처리 방식 |
|---|---|
| `single` | 직접 지급, 단일 스레드 |
| `partitioned` | 직접 지급, 수취인 ID 파티셔닝 5스레드 |
| `two-stage` | 현재 운영 코드: 정산 ID 파티셔닝으로 부분합 준비 후 수취인별 지급 |

직접 지급 기준은 `36e3b8e9`의 로직을 복제한 `Legacy*` 클래스다. 원천 분리 이후, 2단계 도입 이전 버전이며 과거 별도 수수료 큐 방식은 아니다. 세 모드 모두 현재 엔티티/인덱스 스키마를 사용한다. 비교 기준을 의도적으로 변경하지 않는 한 `Legacy*`에 새 지급 로직을 반영하지 않는다.

## 주의사항

- **벤치마크 전용 MySQL만 사용한다.** 실행 중 테이블을 생성·삭제하고 시나리오마다 데이터를 초기화한다. 개발/운영 DB로 포트 포워딩하지 않는다.
- 호스트 실행은 `127.0.0.1:13316/settlement_benchmark`, 컨테이너 실행은 같은 네트워크의 `127.0.0.1:3306/settlement_benchmark`에 접속한다. 임의 DB URL은 입력받지 않는다.
- `benchmark-local-only`는 격리된 로컬 DB의 테스트 전용 암호다. 운영 자격 증명이 아니다. 아래 호스트 포트는 루프백에만 공개한다.
- 아래 명령은 저장소 루트의 PowerShell 기준이다. 같은 이름의 컨테이너가 있으면 소유/용도를 확인하고 충돌을 해결한 뒤 실행한다. 기존 컨테이너나 데이터를 자동 삭제하지 않는다.
- 측정 중 실행 번들 재생성, 다른 벤치마크, 빌드/테스트 등 부하 작업을 피한다. 워밍업과 시나리오 실행 순서의 영향까지 완전히 제거하는 측정은 아니다.

## 전용 MySQL 준비

```powershell
docker run -d --name rarego-settlement-benchmark --label purpose=settlement-benchmark --cpus=4 --memory=6g -p 127.0.0.1:13316:3306 -e MYSQL_ROOT_PASSWORD=benchmark-local-only -e MYSQL_DATABASE=settlement_benchmark mysql:8.4.5 --innodb-buffer-pool-size=2147483648 --innodb-flush-log-at-trx-commit=1 --sync-binlog=1
docker exec rarego-settlement-benchmark mysqladmin ping -uroot --password=benchmark-local-only
```

`mysqld is alive`를 확인한 뒤 측정한다. 초기화 중이면 준비 상태를 다시 확인한다.

## 호스트에서 실행

Java 21과 Docker가 필요하다. 결과 집계에는 Python 3이 필요하다.

```powershell
.\gradlew.bat :payment-service:settlementBenchmark '-Pbenchmark.sizes=100000' '-Pbenchmark.repetitions=1' '-Pbenchmark.chunkSize=1000' '-Pbenchmark.warmup=1000' --console=plain
```

위 명령은 일반/편중 × 3가지 방식, 총 6개 조합을 각각 1회 측정한다. PowerShell에서는 점이 포함된 `-P`/`-D` 옵션 전체를 따옴표로 감싼다. Windows 호스트 측정과 Linux Docker 측정은 같은 결과 집합으로 합치지 않는다.

## JVM도 Docker에서 실행

DB와 JVM의 자원을 각각 4CPU/6GiB로 제한하는 방식이다. 실제 비교에 사용한 JVM은 Temurin `21.0.11+10-LTS`, 힙 4GiB였다. 아래 `eclipse-temurin:21-jre` 태그는 가변이므로 반복 비교 시 이미지 digest와 실제 Java 버전을 고정·기록한다. 버전이 다른 결과는 합치지 않는다.

```powershell
.\gradlew.bat :payment-service:settlementBenchmarkBundle --console=plain
$benchmarkRuntime = (Resolve-Path 'payment-service/build/settlement-benchmark-runtime').Path
New-Item -ItemType Directory -Force -Path 'payment-service/build/settlement-benchmark' | Out-Null
$benchmarkResults = (Resolve-Path 'payment-service/build/settlement-benchmark').Path
docker run --name rarego-settlement-benchmark-once --network container:rarego-settlement-benchmark --cpus=4 --memory=6g --mount "type=bind,source=$benchmarkRuntime,target=/bench,readonly" --mount "type=bind,source=$benchmarkResults,target=/results" --entrypoint java eclipse-temurin:21-jre -Xms4g -Xmx4g '-Dbenchmark.output=/results' '-Dbenchmark.container=true' '-Dbenchmark.sizes=100000' '-Dbenchmark.repetitions=1' '-Dbenchmark.chunkSize=1000' '-Dbenchmark.warmup=1000' -cp '/bench/classes:/bench/lib/*' com.bugzero.rarego.benchmark.SettlementBenchmarkTest
```

컨테이너 실행 중에는 `settlementBenchmarkBundle`을 다시 실행하지 않는다. 같은 호스트 번들이 읽기 전용 마운트의 원본이므로 실행 중 클래스가 바뀔 수 있다. 반복 실행하려면 기존 종료 결과를 확인하고 별도 실행 컨테이너 이름을 사용한다.

## 옵션과 시나리오

| 옵션 | 기본값 | 의미 |
|---|---|---|
| `benchmark.sizes` | `100000,300000` | 결제 건수, 쉼표로 구분 |
| `benchmark.repetitions` | `3` | 각 조합의 반복 횟수 |
| `benchmark.chunkSize` | `1000` | 직접 지급/부분합 준비 청크 크기 |
| `benchmark.warmup` | `1000` | 모드별 워밍업 결제 건수, `0`이면 생략 |
| `benchmark.modes` | `single,partitioned,two-stage` | 실행 모드 선택 |

옵션을 생략하면 30만 건과 3회 반복까지 실행되므로 짧게 확인할 때는 위 1회 명령을 사용한다. 2단계 지급 청크는 수취인 1명이며 `benchmark.chunkSize`로 변경되지 않는다. 부분합 지급 트랜잭션만 `READ COMMITTED`이고, DB 기본 격리 수준/직접 지급은 `REPEATABLE READ`다.

- 결제 1건은 판매자 정산/시스템 수수료 원천 2행이다. 결제 10만 건이면 원천 20만 행이다.
- 결제 금액 1,000원 = 판매자 900원 + 수수료 100원이다.
- `uniform`: 판매자 10,000명에게 순환 분배한다.
- `hot90`: 판매자 1명에게 90%, 나머지 10%를 다른 판매자에게 분배한다.
- 시스템 수수료는 두 시나리오 모두 한 수취인에게 모인다.
- 반복 시 모드 시작 순서를 순환시킨다. 각 모드의 시나리오 순서는 일반 → 편중이다.

## 측정 범위와 결과

정산 Job 실행부터 종료까지 실제 시간을 잰다. DB/Outbox 저장은 포함하며 데이터 생성·초기화·사후 검증과 Kafka 전송은 제외한다. 원장별 원천 참조/금액/타입/수취인, 지갑 잔액, 원천 완료, 부분합 합계/건수, 이벤트 합계를 검증한 뒤에만 CSV 행을 기록한다. 실패 실행은 JSON/로그를 확인하고 성공 결과에 포함하지 않는다.

결과는 `payment-service/build/settlement-benchmark/<UTC 실행 시각>/`에 생성된다.

- `results.csv`: 전체/준비/지급 시간, GC, 지갑 UPDATE, Outbox, InnoDB 잠금 대기. `repeat=0`은 워밍업이다.
- `<모드>-<분포>-<건수>-<반복>.json`: 상태, 단계별 실행 시간·처리 건수·커밋/롤백 수, DB 카운터.
- `*-counts.json`: 부분합 행 수/원천 건수, 원장 및 완료 원천 행 수.
- `environment.json`, `mysql.json`: 런타임·측정 옵션·구현 모델·MySQL 설정.

상위 단계의 합계와 하위 파티션의 건수/커밋 수를 중복 합산하지 않는다. 전체 시간은 경계 설정·단계 전환을 포함하므로 준비+지급 시간의 합과 조금 다를 수 있다. Job 실행 직전/직후 전역 잠금 카운터를 비교하므로 반드시 전용 DB에서 단독 실행한다.

실행 로그의 `BENCHMARK_RESULTS` 경로를 집계 스크립트에 전달한다.

```powershell
python payment-service/src/benchmark/summarize.py payment-service/build/settlement-benchmark/<실행시각> --output payment-service/build/settlement-benchmark/summary.md
```

여러 결과 디렉터리를 전달하면 조합별 중앙값/최소·최대를 집계한다. 런타임/청크/구현 모델이 다른 결과는 합치지 않는다. 스크립트는 3회 비교를 기준으로 경고하므로 의도적으로 1회만 실행한 결과에도 반복 부족 경고가 표시된다. 한 번의 관측으로 안정적인 성능 우위를 단정하지 않는다.

## 종료 및 Git 관리

측정 프로세스가 종료됐는지 확인한 뒤 이번에 만든 전용 DB만 중지한다.

```powershell
docker stop rarego-settlement-benchmark
```

컨테이너/볼륨은 자동 삭제하지 않는다. 벤치마크 코드·Gradle 설정·이 README는 버전 관리하고, 생성된 CSV/JSON/로그/클래스는 `build/` 아래에 보관해 Git에서 제외한다. 프로젝트의 `/docs/` 제외 정책도 유지한다.
