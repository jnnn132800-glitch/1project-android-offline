# ข้อกำหนดและการตรวจสอบ Domain เลข / เงิน / อัตราจ่าย / เพดาน และ `07_TEST_FIXTURES` (Phase 03)

> **สถานะใน Container ปัจจุบัน**: `BUILD_NOT_RUN` เนื่องจากสภาพแวดล้อม Container ไม่มี JDK, Android SDK และ Gradle จึงต้องรัน `./gradlew testDebugUnitTest` ใน Android Studio บนเครื่องพัฒนา

---

## 1. สรุปโมดูล Domain ที่สร้างใน Phase 03 (ก่อนเชื่อม UI ใน Phase 04)
1. **`ExactMoneyMath.kt` (`/app/src/main/java/com/oneproject/offline/core/money/ExactMoneyMath.kt`)**:
   - ใช้ `Long` หน่วยสตางค์ (`1 THB = 100 minor units`) และ `BigDecimal` สำหรับอัตราจ่ายเท่านั้น (ไม่มี `Double` หรือ `Float`)
   - ปัดเศษ `RoundingMode.HALF_UP` รายบรรทัดที่บันทึกเพียงครั้งเดียว (`computeProvisionalLinePayoutMinor`) แล้วจึงรวมยอดด้วย `checkedAdd` (`Math.addExact`)
   - ป้องกันการตัดทศนิยมทิ้งเงียบๆ (`parseThbToMinorUnits` ปฏิเสธทันทีหากทศนิยมเกิน 2 ตำแหน่ง) และตรวจ Overflow ทุกขั้นตอน
2. **`NumberExpansionEngine.kt` (`/app/src/main/java/com/oneproject/offline/domain/engine/NumberExpansionEngine.kt`)**:
   - รูดหน้า `d = d0..d9` (10 เลข), รูดหลัง `d = 0d..9d` (10 เลข), 19 ประตู `d = union unique` (19 เลข)
   - กลับเลข (`expandReverseUnion`): เพิ่มเลขกลับโดยคงเลขเดิมไว้และรวมเป็นชุดไม่ซ้ำเรียงลำดับ (`["31", "11"] -> ["11", "13", "31"]`)
3. **`PolicyAndCapEngine.kt` (`/app/src/main/java/com/oneproject/offline/domain/engine/PolicyAndCapEngine.kt`)**:
   - แยกการสืบทอดค่าตั้งค่ารายช่อง (`resolveEffectiveNumberSidePolicy` ตาม `D07`)
   - แยกตรวจ `Receive Cap` ออกจาก `Payout Cap` (`04_SPEC`: `Receive cap != payout cap`)
   - คำนวณยอดแก้ไขบิลโดยตัดยอด Revision เก่าออกก่อน (`evaluateBillEditCapCheck`: `otherActiveBills + replacement`)
   - คำนวณยอดจ่ายรวมต่อผลรางวัล (`computeGrossPayoutForOutcomeMinor`) และสถานะเงินสด (`evaluateCashScenarioPosition`)
4. **`PasteBatchParserAndQuoteHasher.kt` (`/app/src/main/java/com/oneproject/offline/domain/engine/PasteBatchParserAndQuoteHasher.kt`)**:
   - ตรวจสอบการวางข้อความแบบเข้มงวด หากมีบรรทัดผิดแม้บรรทัดเดียวจะบล็อกทั้งชุด (`BlockedBatch` ตาม `D03`)
   - คำนวณ `computeCanonicalQuotePayloadSha256` ผูก `roundId`, `policyVersion` และทุกรายการบรรทัดเข้าด้วยกัน

---

## 2. ตารางเทียบผลลัพธ์กับ `07_TEST_FIXTURES_abd6d744.json`

| ชุดข้อมูลใน `07_TEST_FIXTURES` | ค่าคาดหวังตาม Fixture | เมธอดที่ตรวจสอบใน `Phase03DomainMathAndFixturesTest` |
|---|---|---|
| **`CART01.total_minor`** | `35000` (`10000 + 5000 + 20000`) | `PolicyAndCapEngine.evaluateAndQuoteOrderLines` -> `35_000L` (`lineCount = 3`, `distinctNumberCount = 2`) |
| **`CART01.top31_bottom27_payout_minor`** | `1800000` (`31 บน: 600000 + 27 ล่าง: 1200000`) | `PolicyAndCapEngine.computeGrossPayoutForOutcomeMinor(..., "31", "27")` -> `1_800_000L` |
| **`selection` (`digit = "1"`)** | `front_count = 10`, `back_count = 10`, `door19_count = 19`, `reverse_union(["31","11"]) = ["11","13","31"]` | `NumberExpansionEngine.expandFront/Back/Door19/ReverseUnion` |
| **`edit`** | `other = 60000`, `old = 20000`, `replacement = 30000`, `limit = 100000` -> `expected_minor = 90000` | `PolicyAndCapEngine.evaluateBillEditCapCheck` -> `90_000L` (`isAllowedWithinCap = true`) |
| **`CASH01`** | `opening = 6000000`, `inflow = 1000000`, `outflow = 9000000` -> `closing = -2000000`, `minimum = 1000000` -> `shortfall = 3000000` | `PolicyAndCapEngine.evaluateCashScenarioPosition` -> `closing = -2_000_000L`, `shortfall = 3_000_000L` |
