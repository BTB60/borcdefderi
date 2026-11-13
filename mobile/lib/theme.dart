import 'package:flutter/material.dart';

const navy = Color(0xFF101C35);
const gold = Color(0xFFD6AD60);
const canvas = Color(0xFFF5F7FB);

ThemeData buildTheme() {
  final scheme = ColorScheme.fromSeed(seedColor: navy, primary: navy, secondary: gold, surface: Colors.white);
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: canvas,
    appBarTheme: const AppBarTheme(backgroundColor: canvas, foregroundColor: navy, elevation: 0, centerTitle: false),
    cardTheme: CardThemeData(
      color: Colors.white,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: Colors.grey.shade200)),
    ),
    // Box border whose label floats INSIDE the field (not onto the top line), so fields stacked without spacing never
    // overlap each other's labels.
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      contentPadding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      border: BoxInputBorder(borderSide: BorderSide(color: Colors.grey.shade400)),
      enabledBorder: BoxInputBorder(borderSide: BorderSide(color: Colors.grey.shade400)),
      disabledBorder: BoxInputBorder(borderSide: BorderSide(color: Colors.grey.shade300)),
      focusedBorder: const BoxInputBorder(borderSide: BorderSide(color: gold, width: 2)),
      errorBorder: BoxInputBorder(borderSide: BorderSide(color: Colors.red.shade400)),
      focusedErrorBorder: BoxInputBorder(borderSide: BorderSide(color: Colors.red.shade700, width: 2)),
      floatingLabelStyle: const TextStyle(color: navy, fontWeight: FontWeight.w600),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: navy,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 20),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      indicatorColor: gold.withValues(alpha: 0.25),
      labelTextStyle: WidgetStateProperty.all(const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: navy)),
    ),
  );
}

/// Rounded box around a text field. Unlike [OutlineInputBorder] it is not an "outline" border, so the floating label
/// stays inside the box instead of sitting on (and sticking out of) the top edge.
class BoxInputBorder extends InputBorder {
  const BoxInputBorder({super.borderSide = const BorderSide(), this.radius = 12});

  final double radius;

  @override
  bool get isOutline => false;

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.all(borderSide.width);

  @override
  BoxInputBorder copyWith({BorderSide? borderSide}) => BoxInputBorder(borderSide: borderSide ?? this.borderSide, radius: radius);

  RRect _rrect(Rect rect) => RRect.fromRectAndRadius(rect, Radius.circular(radius));

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) =>
      Path()..addRRect(_rrect(rect).deflate(borderSide.width));

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) => Path()..addRRect(_rrect(rect));

  @override
  void paintInterior(Canvas canvas, Rect rect, Paint paint, {TextDirection? textDirection}) =>
      canvas.drawRRect(_rrect(rect), paint);

  @override
  bool get preferPaintInterior => true;

  @override
  void paint(Canvas canvas, Rect rect,
      {double? gapStart, double gapExtent = 0.0, double gapPercentage = 0.0, TextDirection? textDirection}) {
    if (borderSide.style == BorderStyle.none) return;
    canvas.drawRRect(_rrect(rect).deflate(borderSide.width / 2), borderSide.toPaint());
  }

  @override
  ShapeBorder scale(double t) => BoxInputBorder(borderSide: borderSide.scale(t), radius: radius * t);

  @override
  bool operator ==(Object other) => other is BoxInputBorder && other.borderSide == borderSide && other.radius == radius;

  @override
  int get hashCode => Object.hash(borderSide, radius);
}
