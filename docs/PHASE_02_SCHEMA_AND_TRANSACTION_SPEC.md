# ข้อกำหนดโครงสร้างฐานข้อมูล Room (Schema v2), Migration และ Durable Transaction Protocol (Phase 02)

> **สถานะใน Container ปัจจุบัน**: `BUILD_NOT_RUN` เนื่องจากสภาพแวดล้อม Container ไม่มี JDK, Android SDK และ Gradle จึงต้องรัน `./gradlew testDebugUnitTest connectedDebugAndroidTest` ใน Android Studio บนเครื่องพัฒนา

---

## 1. ตารางข้อมูลทั้งหมดใน `OneProjectDatabase` (Version 2 — 28 Entities)
1. **กลุ่มผู้ใช้และสิทธิ์ในเครื่อง**: `local_actors`, `local_role_grants`, `approval_records`
2. **กลุ่มงวดและนโยบาย**: `rounds`, `policy_versions`, `number_policy_overrides` (แยกการสืบทอดค่า `null` กับค่าที่ Override รายช่องอย่างชัดเจนตาม `D07`)
3. **กลุ่มตะกร้า ใบตรวจทาน และการทำงานที่ทนต่อแอปดับ**: `carts`, `cart_lines` (ไม่ยุบรวมรายการซ้ำเงียบๆ ตาม `D03`), `quote_snapshots`, `durable_operations`
4. **กลุ่มบิล ประวัติการแก้ไข และตัวนับยอดสะสมที่ถือสิทธิ์ขาด**: `bills`, `bill_revisions`, `bill_number_side_lines`, `number_side_counters`
5. **กลุ่มผลรางวัลและสิทธิได้รับเงิน**: `result_revisions`, `winning_entitlements`
6. **กลุ่มบัญชีแยกประเภทและการจัดสรรเงินสด**: `ledger_accounts`, `ledger_transactions`, `ledger_entries`, `payment_records`, `payment_allocations`
7. **กลุ่มคู่สัญญา การตีออกด้วยมือ และการสำรองข้อมูล**: `counterparties`, `counterparty_contracts`, `manual_transfer_batches`, `manual_transfer_lines`, `transfer_responses`, `backup_metadata`, `audit_logs`

---

## 2. ลำดับการทำงานของ `executeAtomicBillCommit` และ `executeAtomicBillEditRevision`
ภายใน `@Transaction` เดียวของ Room:
1. **Local Access Check**: ตรวจสอบสถานะผู้ใช้งานในเครื่อง (`local_actors`)
2. **Durable Operation Replay ก่อนตรวจอายุ Quote**:
   - ค้นหา `operation_key` ใน `durable_operations`
   - หากมีอยู่แล้วแต่ `payload_sha256` ไม่ตรงกับคำขอปัจจุบัน -> คืนค่า **`PayloadConflict`** ทันที
   - หากมีอยู่แล้วและ `payload_sha256` ตรงกันและสถานะเป็น `COMPLETED` -> คืนค่า **`Committed(replayedFromDurableOperation = true)`** ทันที **โดยไม่สนใจว่า Quote จะหมดอายุหรือ Policy จะเปลี่ยนไปแล้ว** เพื่อรองรับกรณีแอปถูกปิดกะทันหัน (Process Death) หลัง Commit สำเร็จแต่ก่อนแสดงหน้าสำเร็จ
3. **Recheck Round & Policy**: ตรวจว่า `round.status == OPEN`, `round.activePolicyVersion == expectedPolicyVersion` (หากไม่ตรงบังคับตรวจทานใหม่ `POLICY_CHANGED_FORCE_REVIEW`) และ `nowEpochMs <= quote.expiresAtEpochMs`
4. **Recheck Caps & Overflow (`Math.addExact` / `Math.subtractExact`)**:
   - กรณีสร้างบิลใหม่: `newTotal = currentCounter + billLines`
   - กรณีแก้ไขบิล (`executeAtomicBillEditRevision`): หักยอดของ `activeRevisionId` เดิมออกก่อน (`otherActiveBills = currentCounter - oldRevision`) แล้วจึงบวกยอดใหม่ (`newTotal = otherActiveBills + replacement`) ตามกฎ `04_SPEC` และ `07_TEST_FIXTURES`
5. **Atomic Write**: บันทึก `bills`, `bill_revisions`, `bill_number_side_lines`, `number_side_counters`, `durable_operations` และ `audit_logs` พร้อมกันทั้งหมด
