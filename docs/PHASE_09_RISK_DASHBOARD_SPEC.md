# ข้อมูลจำเพาะทางเทคนิค Phase 09: แดชบอร์ดและเอนจินคำนวณความเสี่ยงรายผลลัพธ์ (`100 × 100 Valid Outcomes`) สถานการณ์เงินสด และการบล็อกเมื่อข้อมูลล้าสมัย

## 1. สาระสำคัญของ Phase 09
- **การคำนวณ `gross(outcome)` และ `net(outcome)` แยกตามผลลัพธ์ที่เป็นไปได้ (`100 × 100 = 10,000 กรณี` ตาม `D06`)**:
  - คำนวณ `net(outcome) = max(0, gross(outcome) - acceptedRecoverable(outcome))` แยกทีละผลลัพธ์ก่อนหาค่าสูงสุด
  - ห้ามนำค่าสูงสุดคนละเลขมาลบกันข้ามผลลัพธ์ (`Do not subtract unrelated maxima`)
  - รายการตีออกที่อยู่ในสถานะ `PENDING` หรือ `REJECTED` มีผลลดความเสี่ยงเท่ากับ `0` สตางค์ (`pendingExposureReductionMinor == 0L`)
- **การจำลองสถานการณ์เงินสด (`Cash Scenarios` & `CASH01`)**:
  - สถานการณ์มาตรฐาน (`Default Conservative Scenario`) ใช้เฉพาะเงินสดที่เก็บได้จริง (`collectedCashInflowsMinor`) ไม่นับซ้ำลูกหนี้ค่าบิลค้างเก็บ และไม่นับสิทธิรับคืนจากคู่สัญญาเป็นเงินสดพร้อมจ่าย
  - ตรวจสอบตรงตามชุดทดสอบ `CASH01` ใน `07_TEST_FIXTURES.json` (`opening=6000000, inflow=1000000, outflow=9000000 -> closing=-2000000, minimum=1000000 -> shortfall=3000000`)
- **เวลาคำนวณในเครื่อง (`Local As-Of`) และการบล็อกเมื่อข้อมูลล้าสมัย (`Stale Risk Blocking`)**:
  - บันทึกเวลา `computedAsOfEpochMs` และเปรียบเทียบกับเวลาแก้ไขล่าสุดของงวด นโยบาย บิล และใบตีออกคู่สัญญา (`latestDataMutationEpochMs`) หากมีการแก้ไขหลังเวลาคำนวณจะตั้งค่า `isStale = true` และบล็อกการตัดสินใจจนกว่าจะรีเฟรช

## 2. คำสั่งรันทดสอบใน Android Studio
```bash
./gradlew testDebugUnitTest --tests "com.oneproject.offline.ui.risk.Phase09RiskDashboardAndOutcomeEngineTest"
```
