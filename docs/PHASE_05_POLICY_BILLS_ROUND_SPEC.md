# ข้อกำหนดและการตรวจสอบระบบตั้งค่านโยบาย บิล/ประวัติการแก้ไข และการจัดการงวด (Phase 05)

> **สถานะใน Container ปัจจุบัน**: `BUILD_NOT_RUN` เนื่องจากสภาพแวดล้อม Container ไม่มี JDK, Android SDK และ Gradle จึงต้องรัน `./gradlew testDebugUnitTest assembleDebug` ใน Android Studio บนเครื่องพัฒนา

---

## 1. สิ่งที่ส่งมอบใน Phase 05 (ตาม `01`, `04_SPEC`, `05_UI` และ `06_DECISIONS`)
1. **การตั้งค่ามาตรฐาน / รายเลข / ประวัติ (`PolicyDefaultsOverridesAndHistorySection`)**:
   - แสดงการเปรียบเทียบค่ามาตรฐานเดิมกับค่าใหม่ก่อนบันทึก และบังคับระบุเหตุผลทุกครั้ง
   - ทุกการเปลี่ยนค่ามาตรฐานหรือรายเลขจะสร้าง `PolicyVersionEntity` รุ่นใหม่เสมอ (ไม่เขียนทับประวัติเดิม และบังคับให้ใบตรวจทานที่ค้างอยู่บนรุ่นเก่าต้องตรวจทานใหม่ `Policy change forces review`)
   - **การสืบทอดค่ารายช่อง (`Per-Field Explicit Override Inheritance` ตาม `D07` และ `05_UI`)**:
     - แยกการตั้งค่ารายเลขออกเป็น 4 ช่องอิสระ: `อัตราจ่าย`, `เพดานรับ`, `เพดานจ่าย`, และ `สถานะเปิด/ปิดรับ` (ช่องที่ไม่ติ๊ก Override จะเป็น `null` คือสืบทอดค่าจากมาตรฐาน)
     - เมื่อเปลี่ยนค่ามาตรฐาน: ค่าเริ่มต้นจะไม่ทับค่าที่ตั้ง Override รายเลขไว้ (`D07`)
     - มีตัวเลือก **Apply-All อัตราจ่ายและเพดาน** แยกอิสระจากตัวเลือก **รีเซ็ตสถานะเปิด/ปิดรับรายเลข** (`inheritanceต่อช่องแยกapply-all/เปิดปิดรับ`)
2. **การค้นหาบิล รายละเอียด ประวัติ Revision และการยกเลิกพร้อมเหตุผล (`BillsSearchAndRevisionSection`)**:
   - ค้นหาบิลในงวดด้วยรหัสบิล (`human_bill_code`), UUID หรือหมายเหตุ
   - แสดงรายละเอียดบิล รายการเลขที่มีผลในปัจจุบัน และประวัติ `Revision #1, #2, ...` ทั้งหมด
   - **การแก้ไขบิล (`submitSelectedBillEditRevision`)**: สร้าง Revision ใหม่และหักยอด Revision เดิมที่เคย Active ออกจาก `NumberSideCounterEntity` ก่อนบวกยอดใหม่ (`otherActiveBills + replacement`, ไม่นำ `old + replacement` มานับซ้ำ)
   - **การยกเลิกบิล (`submitSelectedBillCancel`)**: บังคับระบุเหตุผล ถอนยอดออกจาก `NumberSideCounterEntity` ตั้ง `activeRevisionId = null` และเก็บประวัติ Revision เดิมไว้ครบ (ไม่ลบข้อมูลจริง)
3. **การจัดการสถานะงวดและการปรับปรุงงวดที่ปิดแล้ว (`RoundLifecycleAndClosedAdjustmentSection`)**:
   - รองรับการเปลี่ยนสถานะงวด (`DRAFT` / `OPEN` / `PAUSED` / `CLOSED`) พร้อมเหตุผล
   - เมื่องวดถูกพักรับ (`PAUSED`) หรือปิดแล้ว (`CLOSED`) ระบบจะบล็อกการรับบิลใหม่และการแก้ไขบิลปกติทั้งหมด
   - งวดที่ปิดแล้ว (`CLOSED`) ไม่สามารถเปิดรับซ้ำได้ และต้องบันทึกผ่าน **รายการอนุมัติปรับปรุงงวดปิดแล้ว (`Closed-Round Approved Adjustment` / `ApprovalRecordEntity`)** ตามกฎข้อ 01 และ `D08`
