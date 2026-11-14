# ข้อมูลจำเพาะทางเทคนิค Phase 10: คู่สัญญา การตีออกด้วยมือ (`Pending / Accepted / Partial / Rejected`) และการแยกบัญชีคู่สัญญาออกจากความเสี่ยง

## 1. สาระสำคัญของ Phase 10
- **การสร้างชุดรายการตีออกด้วยมือ (`Manual Transfer Batch` — สถานะ `PENDING`)**:
  - ใบตีออกที่สร้างใหม่มีสถานะ `PENDING` โดย `acceptedAmountMinor = 0` และ `acceptedRecoverablePayoutMinor = 0`
  - ยืนยันกฎเหล็ก `Pending transfers reduce no exposure` (`pendingExposureReductionMinor == 0L`)
- **การบันทึกผลตอบรับจากคู่สัญญาด้วยมือ (`ACCEPTED / PARTIAL / REJECTED`)**:
  - `ACCEPTED`: ยอมรับเต็มจำนวนทุกรายการ คำนวณ `acceptedRecoverablePayoutMinor = acceptedMinor * contractRate` (`HALF_UP`)
  - `PARTIAL`: ยอมรับบางส่วนตามจำนวนจริง (`0 < accepted <= submitted`)
  - `REJECTED`: ปฏิเสธทั้งหมด (`accepted = 0`, `recoverable = 0`)
  - บล็อกการแก้ไขหรือบันทึกผลตอบรับซ้ำในใบตีออกที่ได้รับการตัดสินไปแล้ว
- **การแยกบัญชีคู่สัญญาออกจากความเสี่ยงและปิดการสั่งจ่ายภายนอก (`D09`)**:
  - การตั้งหนี้คู่สัญญาโพสต์ในขอบเขต `COUNTERPARTY_SETTLEMENT` (`1301-DEMO` / `2301-DEMO`) แยกจากบัญชีบิลลูกค้า (`ORDER_SETTLEMENT`)
  - ปิดการส่งออกหรือสั่งโอนเงินภายนอกอัตโนมัติทั้งหมด (`isExternalExecutionDisabled = true`)

## 2. คำสั่งรันทดสอบใน Android Studio
```bash
./gradlew testDebugUnitTest --tests "com.oneproject.offline.ui.counterparty.Phase10CounterpartyAndTransferBatchTest"
```
