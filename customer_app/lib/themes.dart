import 'dart:math';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// The pattern drawn over a card's colours.
enum CardPattern { none, lines, circles, waves, dots, diamonds }

/// A ready-made look the customer may pick for a card instead of the store's own design.
class KartTheme {
  const KartTheme(this.id, this.name, this.color1, this.color2, {this.text = Colors.white, this.pattern = CardPattern.none, this.accent});

  final String id, name;
  final Color color1, color2, text;
  final CardPattern pattern;
  final Color? accent;
}

/// "store" means the store's own design; the rest are the app's presets.
const storeThemeId = 'store';

const cardThemes = <KartTheme>[
  KartTheme('gold', 'Qızıl', Color(0xFFB8860B), Color(0xFFF5D67B), text: Color(0xFF3B2A05), pattern: CardPattern.waves, accent: Color(0xFFFFF4C2)),
  KartTheme('silver', 'Gümüş', Color(0xFF8E9AAF), Color(0xFFE3E8EF), text: Color(0xFF1F2937), pattern: CardPattern.lines, accent: Colors.white),
  KartTheme('vip', 'VIP Qara', Color(0xFF0B0B0F), Color(0xFF2A2A35), text: Color(0xFFF5D67B), pattern: CardPattern.diamonds, accent: Color(0xFFD4A537)),
  KartTheme('ocean', 'Okean', Color(0xFF0369A1), Color(0xFF06B6D4), pattern: CardPattern.waves),
  KartTheme('sunset', 'Gün batımı', Color(0xFFF97316), Color(0xFFDB2777), pattern: CardPattern.circles),
  KartTheme('neon', 'Neon', Color(0xFF7C3AED), Color(0xFF22D3EE), pattern: CardPattern.dots),
  KartTheme('emerald', 'Zümrüd', Color(0xFF065F46), Color(0xFF10B981), pattern: CardPattern.lines),
  KartTheme('rose', 'Qızılgül', Color(0xFFBE185D), Color(0xFFF9A8D4), pattern: CardPattern.circles),
  KartTheme('night', 'Gecə', Color(0xFF0F1F3D), Color(0xFF3B1F6B), pattern: CardPattern.dots, accent: Color(0xFFD4A537)),
];

KartTheme? themeById(String? id) {
  for (final t in cardThemes) {
    if (t.id == id) return t;
  }
  return null;
}

/// Each card's chosen look is kept on the phone (the store's design stays the default).
class ThemeStore {
  static String _key(String cardId) => 'kart_theme_$cardId';

  static Future<Map<String, String>> load(Iterable<String> cardIds) async {
    final p = await SharedPreferences.getInstance();
    return {for (final id in cardIds) if (p.getString(_key(id)) != null) id: p.getString(_key(id))!};
  }

  static Future<void> save(String cardId, String themeId) async {
    final p = await SharedPreferences.getInstance();
    if (themeId == storeThemeId) {
      await p.remove(_key(cardId));
    } else {
      await p.setString(_key(cardId), themeId);
    }
  }
}

/// Draws a theme's pattern (soft, so the text stays readable).
class PatternPainter extends CustomPainter {
  PatternPainter(this.pattern, this.color);

  final CardPattern pattern;
  final Color color;

  @override
  void paint(Canvas canvas, Size s) {
    final p = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4;
    switch (pattern) {
      case CardPattern.none:
        break;
      case CardPattern.lines:
        for (double x = -s.height; x < s.width; x += 14) {
          canvas.drawLine(Offset(x, s.height), Offset(x + s.height, 0), p);
        }
      case CardPattern.circles:
        for (var i = 1; i <= 6; i++) {
          canvas.drawCircle(Offset(s.width * 0.95, s.height * 0.05), i * s.width * 0.09, p);
        }
      case CardPattern.waves:
        for (var row = 0; row < 7; row++) {
          final path = Path();
          final y0 = s.height * (0.35 + row * 0.1);
          path.moveTo(0, y0);
          for (double x = 0; x <= s.width; x += 6) {
            path.lineTo(x, y0 + sin(x / s.width * 2 * pi + row * 0.6) * s.height * 0.05);
          }
          canvas.drawPath(path, p);
        }
      case CardPattern.dots:
        final f = Paint()..color = color;
        for (double y = 8; y < s.height; y += 16) {
          for (double x = (y ~/ 16).isOdd ? 16 : 8; x < s.width; x += 16) {
            canvas.drawCircle(Offset(x, y), 1.6, f);
          }
        }
      case CardPattern.diamonds:
        const d = 22.0;
        for (double y = 0; y < s.height + d; y += d) {
          for (double x = 0; x < s.width + d; x += d) {
            final path = Path()
              ..moveTo(x, y - d / 2)
              ..lineTo(x + d / 2, y)
              ..lineTo(x, y + d / 2)
              ..lineTo(x - d / 2, y)
              ..close();
            canvas.drawPath(path, p);
          }
        }
    }
  }

  @override
  bool shouldRepaint(PatternPainter old) => old.pattern != pattern || old.color != color;
}
