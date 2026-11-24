import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

Color _color(Object? v, Color fallback) {
  final s = '${v ?? ''}'.replaceAll('#', '');
  if (s.length != 6) return fallback;
  final i = int.tryParse(s, radix: 16);
  return i == null ? fallback : Color(0xFF000000 | i);
}

Uint8List? _dataUrl(Object? v) {
  final s = '${v ?? ''}';
  final comma = s.indexOf(',');
  if (!s.startsWith('data:image/') || comma < 0) return null;
  try {
    return base64Decode(s.substring(comma + 1));
  } catch (_) {
    return null;
  }
}

/// The store's card design (Bonus kartları → Kart dizaynı in HesabPro).
class CardDesign {
  CardDesign(Map<String, dynamic>? d)
      : color1 = _color(d?['color1'], const Color(0xFF0F1F3D)),
        color2 = _color(d?['color2'], const Color(0xFF3B1F6B)),
        textColor = _color(d?['textColor'], Colors.white),
        background = _dataUrl(d?['backgroundImage']),
        logo = _dataUrl(d?['logo']),
        shade = ((d?['shade'] as num?)?.toDouble() ?? 30).clamp(0, 80) / 100,
        title = (d?['title'] as String?)?.trim().isNotEmpty == true ? d!['title'] as String : 'Bonus kartı',
        showStore = d?['showStore'] != false,
        showPercent = d?['showPercent'] != false;

  final Color color1, color2, textColor;
  final Uint8List? background, logo;
  final double shade;
  final String title;
  final bool showStore, showPercent;
}

class LoyaltyCard {
  LoyaltyCard(Map<String, dynamic> j)
      : id = '${j['id']}',
        storeName = '${j['storeName'] ?? ''}',
        storePhone = j['storePhone'] as String?,
        number = '${j['number']}',
        fullName = '${j['fullName'] ?? ''}',
        percent = _n(j['percent']),
        balance = _n(j['balance']),
        design = CardDesign(j['design'] as Map<String, dynamic>?);

  final String id, storeName, number, fullName;
  final String? storePhone;
  final double percent, balance;
  final CardDesign design;
}

class BonusEntry {
  BonusEntry(Map<String, dynamic> j)
      : kind = '${j['kind']}',
        amount = _n(j['amount']),
        saleNumber = j['saleNumber'] as String?,
        createdAt = DateTime.tryParse('${j['createdAt']}')?.toLocal();

  final String kind;
  final double amount;
  final String? saleNumber;
  final DateTime? createdAt;
}

/// A store's promo post (discount, new goods) from Reklamlar in HesabPro.
class Promo {
  Promo(Map<String, dynamic> j)
      : id = '${j['id']}',
        storeName = '${j['storeName'] ?? ''}',
        title = '${j['title'] ?? ''}',
        subtitle = j['subtitle'] as String?,
        badge = j['badge'] as String?,
        oldPrice = (j['oldPrice'] as num?)?.toDouble(),
        newPrice = (j['newPrice'] as num?)?.toDouble(),
        color = _color(j['color'], const Color(0xFF7C3AED)),
        hasImage = j['hasImage'] == true,
        imageVersion = (j['imageVersion'] as num?)?.toInt() ?? 0,
        endsOn = DateTime.tryParse('${j['endsOn']}');

  final String id, storeName, title;
  final String? subtitle, badge;
  final double? oldPrice, newPrice;
  final Color color;
  final bool hasImage;
  final int imageVersion;
  final DateTime? endsOn;

  /// "-30%" from the prices when the store wrote no badge.
  String? get label {
    if (badge != null && badge!.trim().isNotEmpty) return badge!.trim();
    final o = oldPrice, n = newPrice;
    if (o != null && n != null && o > n && o > 0) return '-${((1 - n / o) * 100).round()}%';
    return null;
  }

  /// Days left until the last day of the offer (null when it has no end).
  int? get daysLeft {
    final e = endsOn;
    if (e == null) return null;
    final now = DateTime.now();
    return DateTime(e.year, e.month, e.day).difference(DateTime(now.year, now.month, now.day)).inDays;
  }
}
