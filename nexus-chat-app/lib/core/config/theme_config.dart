import 'package:flutter/material.dart';

/// Nexus Chat 应用主题配置
class AppTheme {
  AppTheme._();

  // ===== 主色调 =====
  static const Color primary = Color(0xFF10B981); // Emerald-500
  static const Color primaryDark = Color(0xFF059669); // Emerald-600
  static const Color primaryLight = Color(0xFF34D399); // Emerald-400

  // ===== 背景色 =====
  static const Color backgroundLight = Color(0xFFFFFFFF);
  static const Color backgroundDark = Color(0xFF111827); // Gray-900

  // ===== 文字色 =====
  static const Color textPrimary = Color(0xFF1F2937); // Gray-800
  static const Color textSecondary = Color(0xFF6B7280); // Gray-500
  static const Color textMuted = Color(0xFF9CA3AF); // Gray-400
  static const Color textDark = Color(0xFFFFFFFF);
  static const Color textDarkSecondary = Color(0xFF9CA3AF);

  // ===== 表面色 =====
  static const Color surfaceLight = Color(0xFFF3F4F6); // Gray-100
  static const Color surfaceDark = Color(0xFF1F2937); // Gray-800

  // ===== 渐变色 =====
  static const LinearGradient primaryGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [primaryLight, primaryDark],
  );

  // ===== 阴影 =====
  static List<BoxShadow> primaryShadow = [
    BoxShadow(
      color: primary.withValues(alpha: 0.3),
      blurRadius: 24,
      offset: const Offset(0, 8),
      spreadRadius: 0,
    ),
  ];

  // ===== 亮色主题 =====
  static ThemeData lightTheme = ThemeData(
    useMaterial3: true,
    brightness: Brightness.light,
    primaryColor: primary,
    scaffoldBackgroundColor: backgroundLight,
    colorScheme: const ColorScheme.light(
      primary: primary,
      secondary: primaryLight,
      surface: backgroundLight,
      onPrimary: Colors.white,
      onSecondary: Colors.white,
      onSurface: textPrimary,
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: backgroundLight,
      foregroundColor: textPrimary,
      elevation: 0,
    ),
    textTheme: const TextTheme(
      displayLarge: TextStyle(
        fontSize: 36,
        fontWeight: FontWeight.w800,
        color: textPrimary,
        letterSpacing: -0.5,
      ),
      displayMedium: TextStyle(
        fontSize: 28,
        fontWeight: FontWeight.w700,
        color: textPrimary,
      ),
      titleLarge: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w600,
        color: textPrimary,
      ),
      titleMedium: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w500,
        color: textPrimary,
      ),
      bodyLarge: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w400,
        color: textPrimary,
      ),
      bodyMedium: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w400,
        color: textSecondary,
      ),
      bodySmall: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w400,
        color: textMuted,
      ),
      labelSmall: TextStyle(
        fontSize: 10,
        fontWeight: FontWeight.w600,
        color: textMuted,
        letterSpacing: 2.0,
      ),
    ),
  );

  // ===== 暗色主题 =====
  static ThemeData darkTheme = ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    primaryColor: primary,
    scaffoldBackgroundColor: backgroundDark,
    colorScheme: const ColorScheme.dark(
      primary: primary,
      secondary: primaryLight,
      surface: backgroundDark,
      onPrimary: Colors.white,
      onSecondary: Colors.white,
      onSurface: textDark,
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: backgroundDark,
      foregroundColor: textDark,
      elevation: 0,
    ),
    textTheme: const TextTheme(
      displayLarge: TextStyle(
        fontSize: 36,
        fontWeight: FontWeight.w800,
        color: textDark,
        letterSpacing: -0.5,
      ),
      displayMedium: TextStyle(
        fontSize: 28,
        fontWeight: FontWeight.w700,
        color: textDark,
      ),
      titleLarge: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w600,
        color: textDark,
      ),
      titleMedium: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w500,
        color: textDark,
      ),
      bodyLarge: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w400,
        color: textDark,
      ),
      bodyMedium: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w400,
        color: textDarkSecondary,
      ),
      bodySmall: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w400,
        color: textMuted,
      ),
      labelSmall: TextStyle(
        fontSize: 10,
        fontWeight: FontWeight.w600,
        color: textMuted,
        letterSpacing: 2.0,
      ),
    ),
  );
}
