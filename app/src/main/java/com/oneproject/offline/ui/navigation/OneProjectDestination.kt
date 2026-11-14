package com.oneproject.offline.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * เมนูหลัก 5 หมวดตามข้อกำหนด 05_UI:
 * ภาพรวม / รับรายการ / บิล / รายงาน / เพิ่มเติม
 */
enum class OneProjectDestination(
    val route: String,
    val titleTh: String,
    val talkBackDescription: String,
    val icon: ImageVector
) {
    OVERVIEW(
        route = "overview",
        titleTh = "ภาพรวม",
        talkBackDescription = "แท็บภาพรวมและความเสี่ยงในเครื่อง",
        icon = Icons.Outlined.Dashboard
    ),
    ORDER_ENTRY(
        route = "order_entry",
        titleTh = "รับรายการ",
        talkBackDescription = "แท็บรับรายการ กรอกเอง เลือกหลายเลข วางข้อความ และตะกร้า",
        icon = Icons.Outlined.EditNote
    ),
    BILLS(
        route = "bills",
        titleTh = "บิล",
        talkBackDescription = "แท็บบิล ประวัติการแก้ไข และการตั้งค่าอัตราจ่าย",
        icon = Icons.Outlined.ReceiptLong
    ),
    REPORTS(
        route = "reports",
        titleTh = "รายงาน",
        talkBackDescription = "แท็บรายงานและตรวจผลรางวัลในเครื่อง",
        icon = Icons.Outlined.Assessment
    ),
    MORE(
        route = "more",
        titleTh = "เพิ่มเติม",
        talkBackDescription = "แท็บเพิ่มเติม งวด คู่สัญญา บัญชี ล็อกแอป และสำรองข้อมูล",
        icon = Icons.Outlined.MoreHoriz
    )
}
