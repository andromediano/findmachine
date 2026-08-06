# 미국 서비스 확장 대비 — 다중 타임존 / DST / UTC 전환 대응 자료

> 작성 기준일: 2026-08-03
> 대상 스택: Spring Boot 2.3.x / Java 11, MyBatis, Oracle · PostgreSQL, React + Ant Design, ShedLock + Redis, Podman, Prometheus/Grafana, Elasticsearch/Kibana, Kafka

---

## 0. 요약 — 이 문서의 결론 먼저

| # | 원칙 | 한 줄 요약 |
|---|---|---|
| 1 | **저장은 UTC** | DB는 `timestamptz`(PG) / `TIMESTAMP WITH TIME ZONE`(Oracle). 로컬 시각을 그대로 저장하지 않는다. |
| 2 | **연산은 UTC** | 애플리케이션 내부는 `Instant` 단일 타입. `ZoneId.systemDefault()` 사용 금지. |
| 3 | **변환은 경계에서만** | UI 렌더링, 리포트 출력, 외부 연동 어댑터 — 이 3곳에서만 변환. |
| 4 | **오프셋 ≠ 타임존** | `-05:00`은 *결과*, `America/New_York`은 *규칙*. 미래 시각은 반드시 IANA Zone ID를 함께 저장. |
| 5 | **주(State) 단위 매핑 금지** | 인디애나·켄터키·노스다코타 등은 주 내부가 분할됨. 위치 엔티티가 Zone ID를 직접 보유. |
| 6 | **하루 ≠ 86400초** | DST 전환일은 23시간 / 25시간. `plusHours(24)`와 `plusDays(1)`은 다르다. |

> **가장 큰 단일 리스크**: JVM/OS/DB 기본 타임존을 KST → UTC로 바꾸는 순간, "인프라 설정을 암묵적으로 신뢰"하던 **기존 한국 로직이 조용히 9시간 틀어진다.** 예외도 안 나고 로그도 안 남는다. → 4장(코드 인벤토리)과 9장(마이그레이션 전략)이 실질적인 핵심.

---

## 1. 미국 타임존 실태 — 왜 "4개 타임존"이 거짓말인가

### 1.1 실제로 필요한 Zone 목록

| 구분 | IANA Zone ID | 표준/서머 | DST |
|---|---|---|---|
| Eastern | `America/New_York` | EST(-5) / EDT(-4) | O |
| Central | `America/Chicago` | CST(-6) / CDT(-5) | O |
| Mountain | `America/Denver` | MST(-7) / MDT(-6) | O |
| Mountain (AZ) | `America/Phoenix` | MST(-7) 고정 | **X** |
| Pacific | `America/Los_Angeles` | PST(-8) / PDT(-7) | O |
| Alaska | `America/Anchorage` | AKST(-9) / AKDT(-8) | O |
| Hawaii | `Pacific/Honolulu` | HST(-10) 고정 | **X** |
| Puerto Rico | `America/Puerto_Rico` | AST(-4) 고정 | X |
| Guam | `Pacific/Guam` | ChST(+10) 고정 | X |

주 단위로 다뤄서는 안 되는 케이스:

- **애리조나**: DST 미적용. 단, 주 내 **나바호 자치국(Navajo Nation)은 DST 적용**(`America/Denver` 규칙), 그 안에 둘러싸인 **호피 보호구역(Hopi)은 미적용**. 도넛 구조.
- **인디애나** → 아래 별도 절.
- **켄터키**: `America/Kentucky/Louisville`, `America/Kentucky/Monticello`
- **노스다코타**: `America/North_Dakota/Center`, `/New_Salem`, `/Beulah` (3개)
- **주 내부 ET/CT 또는 CT/MT 분할**: 플로리다(팬핸들), 테네시, 미시간(서부 4개 카운티), 텍사스(엘패소 지역 MT), 캔자스·네브래스카·노스다코타·사우스다코타(서부 MT), 오리건(말루어 카운티 MT), 아이다호(남부 MT / 북부 PT)

### 1.2 인디애나 — 대표 함정 사례

- 92개 카운티 중 대부분이 **Eastern**, 시카고 근교 북서부(Lake, Porter, LaPorte, Newton, Jasper, Starke)와 에번스빌 중심 남서부(Vanderburgh, Warrick, Posey, Gibson, Spencer, Perry, Pike 등) 약 12개 카운티가 **Central**.
- **2006년 이전에는 인디애나 대부분이 DST를 적용하지 않았다.** 2006년 4월부터 주 전역 DST 적용. 게다가 일부 카운티는 2006~2007년 사이에 ET↔CT를 넘나들었다.
- 그 결과 IANA tzdb는 인디애나에만 **8개 Zone**을 유지한다:
  `America/Indiana/Indianapolis`, `/Vincennes`, `/Winamac`, `/Marengo`, `/Petersburg`, `/Vevay`, `/Tell_City`, `/Knox`
- **시사점 3가지**
  1. `state → timezone` 매핑 테이블은 **틀린 설계**다. 위치 엔티티(지점/매장/고객 주소)에 Zone ID 컬럼을 직접 둬야 한다.
  2. **과거 데이터 재계산이 위험하다.** 2005년 인디애나 데이터를 "지금의 ET 규칙"으로 역산하면 1시간 틀린다. tzdb는 역사 규칙을 갖고 있으므로 **반드시 tzdb 기반 변환**(`ZonedDateTime.ofInstant`)을 쓰고, 직접 오프셋 산술을 하지 말 것.
  3. `America/Indianapolis`(구 이름)는 tzdb에서 backward link로만 남아 있다. 정규 ID로 정규화하는 로직 필요.

### 1.3 DST 전환 규칙 (2007년~ 현행, Energy Policy Act 2005)

- **시작**: 3월 **둘째** 일요일 02:00 local → 03:00 (하루 23시간)
- **종료**: 11월 **첫째** 일요일 02:00 local → 01:00 (하루 25시간)

| 연도 | 시작 | 종료 |
|---|---|---|
| 2026 | 3/8 | 11/1 |
| 2027 | 3/14 | 11/7 |
| 2028 | 3/12 | 11/5 |

### 1.4 ⚠️ 진행 중인 법률 리스크 — Sunshine Protection Act

- **2026-07-14, 미 하원이 H.R. 139(Sunshine Protection Act)를 308-117로 가결.** 연중 상시 서머타임(permanent DST)으로 전환하는 내용이며, **현재 상원 계류 중**이고 통과 여부는 불확실하다. 주별로 사전 면제(exempt) 선택이 가능한 구조.
  - 출처: <https://energycommerce.house.gov/posts/house-passes-legislation-to-make-daylight-saving-time-permanent>, <https://www.nbcnews.com/politics/congress/house-passes-bill-daylight-saving-time-permanent-sunshine-protection-rcna587531>
- **설계에 미치는 영향 (중요)**
  - DST 규칙을 코드/설정에 **하드코딩하면 안 된다.** ("3월 둘째 주 일요일" 같은 상수 금지)
  - 법 통과 시 tzdb가 갱신되고 **미래 시각의 UTC 값이 통째로 바뀐다.** → 미래 예약을 UTC로만 저장해 둔 시스템은 전부 1시간 틀어진다.
  - ⇒ **미래 이벤트는 `(로컬 시각 + Zone ID)`를 원본으로 저장하고, UTC는 파생 캐시로 취급 + 재계산 배치를 마련**해야 한다. (6.2절)
  - 주별 면제가 허용되면 주 내 분할이 **지금보다 더 복잡해질 수 있다.** Zone ID 기반 설계의 필요성이 더 커짐.

### 1.5 tzdb 버전 관리

- 최신 릴리스: **2026c** (2026-07-08), 그 이전 2026b(4월), 2026a(3월). 연 2~5회 비정기 릴리스.
- 2026c 코멘터리에는 캐나다 노스웨스트 준주가 2026-11-01 이전 상시 -06으로 이동 예정이라는 내용이 주석 상태로 포함되어 있다 — **아직 확정 아님.** 이런 식으로 규칙은 수시로 바뀐다.
- TZif 포맷은 **RFC 9636**으로 표준화됨.
- 참고: <https://lists.iana.org/hyperkitty/list/tz-announce@iana.org/latest>
- ⇒ **tz-announce 메일링 리스트 구독을 운영 프로세스에 포함**할 것.

---

## 2. DST가 만드는 3가지 근본 문제

### 2.1 존재하지 않는 시각 (Gap / Spring Forward)

2026-03-08 `America/New_York` 기준 **02:00 ~ 02:59:59는 존재하지 않는다.**

```java
ZoneId ny = ZoneId.of("America/New_York");
LocalDateTime lt = LocalDateTime.of(2026, 3, 8, 2, 30);

ny.getRules().getValidOffsets(lt);        // [] — 빈 리스트
ZonedDateTime.of(lt, ny);                 // 2026-03-08T03:30-04:00 (gap 만큼 밀림, 예외 없음)
```

> **예외가 안 난다는 게 핵심 위험.** 조용히 1시간 밀려서 흘러간다.

### 2.2 중복되는 시각 (Overlap / Fall Back)

2026-11-01 `America/New_York` 기준 **01:00 ~ 01:59:59는 두 번 발생한다.**

```java
LocalDateTime lt = LocalDateTime.of(2026, 11, 1, 1, 30);
ny.getRules().getValidOffsets(lt);        // [-04:00, -05:00] — 2개

ZonedDateTime.of(lt, ny);                 // 기본은 이른 쪽(-04:00, EDT)
ZonedDateTime.of(lt, ny).withLaterOffsetAtOverlap();   // -05:00 (EST)
```

### 2.3 하루 ≠ 24시간

```java
ZonedDateTime start = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny);

start.plusDays(1);    // 2026-03-09T00:00-04:00  ← 벽시계 기준, 실경과 23시간
start.plusHours(24);  // 2026-03-09T01:00-04:00  ← 절대시간 기준
Duration.between(start, start.plusDays(1)).toHours();   // 23
```

| 의도 | 사용할 API | 사용할 타입 |
|---|---|---|
| "다음 날 같은 시각" (영업일, 청구일) | `plusDays` / `Period` | `ZonedDateTime` + Zone |
| "정확히 24시간 후" (SLA, TTL, 타임아웃) | `plusHours` / `Duration` | `Instant` |

⚠️ 로컬 자정 경계에서 시간 단위 파티션/버킷 개수도 달라진다 (**23개 / 25개**). 시간별 집계 배치·백필 로직이 24를 상수로 갖고 있으면 깨진다.

### 2.4 정책으로 **결정**해야 하는 것들 (기술 문제가 아니라 도메인 문제)

| 상황 | 선택지 |
|---|---|
| 예약이 존재하지 않는 시각(02:30)에 잡힌 경우 | (a) 스킵 (b) 03:30으로 밀기 (c) 01:30으로 당기기 (d) 등록 시점에 거부 |
| 배치가 중복 시각(01:30)에 걸린 경우 | (a) 1회만 실행 (멱등성 보장) (b) 2회 실행 허용 |
| DST 전환일의 "일 마감" | 23시간/25시간을 그대로 인정할 것인가, 24시간으로 정규화할 것인가 |
| 매월 청구 주기 | 로컬 기준 매월 1일 00:00인가, 고정 UTC 시각인가 |

→ **이건 개발이 임의로 정하면 안 되고, 기획/현업 확인이 필요한 항목이다.** (12장 참조)

---

## 3. DB 레이어

### 3.1 PostgreSQL

| 타입 | 실제 동작 | 권장 |
|---|---|---|
| `timestamptz` | 내부적으로 **UTC 마이크로초로 정규화 저장**. 입출력 시 세션 `TimeZone`으로 변환 | ✅ **기본값으로 사용** |
| `timestamp` (without tz) | 입력 문자열을 **해석 없이 그대로** 저장. 절대시각 비교 불가 | ❌ 사용 금지 |
| `date` | 순수 달력 날짜 | 영업일(business date) 용도로만 |

**주의점**

- `timestamptz`는 **"원래 어느 존이었는지"를 저장하지 않는다.** 존이 필요하면 `zone_id VARCHAR(64)` 컬럼을 별도로 둔다.
- 세션 타임존에 따라 **같은 쿼리가 다른 결과 문자열**을 낸다 → 서버 `postgresql.conf`의 `timezone = 'UTC'` 고정 + 커넥션 레벨에서도 고정.
- **pgJDBC는 커넥션 시 JVM 기본 TimeZone으로 `SET TIME ZONE`을 보낸다.** JVM TZ가 KST면 DB 설정이 UTC여도 세션은 KST가 된다. → JVM TZ 고정이 필수.

```sql
-- 로컬 기준 일별 집계
SELECT date_trunc('day', occurred_at AT TIME ZONE 'America/New_York') AS local_day,
       count(*)
  FROM events
 GROUP BY 1;

-- PG 16+ : 3-인자 date_trunc (타임존 인자 직접 지원)
SELECT date_trunc('day', occurred_at, 'America/New_York') FROM events;
```

- `AT TIME ZONE`의 방향이 헷갈리기 쉽다:
  - `timestamptz AT TIME ZONE 'X'` → **`timestamp`** (해당 존의 벽시계 시각)
  - `timestamp AT TIME ZONE 'X'` → **`timestamptz`** (그 벽시계 시각을 X존으로 해석)
- 위 집계식은 인덱스를 못 탄다 → **함수 기반 인덱스** 또는 **generated column** + 인덱스, 혹은 `local_business_date` 컬럼 비정규화.
- `now()` / `current_timestamp`는 `timestamptz`, `localtimestamp`는 `timestamp` → **`now()`만 사용.**

### 3.2 Oracle

| 타입 | 동작 | 평가 |
|---|---|---|
| `DATE` | 타임존 개념 없음, 초 단위 | ❌ 신규 사용 지양 |
| `TIMESTAMP` | 타임존 개념 없음 | △ UTC 규약을 코드로 강제할 때만 |
| `TIMESTAMP WITH TIME ZONE` (TSTZ) | 오프셋 또는 **존 이름**을 함께 저장 | ✅ 권장 (제약 확인 필요) |
| `TIMESTAMP WITH LOCAL TIME ZONE` (TSLTZ) | DB 타임존으로 정규화 저장, **세션 타임존으로 반환** | ⚠️ 함정 |

**TSLTZ가 함정인 이유**: 편해 보이지만 **읽는 쪽 세션 설정에 결과가 의존**한다. 배치 서버, 리포트 툴, DBA의 SQL*Plus가 각각 다른 세션 TZ를 가지면 같은 데이터가 다르게 보인다. 원소스 아키텍처와 상성이 나쁘다.

**TSTZ 제약사항 (반드시 사전 검증)**

- **파티셔닝 키로 사용할 수 없다.** → 시계열 파티셔닝을 쓰는 테이블이면 설계 변경 필요.
- 인덱스/제약조건에 대한 버전별 제약이 있으므로 실제 버전에서 PoC 필수.
- ⇒ 현실적 타협안: **`TIMESTAMP(6)` 컬럼에 UTC만 저장 + `zone_id VARCHAR2(64)` 별도 컬럼**. 파티셔닝·인덱스 자유롭고, "UTC만 넣는다"는 규약을 코드/리뷰로 강제.

**함수 혼용 금지 — 이게 실무에서 가장 많이 터진다**

| 함수 | 기준 | 반환 |
|---|---|---|
| `SYSDATE` | **DB 서버 OS 타임존** | `DATE` |
| `SYSTIMESTAMP` | DB 서버 OS 타임존 | `TIMESTAMP WITH TIME ZONE` |
| `CURRENT_DATE` | **세션 타임존** | `DATE` |
| `CURRENT_TIMESTAMP` | 세션 타임존 | `TIMESTAMP WITH TIME ZONE` |
| `SYSTIMESTAMP AT TIME ZONE 'UTC'` | 항상 UTC | ✅ |
| `SYS_EXTRACT_UTC(SYSTIMESTAMP)` | 항상 UTC | ✅ |

→ **`SYSDATE` 전수 조사 후 제거 대상.**

**DBMS_DST (Oracle 타임존 파일 패치)**

- Oracle은 자체 타임존 파일(`timezlrg_<n>.dat`)을 가지며 IANA와 **버전이 따로 논다.**
- 확인: `SELECT * FROM v$timezone_file;` / `SELECT version FROM v$timezone_file;`
- TSTZ·TSLTZ 컬럼이 존재하는 DB만 업그레이드 영향 → `DBMS_DST.BEGIN_PREPARE` → `FIND_AFFECTED_TABLES` → `BEGIN_UPGRADE` → `UPGRADE_DATABASE` 절차.
- ⚠️ **DST 규칙 변경(예: Sunshine Protection Act 통과) 시 DB도 패치 대상**이라는 걸 운영 계획에 반드시 포함.

### 3.3 JDBC / 커넥션 풀

```yaml
# HikariCP - Oracle
spring.datasource.hikari.connection-init-sql: "ALTER SESSION SET TIME_ZONE='UTC'"
spring.datasource.hikari.data-source-properties:
  oracle.jdbc.timezoneAsRegion: false   # ORA-01882 회피 (지역명 대신 오프셋 사용)

# HikariCP - PostgreSQL
spring.datasource.hikari.connection-init-sql: "SET TIME ZONE 'UTC'"
```

- **`ORA-01882: timezone region not found`** — JVM 기본 TZ의 지역명을 Oracle이 모를 때 발생. `oracle.jdbc.timezoneAsRegion=false` 또는 JVM TZ를 UTC로 고정하면 해소.
- JVM: `-Duser.timezone=UTC` + 컨테이너 환경변수 `TZ=UTC` (둘 다 설정, JVM 옵션이 우선).
- ⚠️ **`TimeZone.setDefault()`를 코드에서 호출하는 방식은 지양.** 호출 시점(클래스 로딩 순서)에 따라 이미 캐시된 값이 남아 비결정적으로 동작한다. 부득이하면 `main()` 최상단 1회.

### 3.4 MyBatis

- MyBatis 3.4.5+ 는 JSR-310 타입 핸들러를 기본 등록: `InstantTypeHandler`, `OffsetDateTimeTypeHandler`, `LocalDateTimeTypeHandler` 등.
- **`LocalDateTimeTypeHandler`는 존 정보가 없어 드라이버/JVM TZ에 암묵 의존** → 가능하면 `Instant` 또는 `OffsetDateTime` 사용.
- 명시적 매핑 권장:

```xml
<result column="OCCURRED_AT" property="occurredAt"
        javaType="java.time.OffsetDateTime" jdbcType="TIMESTAMP_WITH_TIMEZONE"/>
```

- **SQL 내 날짜 리터럴/포맷 함수 전수 조사**: `TO_CHAR(dt,'YYYYMMDD')`, `TRUNC(SYSDATE)`, `to_char(now(),'YYYY-MM-DD')` 등은 전부 암묵적 타임존 의존 코드다.
- 기존 컨벤션(`now()`를 SQL에서 호출)은 **다중 타임존 환경에서는 재검토 필요.** "언제"를 애플리케이션 Clock에서 주입하면 테스트 가능성과 일관성이 올라간다. 단, ShedLock lock 시각처럼 서버 간 시계 오차가 문제되는 곳은 DB 시간이 낫다 (5.3절).

---

## 4. 애플리케이션 레이어 (Java 11 / Spring Boot 2.3.x)

### 4.1 타입 선택 기준

| 타입 | 의미 | 쓰는 곳 |
|---|---|---|
| `Instant` | 절대 시각 (타임존 없음) | **기본값.** 로그, 감사, 트랜잭션 시각, 이벤트 발생 시각 |
| `OffsetDateTime` | 절대 시각 + 오프셋 | DB/JDBC 경계, API 응답 직렬화 |
| `ZonedDateTime` | 절대 시각 + 존 규칙 | 로컬 시각 계산이 필요한 순간에만 (표시, 스케줄 산출) |
| `LocalDateTime` | 존 없는 벽시계 시각 | **사용자 입력 원본**, 미래 예약의 "의도된 시각" |
| `LocalDate` | 달력 날짜 | business date, 청구일, 정산일 |
| `ZoneId` | 존 규칙 | 엔티티 속성으로 저장 |
| `Duration` | 절대 시간 간격 | TTL, 타임아웃, SLA |
| `Period` | 달력 간격 | "한 달 후", "다음 날" |

### 4.2 금지 API 목록 (정적 분석으로 차단)

```
java.util.Date               → Instant
java.util.Calendar           → ZonedDateTime
java.text.SimpleDateFormat   → DateTimeFormatter (동시성 버그도 함께 해결)
java.sql.Timestamp           → Instant / OffsetDateTime
System.currentTimeMillis()   → clock.instant()
LocalDateTime.now()          → Instant.now(clock)  ※ 인자 없는 now() 전부
LocalDate.now()              → LocalDate.now(clock) 또는 zone 명시
ZoneId.systemDefault()       → 명시적 ZoneId 주입
TimeZone.getDefault()        → 동일
new Date()                   → clock.instant()
```

- **SonarQube 커스텀 룰 / ArchUnit 테스트 / Checkstyle `IllegalImport`** 로 CI에서 차단할 것. (SonarQube를 이미 쓰고 있으므로 Quality Gate에 편입이 가장 저렴)
- ArchUnit 예시:

```java
@ArchTest
static final ArchRule 인자없는_now_금지 =
    noClasses().should().callMethod(LocalDateTime.class, "now")
        .because("Clock 주입을 통해 시각을 획득해야 한다");
```

### 4.3 `Clock` 주입 — 테스트 가능성의 핵심

```java
@Configuration
public class TimeConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

@Service
@RequiredArgsConstructor
public class BillingService {
    private final Clock clock;

    public void charge() {
        Instant now = Instant.now(clock);   // ← 테스트에서 고정 가능
    }
}
```

테스트에서:

```java
Clock fixed = Clock.fixed(
    Instant.parse("2026-03-08T06:59:00Z"),   // DST 전환 1분 전 (ET 01:59)
    ZoneOffset.UTC);
```

### 4.4 Jackson / 직렬화 규약

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false      # ISO-8601 문자열로
    deserialization:
      adjust-dates-to-context-time-zone: false  # ⚠️ 기본 true — 입력 오프셋을 멋대로 변환함
    time-zone: UTC
    date-format: yyyy-MM-dd'T'HH:mm:ss.SSSXXX
```

- `ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 기본값이 `true`라서, 클라이언트가 보낸 `-05:00` 오프셋이 조용히 사라지고 컨텍스트 TZ로 바뀐다. **명시적으로 끄는 것을 권장.**
- 응답 포맷은 **RFC 3339 / ISO-8601 with offset** 고정: `2026-03-08T07:30:00Z`
- epoch millis를 쓸 거면 **필드명에 단위를 박을 것**: `createdAtEpochMs`

### 4.5 로깅

```xml
<!-- logback: 로그 타임스탬프는 항상 UTC + 오프셋 표기 -->
<pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX, UTC} [%thread] %-5level %X{traceId} %logger{36} - %msg%n</pattern>
```

- **로그는 예외 없이 UTC 고정.** 다중 리전 로그를 Kibana에서 상관분석(MDC traceId)할 때 존이 섞이면 순서가 뒤집혀 보인다.
- 사용자에게 보이는 메시지 안의 시각은 **표시용 변환 + 존 약어 병기**: `2026-03-08 02:30 EDT (UTC-4)`
- 로그 파일 롤링 정책(`%d{yyyy-MM-dd}`)도 UTC 기준으로 고정할지 결정 필요.

---

## 5. 스케줄러 / 배치 — 실무상 가장 위험한 영역

### 5.1 Spring `@Scheduled`의 DST 동작

```java
@Scheduled(cron = "0 30 2 * * *", zone = "America/New_York")
```

- `zone` 속성으로 존 지정은 가능하지만, **DST 경계에서의 동작(gap 스킵 / overlap 중복 실행)은 Spring 버전의 cron 구현에 의존**한다. Boot 2.3은 `CronSequenceGenerator`, 5.3+ 는 `CronExpression`으로 구현이 교체되었다.
- ⇒ **문서를 믿지 말고 실제 버전에서 검증**할 것. 검증 방법:

```java
// 실제 사용 중인 Trigger 구현으로 다음 실행 시각을 연속 산출해 본다
CronTrigger trigger = new CronTrigger("0 30 2 * * *", TimeZone.getTimeZone("America/New_York"));
// 2026-03-07 ~ 2026-03-09, 2026-10-31 ~ 2026-11-02 구간의 nextExecutionTime을 뽑아
// 실행 횟수(0회/1회/2회)를 눈으로 확인
```

- Quartz를 쓴다면 `CronTrigger`의 DST 동작이 문서화되어 있으니 그쪽이 예측 가능성은 높다.

### 5.2 권장 설계 — Fan-out 패턴 (다중 타임존 배치의 표준 해법)

**핵심 아이디어: 스케줄러 자체는 UTC로 단순하게 돌리고, "지금이 그 존의 목표 시각인가"를 서비스가 판단한다.**

```java
// 스케줄러: UTC 기준 매시 정각 (DST와 무관)
@Scheduled(cron = "0 0 * * * *", zone = "UTC")
@SchedulerLock(name = "dailySettlement", lockAtMostFor = "50m", lockAtLeastFor = "1m")
public void trigger() {
    dailySettlementService.runForDueZones(Instant.now(clock));
}

// 서비스: 대상 존들 중 로컬 시각이 02:00인 것만 골라 처리
public void runForDueZones(Instant now) {
    for (ZoneId zone : storeRepository.findDistinctZones()) {
        ZonedDateTime local = now.atZone(zone);
        if (local.getHour() != 2) continue;

        LocalDate businessDate = local.toLocalDate().minusDays(1);
        // 멱등성: (jobName, zone, businessDate) 유니크 제약으로 중복 실행 차단
        settlementService.settle(zone, businessDate);
    }
}
```

**이 패턴의 장점**

| 문제 | 해결 방식 |
|---|---|
| 존마다 다른 실행 시각 | 스케줄러 하나로 전 존 커버 |
| DST gap (02:00이 없는 날) | `getHour() != 2`가 계속 false → **자동 스킵**. 정책이 필요하면 "그 날은 03시 대체" 분기 추가 |
| DST overlap (01:00이 두 번) | 시간당 1회씩 총 2회 진입 → **멱등성 키로 2회차 차단** |
| 서버가 어디에 배포되든 | 서버 TZ 무관 |
| 재실행/백필 | `businessDate`가 파라미터라 그대로 재호출 가능 |

> 이미 확립한 원칙인 **"스케줄러(언제) / 서비스(무엇) 분리"** 와 정확히 같은 방향이다. `@Scheduled` 메서드는 트리거만 하고, 존 판별과 실제 처리는 서비스가 담당한다.

### 5.3 ShedLock

- `lockAtMostFor` / `lockAtLeastFor`는 **`Duration`(절대시간)** 이므로 DST 영향 없음. ✅
- ⚠️ 확인 사항:
  1. `shedlock` 테이블의 `lock_until`, `locked_at` 컬럼 타입 — **PG면 `timestamptz`, Oracle이면 UTC 규약 확정.** `timestamp`(w/o tz)면 인스턴스 TZ가 다를 때 락이 깨진다.
  2. **`usingDbTime()` 사용 권장** — 각 인스턴스의 JVM 시계 대신 DB 시계를 기준으로 삼아 서버 간 클럭 스큐 문제를 제거한다.

```java
@Bean
public LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()          // ← DB 시계 기준
            .build());
}
```

  3. Redis LockProvider를 쓰는 경우 TTL은 절대시간이라 안전. 단 만료 시각을 "로컬 자정까지"로 계산하는 코드가 있으면 재검토.

### 5.4 OS crontab

- Vixie cron 계열은 DST 전환 시 3시간 이내 잡에 대해 자체 보정 로직을 갖는다(gap 시 즉시 실행, overlap 시 1회만). **애플리케이션 스케줄러와 동작이 다르다.**
- Ansible/Semaphore로 배포되는 크론 스크립트가 있다면 **`TZ=UTC`를 crontab 상단에 명시.**

### 5.5 기존 PM 상태 동기화 / 메트릭 수집 스케줄러 점검 포인트

- 6개 컬렉터의 cron이 KST 벽시계 기준으로 설계되어 있다면, **UTC 전환 시 전부 9시간 밀린다.** 컷오버 시점에 cron 표현식 일괄 재계산 필요.
- Prometheus/ES 조회 시 쓰는 시간 범위 파라미터(`start`, `end`, `step`)가 로컬 시각 문자열로 만들어지고 있는지 확인.
- 외부 Oracle 4개(EmsDb, EmsIc, MmsCj, MmsIc)는 **각각 다른 DB 타임존일 수 있다.** 데이터소스별 세션 TZ 고정 + 읽어온 시각의 존 해석 규약을 데이터소스 단위로 명문화할 것. (`@Qualifier` 누락으로 데이터소스가 조용히 폴백됐던 이슈와 동일한 계열의 위험 — **조용히 틀린 값이 들어온다.**)

---

## 6. 도메인 / 데이터 모델 설계

### 6.1 "시점"과 "날짜"를 분리 저장

```sql
CREATE TABLE orders (
    id             BIGINT PRIMARY KEY,
    store_id       BIGINT NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,   -- 절대 시각 (UTC)
    store_zone_id  VARCHAR(64) NOT NULL,   -- 'America/Indiana/Vincennes'
    business_date  DATE NOT NULL           -- 로컬 기준 영업일 (파생, 비정규화)
);
CREATE INDEX idx_orders_bizdate ON orders (store_id, business_date);
```

- `occurred_at`: 정렬·중복제거·시계열 분석용
- `business_date`: 리포트·정산용. **"현업이 보는 날짜"와 DB 집계가 일치하려면 이 컬럼이 반드시 필요하다.** 매 조회마다 `AT TIME ZONE` 변환을 하면 인덱스도 못 타고 존 규칙 변경 시 과거 리포트 수치가 흔들린다.
- 이 두 값이 **서로 어긋나지 않도록 쓰기 시점에 한 곳에서만 계산**(도메인 팩토리/서비스)한다.

### 6.2 미래 이벤트 — 원본은 로컬 시각

```sql
CREATE TABLE reservations (
    id                 BIGINT PRIMARY KEY,
    scheduled_local    TIMESTAMP NOT NULL,    -- 사용자가 의도한 벽시계 시각 (원본)
    zone_id            VARCHAR(64) NOT NULL,  -- 의도한 존 (원본)
    scheduled_utc      TIMESTAMPTZ NOT NULL,  -- 파생 캐시 (실행용)
    tzdb_version       VARCHAR(16) NOT NULL   -- 계산 당시 tzdb 버전 (예: '2026c')
);
```

- **원본은 `(scheduled_local, zone_id)`**, `scheduled_utc`는 캐시.
- tzdb 갱신 시 `tzdb_version`이 다른 미래 레코드를 재계산하는 배치를 마련.
- **Sunshine Protection Act가 통과되면 이 배치가 실제로 필요해진다.** (1.4절)
- gap/overlap에 걸리는 값은 등록 시점에 검증 → 사용자에게 재확인 요구 또는 정책대로 보정 후 보정 사실을 기록.

### 6.3 반복 일정 (RRULE)

- 반복은 **로컬 시각 기준으로 전개**한다. "매주 화요일 09:00"은 DST를 넘어가면 UTC 시각이 바뀐다(14:00Z ↔ 13:00Z).
- UTC로 전개해 저장하면 DST 이후 사용자 눈에 1시간 틀린 시각이 보인다.

### 6.4 영향받는 도메인 개념 체크리스트

- 영업시간 / 휴무일 / 예약 가능 시간대
- 구독 만료, 쿠폰·프로모션 유효기간, 무료체험 종료
- 청구 주기, 정산 컷오프, 마감 시각
- SLA·응답시간 측정 (→ 절대시간, `Duration`)
- 데이터 보존기간(retention) / 다운샘플링 경계
- 알림 발송 시간대 (야간 발송 금지 규정 — **수신자 로컬 기준**)
- 감사 로그, 로그인 이력, 보안 이벤트
- 통계 리포트의 "오늘/어제/이번 달"

---

## 7. API / 경계(Edge) 계약

### 7.1 표현 규약

| 항목 | 규약 |
|---|---|
| 시점(instant) | RFC 3339: `2026-03-08T07:30:00Z` 또는 `2026-03-08T02:30:00-05:00` |
| 날짜 | `2026-03-08` (LocalDate) — **어느 존 기준 날짜인지 스펙에 명시** |
| 기간 필터 | 반열림 구간 `[from, to)` 로 고정 (경계 중복/누락 방지) |
| 존 전달 | `X-Client-Timezone: America/Indiana/Vincennes` 헤더 또는 사용자 프로필 |
| 존 우선순위 | 사용자 명시 설정 > 리소스(매장) 존 > 클라이언트 감지 존 > UTC |

### 7.2 프론트엔드 (React + Ant Design)

- **Ant Design v5는 dayjs, v4는 moment** 기반. `DatePicker`가 반환하는 값의 존 처리를 명확히 할 것.
  - dayjs 사용 시 `dayjs/plugin/utc` + `dayjs/plugin/timezone` 플러그인 필요 (미설치 시 `.tz()` 호출이 런타임 에러)
  - pnpm 환경에서는 **phantom dependency로 우연히 동작하던 게 깨진다** → 명시적 `pnpm add dayjs`
- 브라우저 존 감지: `Intl.DateTimeFormat().resolvedOptions().timeZone`
  - VPN·여행·회사 노트북 설정 등으로 **틀릴 수 있다.** 감지값은 기본값일 뿐, 사용자 설정이 우선.
- 표시 포맷: `Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'short' })` → `EDT`/`EST` 자동 처리
- API 요청 시 `Date` 객체를 `.toISOString()`(항상 Z)으로 보내는지, 로컬 문자열로 보내는지 통일.
- ⚠️ `json-bigint`로 BigInt 정밀도를 다루고 있으므로, epoch millis를 BigInt로 다루는 경로가 있다면 함께 점검.

### 7.3 외부 연동 / 파이프라인

- 연동 지점마다 **anti-corruption layer**를 두고 변환을 한 곳에 모은다. 절대 도메인 안쪽으로 상대 시스템의 시각 규약이 새어 들어오지 않게 한다.
- 상대 시스템별로 문서화할 항목: 포맷, 존 규약, DST 처리, epoch 단위(초/밀리초), 날짜 경계 정의
- **Kafka**: 메시지 페이로드는 ISO-8601 with offset 또는 epoch millis(UTC)로 통일. Avro `timestamp-millis` 논리 타입은 **UTC epoch 기준**임을 스키마 레벨에서 못 박을 것. 사내 SDK의 직렬화 규약에 반영.
- **CSV/Excel export**: Excel은 존 개념이 없다. 헤더 또는 파일명에 기준 존 표기 (`sales_20260308_ET.xlsx`), 셀 값은 문자열 또는 로컬 벽시계 + 별도 존 컬럼.

---

## 8. 인프라 / 운영

### 8.1 tzdata 3계층 문제 ⚠️

**JDK / OS / DB가 각각 별도의 tzdata를 갖는다. 버전이 어긋나면 특정 존·특정 날짜에서만 1시간 틀린다.**

| 계층 | 위치 | 확인 방법 | 갱신 방법 |
|---|---|---|---|
| JDK | `$JAVA_HOME/lib/tzdb.dat` | `java -XshowSettings:properties -version` 로그, 또는 `ZoneRulesProvider.getVersions("UTC")` | **JDK 패치 버전 업그레이드** (Temurin 최신 적용) |
| OS | `/usr/share/zoneinfo` | `rpm -q tzdata` / `dpkg -l tzdata` | 패키지 업데이트 |
| Oracle | `timezlrg_<n>.dat` | `SELECT version FROM v$timezone_file` | `DBMS_DST` 업그레이드 |
| PostgreSQL | 번들 또는 시스템 | `SELECT * FROM pg_timezone_names` | 마이너 버전 업그레이드 |

- **Java는 OS tzdata가 아니라 JDK 내장 `tzdb.dat`을 쓴다.** 그래서 OS만 패치하면 Java 동작은 안 바뀐다. Java 11 사용 중이므로 **Temurin 패치 릴리스 적용 주기를 정책화**할 것.
- 컨테이너(Podman): `TZ=UTC` 환경변수 설정. Alpine/distroless 베이스는 OS `tzdata` 패키지가 없어 **`/etc/localtime` 기반 존 해석이 실패**할 수 있다 (JDK 내장 tzdb를 쓰는 Java 코드는 대체로 동작하지만, `psql`·`date` 등 OS 도구는 실패).
- **모니터링 항목으로 추가**: 각 노드의 tzdata 버전을 수집해 불일치를 알람. `node_exporter` textfile collector로 노출 가능.

### 8.2 관측 스택

| 도구 | 포인트 |
|---|---|
| **Prometheus** | 저장은 항상 Unix timestamp(UTC). 문제 없음. 단 recording rule의 시간 윈도우는 절대시간 |
| **Grafana** | 대시보드 타임존 설정(`Browser` / `UTC` / 특정 존). **팀 표준을 UTC로 고정할지, 지역팀은 로컬로 볼지 결정 필요** |
| **Alertmanager** | `time_intervals`(뮤트 시간대)에 `location` 필드로 IANA 존 지정 가능. 야간 알람 억제를 어느 존 기준으로 할지 결정 |
| **Elasticsearch** | `date` 필드는 UTC 저장. `date_histogram`의 **`time_zone` 파라미터**로 로컬 기준 버킷 생성 가능. DST 전환일에는 버킷 크기가 23/25시간으로 달라짐을 인지 |
| **Kibana** | `dateFormat:tz` Advanced Setting. 기본은 브라우저 존 → **UTC 고정 권장** (로그 상관분석 목적) |
| **Redis** | TTL은 절대시간이라 안전 |

### 8.3 운영 프로세스에 추가할 것

- [ ] tz-announce 메일링 리스트 구독 → tzdb 릴리스 시 영향도 평가
- [ ] JDK/OS/DB tzdata 버전 인벤토리 + 정기 점검 (분기 1회)
- [ ] DST 전환일(연 2회) 사전 점검 체크리스트 + 당일 모니터링 강화
- [ ] Sunshine Protection Act 상원 진행 상황 트래킹 (통과 시 tzdb·DB·미래 데이터 재계산 프로젝트 필요)

---

## 9. 마이그레이션 전략 (KST 암묵 → UTC 명시)

### 9.1 단계

```
1) 인벤토리 및 영향도 분석
2) 규약 확정 + 정적 분석 룰 도입 (신규 코드부터 차단)
3) 경계 변환 계층 도입 (내부는 아직 KST여도 무방)
4) 저장 타입 전환 — 듀얼 컬럼 + 듀얼 라이트
5) 기존 데이터 백필 및 검증
6) 읽기 전환 → 컷오버 → 구 컬럼 제거
```

### 9.2 인벤토리 대상 (전수 조사)

**DB**
```sql
-- PostgreSQL: 타임존 없는 시각 컬럼 찾기
SELECT table_schema, table_name, column_name, data_type
  FROM information_schema.columns
 WHERE data_type IN ('timestamp without time zone', 'time without time zone')
 ORDER BY 1,2;

-- Oracle: DATE / TIMESTAMP 컬럼 + 문자열 날짜 의심 컬럼
SELECT owner, table_name, column_name, data_type, data_length
  FROM all_tab_columns
 WHERE owner = :schema
   AND (data_type IN ('DATE','TIMESTAMP(6)')
        OR (data_type LIKE 'VARCHAR2%' 
            AND (column_name LIKE '%DT%' OR column_name LIKE '%DATE%' 
                 OR column_name LIKE '%_TM%' OR column_name LIKE '%TIME%')))
 ORDER BY 1,2;
```

**코드**
```bash
# 금지 API 사용처
grep -rnE "new Date\(|SimpleDateFormat|Calendar\.getInstance|System\.currentTimeMillis|\.now\(\)" --include=*.java

# SQL 내 시각 함수
grep -rniE "sysdate|current_date|localtimestamp|to_char\(.*(YYYY|MM|DD)" --include=*.xml
```

### 9.3 🔴 최대 난관 — 문자열 날짜 컬럼

한국 SI 환경에서 매우 흔한 `VARCHAR2(14)` + `'YYYYMMDDHH24MISS'` 패턴.

- **존 정보가 완전히 소실**되어 있고, 정렬·비교가 문자열로 이뤄지며, 애플리케이션 곳곳에서 파싱된다.
- 미국 확장 시 **이 컬럼들은 반드시 제거하거나 존 규약을 명문화**해야 한다.
- 현실적 접근: (a) 신규 테이블은 금지, (b) 기존은 `timestamptz` 컬럼을 추가하고 듀얼 라이트, (c) 읽기 전환 후 문자열 컬럼 deprecated.

### 9.4 기존 KST 데이터 변환

```sql
-- ⚠️ 단순 -9시간이 아니라 Asia/Seoul 규칙으로 변환할 것
UPDATE events
   SET occurred_at_utc = occurred_at_local AT TIME ZONE 'Asia/Seoul';
```

- **한국은 1987~1988년에 서머타임을 시행했다** (서울올림픽 대비). 그 시기 데이터가 존재한다면 단순 `-9h` 산술은 1시간 틀린다. 더 거슬러 올라가면 UTC+8:30 시기(1954~1961)도 있다.
- ⇒ **오프셋 산술 금지, 반드시 tzdb 기반 변환.** (인디애나 과거 데이터와 동일한 이유)

### 9.5 검증

- 변환 전후 건수·합계 대조 쿼리
- 샘플 레코드의 왕복 변환 일치 검증 (`UTC → KST → 원본`)
- **경계값 집중 검증**: 자정 직전/직후 레코드, 월말/월초, 연말/연초
- 리포트 결과 대조 (기존 리포트 vs 신규 리포트의 일별 합계)
- 롤백 플랜: 구 컬럼을 일정 기간 유지, 읽기 소스만 플래그로 전환

---

## 10. 테스트 설계

### 10.1 결정 테이블 (`@ParameterizedTest` + `@MethodSource`)

| # | Zone | 입력 로컬 시각 | 기대 결과 |
|---|---|---|---|
| 1 | `America/New_York` | 2026-03-08 02:30 | 존재하지 않음 (`getValidOffsets()` 빈 리스트) |
| 2 | `America/New_York` | 2026-03-08 01:59 | `-05:00` (EST) |
| 3 | `America/New_York` | 2026-03-08 03:00 | `-04:00` (EDT) |
| 4 | `America/New_York` | 2026-11-01 01:30 | 오프셋 2개 (`-04:00`, `-05:00`) |
| 5 | `America/Phoenix` | 2026-03-08 02:30 | 정상, `-07:00` (DST 없음) |
| 6 | `America/Indiana/Indianapolis` | 2026-03-08 02:30 | 존재하지 않음 (ET 규칙) |
| 7 | `America/Indiana/Knox` | 2026-03-08 02:30 | 존재하지 않음 (CT 규칙, **UTC 시각은 #6과 1시간 차이**) |
| 8 | `America/Indiana/Indianapolis` | 2005-04-03 02:30 | **정상** (당시 DST 미적용) |
| 9 | `Pacific/Honolulu` | 임의 | 항상 `-10:00` |
| 10 | `Asia/Seoul` | 1988-05-08 02:30 | 존재하지 않음 (한국 서머타임) |

```java
static Stream<Arguments> dstBoundaryCases() {
    return Stream.of(
        Arguments.of("America/New_York", LocalDateTime.of(2026,3,8,2,30), 0),
        Arguments.of("America/New_York", LocalDateTime.of(2026,11,1,1,30), 2),
        Arguments.of("America/Phoenix",  LocalDateTime.of(2026,3,8,2,30), 1),
        Arguments.of("America/Indiana/Indianapolis", LocalDateTime.of(2005,4,3,2,30), 1)
    );
}

@ParameterizedTest(name = "[{index}] {0} {1} → 유효 오프셋 {2}개")
@MethodSource("dstBoundaryCases")
@DisplayName("DST 경계에서 유효 오프셋 개수를 판별한다")
void DST_경계_오프셋_검증(String zoneId, LocalDateTime local, int expected) {
    // given
    ZoneId zone = ZoneId.of(zoneId);
    // when
    var offsets = zone.getRules().getValidOffsets(local);
    // then
    assertThat(offsets).hasSize(expected);
}
```

### 10.2 통합 테스트

- **JVM TZ를 일부러 비-UTC로 설정하고 돌리는 CI 잡을 하나 추가.** (`-Duser.timezone=America/New_York`, `Pacific/Kiritimati`(+14) 등) 코드에 남은 `systemDefault()` 의존을 강제로 노출시킨다.
- Testcontainers로 **DB 컨테이너 TZ를 다르게** 띄워 JDBC 왕복 검증.
- 배치는 `Clock.fixed`로 DST 경계 시각을 주입해 실행 횟수 검증 (Awaitility 기반 기존 통합 테스트에 케이스 추가).
- H2 + `InMemoryLockProvider` 테스트 프로파일에서도 `timestamptz` 상당의 동작을 확인 — H2와 실 DB의 시각 처리 차이 주의.

---

## 11. 실행 체크리스트

### 설계 · 규약
- [ ] 저장/연산 UTC, 변환 경계 원칙 문서화 및 팀 합의
- [ ] `Instant` / `LocalDateTime+ZoneId` 사용 기준 명문화
- [ ] API 시각 표현 규약(RFC 3339) 확정
- [ ] Zone ID를 보유할 엔티티 식별 (매장/지점/사용자/테넌트)
- [ ] business date 개념 도입 여부 및 정의 확정

### 코드
- [ ] 금지 API 목록 확정 + SonarQube/ArchUnit 룰 적용
- [ ] `Clock` 빈 도입 및 전 서비스 주입 전환
- [ ] Jackson 직렬화 설정 통일
- [ ] 로그 패턴 UTC 고정
- [ ] `SimpleDateFormat` → `DateTimeFormatter` 전환

### DB
- [ ] 시각 컬럼 전수 인벤토리 작성
- [ ] PG: `timestamp` → `timestamptz` 전환 계획
- [ ] Oracle: TSTZ vs (TIMESTAMP + zone 컬럼) 방식 결정 + 파티셔닝 제약 PoC
- [ ] `SYSDATE` 사용처 전수 제거
- [ ] 문자열 날짜 컬럼 처리 방안 결정
- [ ] MyBatis 타입 핸들러 명시화

### 스케줄러
- [ ] 전 배치의 cron 표현식 재계산 (KST → UTC)
- [ ] Fan-out 패턴 적용 대상 배치 식별
- [ ] 배치 멱등성 키 설계 (`jobName + zone + businessDate`)
- [ ] ShedLock 테이블 컬럼 타입 확인 + `usingDbTime()` 적용
- [ ] DST 경계일 배치 실행 시뮬레이션

### 인프라
- [ ] 서버/컨테이너 `TZ=UTC`, JVM `-Duser.timezone=UTC`
- [ ] JDK/OS/DB tzdata 버전 인벤토리 + 갱신 정책
- [ ] Grafana/Kibana 표시 타임존 정책 결정
- [ ] Alertmanager 뮤트 시간대 존 설정
- [ ] tz-announce 구독

### 검증
- [ ] DST 경계 파라미터라이즈드 테스트 작성
- [ ] 비-UTC JVM TZ CI 잡 추가
- [ ] 데이터 변환 전후 대조 검증 쿼리
- [ ] 롤백 플랜 수립

---

## 12. 의사결정이 필요한 항목 (기획/현업 확인 대상)

1. **"오늘"의 정의** — 사용자 로컬 기준인가, 본사(KST) 기준인가, 매장 기준인가? 리포트마다 다를 수 있다.
2. **DST gap에 걸린 예약**의 처리 정책 (스킵 / 밀기 / 당기기 / 거부)
3. **DST overlap 배치**의 실행 횟수 (1회 / 2회)
4. **일 마감·정산 컷오프**의 기준 존 — 매장별 로컬인가 단일 기준인가
5. **알림 발송 시간대 규제** — 수신자 로컬 기준 야간 발송 금지 시간
6. **관리자 화면 표시 존** — 본사 담당자는 KST로 볼지, 대상 매장 로컬로 볼지, 토글 제공할지
7. **기존 한국 서비스도 함께 UTC로 전환할지, 미국만 신규 규약을 적용할지** — 원소스 아키텍처를 지향한다면 전자가 정합적이지만, 기존 서비스 리스크가 크다
8. **과거 데이터 소급 변환 범위** — 전체 / 최근 N년 / 미변환

---

## 부록 A. 빠른 참조 코드

```java
// UTC 현재 시각
Instant now = Instant.now(clock);

// UTC → 로컬 표시
String display = now.atZone(ZoneId.of("America/New_York"))
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz", Locale.US));
// → "2026-03-08 03:30 EDT"

// 로컬 입력 → UTC (gap/overlap 검증 포함)
ZoneId zone = ZoneId.of("America/New_York");
LocalDateTime input = LocalDateTime.of(2026, 3, 8, 2, 30);
var offsets = zone.getRules().getValidOffsets(input);
if (offsets.isEmpty()) {
    throw new NonExistentLocalTimeException(input, zone);   // gap
} else if (offsets.size() > 1) {
    throw new AmbiguousLocalTimeException(input, zone);     // overlap
}
Instant utc = input.atZone(zone).toInstant();

// 로컬 기준 영업일
LocalDate businessDate = now.atZone(zone).toLocalDate();

// 로컬 자정 (DST 안전)
ZonedDateTime midnight = businessDate.atStartOfDay(zone);   // ⚠️ atTime(0,0) 아님
```

> `LocalDate.atStartOfDay(zone)`을 쓸 것. 일부 존/일자는 자정 자체가 존재하지 않는 경우가 있어 `atTime(0,0)` 후 존 부여보다 안전하다.

## 부록 B. 참고 링크

- IANA tz-announce (릴리스 공지): <https://lists.iana.org/hyperkitty/list/tz-announce@iana.org/latest>
- tzdb 소스: <https://github.com/eggert/tz>
- H.R.139 하원 통과 보도자료: <https://energycommerce.house.gov/posts/house-passes-legislation-to-make-daylight-saving-time-permanent>
