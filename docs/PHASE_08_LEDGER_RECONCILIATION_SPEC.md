# ข้อมูลจำเพาะทางเทคนิค Phase 08: สมุดบัญชีแยกประเภทคู่ การกลับรายการ การจัดสรรยอดรับ/จ่าย และการกระทบยอดเงินสดในเครื่อง

## 1. วัตถุประสงค์และขอบเขตของ Phase 08
Phase 08 พัฒนาระบบบัญชีแยกประเภทคู่ด้วยมือ (Manual Double-Entry Ledger), การกลับรายการแบบผูกอ้างอิง (`Linked Reversal` — ห้ามแก้ไขทับรายการที่โพสต์แล้ว), การบันทึกรับ/จ่ายเงินสดพร้อมจัดสรรยอดไม่เกินเพดานจริง (`Bounded Payment Allocations`), และการกระทบยอดเงินสดในลิ้นชักเทียบกับบัญชี (`Cash & Settlement Reconciliation`) ตามข้อกำหนด `03_PHASE_PROMPTS.md`, `04_SPEC_ระบบและพัฒนา.md`, และ `06_DECISIONS_ต้องอนุมัติ.md (D09)`:
- **สมุดบัญชีคู่สมดุล (Balanced Double-Entry Posting)**:
  - ทุกธุรกรรม (`ledger_transactions`) ต้องมีบรรทัดรายการ (`ledger_entries`) อย่างน้อย 2 บรรทัด
  - ยอดรวมฝั่งเดบิตต้องเท่ากับยอดรวมฝั่งเครดิตทุกสตางค์ (`SUM(DEBIT) == SUM(CREDIT) > 0`) โดยตรวจด้วย `Math.addExact` / `ExactMoneyMath`
  - ทุกบรรทัดในธุรกรรมเดียวกันต้องอยู่ในสกุลเงิน (`currency_code = THB`) และขอบเขตบัญชี (`account_scope`: `ORDER_SETTLEMENT`, `OPERATING_CASH`, หรือ `COUNTERPARTY_SETTLEMENT`) เดียวกัน
  - รองรับ Idempotency ด้วย `operation_key` เพื่อป้องกันการโพสต์ซ้ำเมื่อเกิดการกดซ้ำหรือแอปถูกปิดกะทันหัน
- **การกลับรายการแบบผูกอ้างอิง (`reversalsnotedit`)**:
  - ห้ามแก้ไขหรือลบรายการบัญชีที่โพสต์ลงฐานข้อมูลแล้ว
  - การยกเลิกหรือปรับปรุงรายการที่โพสต์ผิดต้องสร้างธุรกรรมใหม่ผ่าน `postLinkedReversalTransaction` ที่สลับฝั่ง `DEBIT <-> CREDIT` ทุกบรรทัดของรายการต้นทางและผูก `reverses_transaction_id`
  - ป้องกันการกลับรายการซ้ำในรายการต้นทางเดิม และป้องกันการกลับรายการของรายการที่เป็น Reversal
- **การแยกสถานะยอดบิลและสิทธิรางวัลออกจากเงินสดจริง (`boundedallocations`, `orderamount != collectedcash`, `winningentitlement != paid`)**:
  - การยืนยันบิลสร้างยอดสั่งซื้อที่ยืนยันแล้ว (`currentTotalAmountMinor`) ซึ่งยังไม่ถือเป็นเงินสดที่เก็บได้จริงจนกว่าจะมีการบันทึกรับเงินสด (`INFLOW_COLLECTION`) และจัดสรร (`allocatePaymentToTargetTransaction`) เข้าสู่ `BILL_RECEIVABLE`
  - การอนุมัติผลรางวัลสร้างสิทธิได้รับรางวัล (`entitlementPayoutMinor`) ซึ่งยังไม่ถือเป็นเงินสดที่จ่ายแล้วจนกว่าจะมีการบันทึกจ่ายเงินสด (`OUTFLOW_ENTITLEMENT_PAYOUT`) และจัดสรรเข้าสู่ `WINNING_ENTITLEMENT`
  - เพดานการจัดสรร (`Bounded Allocation`): ยอดจัดสรรสะสมของรายการชำระเงินต้องไม่เกิน `payment.amountMinor` และยอดจัดสรรสะสมของบิลหรือสิทธิรางวัลเป้าหมายต้องไม่เกินยอดคงค้างของเป้าหมายนั้น
- **ผังบัญชีสาธิต (`D09 Demo Chart of Accounts`)**:
  - บัญชีทั้งหมดใน Phase 08 ถูกตั้งค่า `isDemoChart = true` และแสดงป้ายเตือน `[DEMO DEFAULT — D09 รอผู้รับผิดชอบบัญชีอนุมัติ]` อย่างชัดเจน ห้ามใช้กับข้อมูลเงินจริงจนกว่าผู้รับผิดชอบบัญชีจะลงนามอนุมัติผังบัญชีและการรับรู้รายได้/ค่าใช้จ่ายจริง

---

## 2. คำสั่งตรวจสอบและรันทดสอบใน Android Studio / CLI
เมื่อเปิดโปรเจกต์ในเครื่องที่มี JDK 17 และ Android SDK (API 35) สามารถรันคำสั่งต่อไปนี้เพื่อตรวจสอบ Phase 08 ได้ทันที:

```bash
# 1. รัน Unit Tests ทั้งหมด (รวม Phase08LedgerAllocationAndReconciliationTest)
./gradlew testDebugUnitTest

# 2. รันเฉพาะชุดทดสอบสมุดบัญชีคู่ การกลับรายการ การจัดสรรยอด และการกระทบยอดเงินสด Phase 08
./gradlew testDebugUnitTest --tests "com.oneproject.offline.ui.ledger.Phase08LedgerAllocationAndReconciliationTest"

# 3. สร้างไฟล์ติดตั้ง Debug APK
./gradlew assembleDebug
```
