package com.oneproject.offline.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * กฎข้อ 01 & 05_UI:
 * - ใช้ฟอนต์ในเครื่อง (Local System/Bundled Thai Font) ห้ามดึงฟอนต์จากอินเทอร์เน็ตขณะรันแอป
 * - รองรับการขยายขนาดอักษร (Font Scaling ด้วยหน่วย sp และ lineHeight ที่เผื่อสระ/วรรณยุกต์ภาษาไทย)
 */
val LocalThaiFontFamily = FontFamily.SansSerif

val OneProjectTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = LocalThaiFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = LocalThaiFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = LocalThaiFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = LocalThaiFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = LocalThaiFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp
    ),
    labelLarge = TextStyle(
        fontFamily = LocalThaiFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = LocalThaiFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
)
