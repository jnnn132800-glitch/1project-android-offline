# Phase 07 — Result Revision Approval, Idempotent Winning Entitlement & Reports Drilldown Specification

เอกสารฉบับนี้สรุปรายละเอียดการพัฒนา **Phase 07** ตามข้อกำหนดใน `01_SYSTEM_INSTRUCTIONS.txt`, `03_PHASE_PROMPTS.md` (Phase 07), `04_SPEC_ระบบและพัฒนา.md`, `05_UI_และคู่มือใช้งาน.md`, และ `07_TEST_FIXTURES.json`

---

## 1. กฎโดเมนและสถาปัตยกรรมที่บังคับใช้ใน Phase 07

1. **การคำนวณตัวอย่างผลรางวัลก่อนอนุมัติ (`Preview -> Approve`)**:
   - ห้ามอนุมัติผลรางวัลโดยไม่ผ่านการคำนวณตัวอย่าง (`Preview`) หากผู้ใช้แก้ไขเลขรางวัล 2 ตัวบนหรือ 2 ตัวล่าง ระบบจะล้างผล `Preview` เดิมทันทีเพื่อบังคับให้ตรวจทานยอดใหม่ก่อนกดยืนยัน
   - คำนวณเฉพาะจากบิลที่สถานะ `ACTIVE` และใช้รายการบรรทัดจาก `activeRevisionId` เท่านั้น (บิลที่สถานะ `CANCELLED` และ Revision เก่าที่ถูกแทนที่จะไม่ถูกนำมานับรวมโดยเด็ดขาด)
   - ตรวจสอบความถูกต้องของยอดจ่ายรายบรรทัดด้วย `ExactMoneyMath` (`BigDecimal` + `HALF_UP` รายบรรทัดที่บันทึกครั้งเดียวแล้วรวมด้วย `checkedAdd`)
   - ตรงตามชุดทดสอบมาตรฐาน `CART01` ใน `07_TEST_FIXTURES.json`:
     - รายการ: `31 บน 100.00 (10,000 สตางค์) @ 60`, `31 ล่าง 50.00 (5,000 สตางค์) @ 60`, `27 ล่าง 200.00 (20,000 สตางค์) @ 60`
     - เมื่อผลออก `บน = 31`, `ล่าง = 27` -> สิทธิได้รับรางวัลรวม `top31_bottom27_payout_minor = 1,800,000 สตางค์` (`18,000.00 บาท`)

2. **การอนุมัติผลรางวัลแบบ Atomic & Idempotent (`executeApproveResultRevisionTransaction`)**:
   - ใช้ `operation_key` และ `payload_sha256` ภายใน `@Transaction` ของ Room:
     - หากส่ง `operation_key` เดิมซ้ำด้วย `payload_sha256` เดิม ระบบจะคืนผลลัพธ์เดิม (`replayedFromDurableOperation = true`) ทันทีโดยไม่สร้าง `ResultRevisionEntity` หรือ `WinningEntitlementEntity` ซ้ำ
     - หากส่ง `operation_key` เดิมแต่ `payload_sha256` ต่างกัน ระบบจะปฏิเสธด้วย `PayloadConflict`
     - หากส่ง `operation_key` ใหม่แต่เลขรางวัล `(winningTopTwoDigit, winningBottomTwoDigit)` ตรงกับรุ่น `APPROVED` ปัจจุบันของงวดนั้นทุกประการ ระบบจะคืนรุ่นปัจจุบันแบบ Idempotent (`replayedFromIdenticalActiveResult = true`) โดยไม่สร้างแถวซ้ำ
   - หากเป็นการแก้ไขผลรางวัล (เลขรางวัลเปลี่ยนจากรุ่น `APPROVED` เดิม) ระบบจะเปลี่ยนสถานะรุ่นเดิมเป็น `SUPERSEDED` (เก็บประวัติและ `ApprovalRecordEntity` เดิมไว้ครบถ้วน) แล้วสร้าง `ResultRevisionEntity` รุ่นถัดไปพร้อมคำนวณ `WinningEntitlementEntity` ชุดใหม่ใน Transaction เดียวกัน

3. **การแยกสิทธิได้รับรางวัลออกจากเงินสดจ่ายจริง (`Winning Entitlement != Paid Cash`)**:
   - ทั้งในระดับฐานข้อมูล (`WinningEntitlementEntity` เทียบกับ `PaymentAllocationEntity`), ใน Repository (`RoundReportAndDrilldownSnapshot`, `BillEntitlementDrilldown`), และในหน้าจอ UI (`ReportsAndResultScreen.kt`) แสดงแยกชัดเจนระหว่าง:
     - **สิทธิได้รับรางวัลตามผลที่อนุมัติ (`Winning Entitlement • ยังไม่ถือว่าจ่ายเงินสดจริง`)**
     - **ยอดที่บันทึกจ่ายเงินสดแล้ว (`Allocated Paid Cash`)**
     - **ยอดสิทธิคงค้างรอจ่าย (`Unpaid Entitlement Balance`)**

4. **รายงานและเจาะลึก 3 ระดับ (`ReportsAndResultScreen.kt`)**:
   - **แท็บย่อยที่ 1**: ประกาศผลรางวัล (`Preview -> Approve`) และประวัติรุ่นผลรางวัลทั้งหมด (`APPROVED` / `SUPERSEDED`)
   - **แท็บย่อยที่ 2**: สรุปยอดประจำงวดและเจาะลึกรายบิล/รายบรรทัด (`Bill & Line Entitlement Drilldown`)
   - **แท็บย่อยที่ 3**: สรุปยอดรับและสิทธิรางวัลรายเลข `00–99` (`บน/ล่าง`) พร้อมกดเจาะลึกดูรายการบิลที่มา (`Number-Side Drilldown`)

---

## 2. คำสั่งรันทดสอบใน Android Studio

```bash
./gradlew testDebugUnitTest --tests "com.oneproject.offline.ui.reports.Phase07ResultEntitlementAndReportsTest"
```
