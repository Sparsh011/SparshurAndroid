package com.sparsh.sparshur.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Family = FontFamily.SansSerif
val Typography = Typography(
    headlineLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 31.sp, lineHeight = 36.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 31.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 15.sp)
)
