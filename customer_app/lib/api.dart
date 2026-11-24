import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

/// The HesabPro API. Build with --dart-define=API_URL=https://borcdefderi.onrender.com (the default).
const String apiBaseUrl = String.fromEnvironment('API_URL', defaultValue: 'https://borcdefderi.onrender.com');

class ApiException implements Exception {
  const ApiException(this.status, this.code, this.message);

  final int status;
  final String code;
  final String message;

  @override
  String toString() => message;
}

/// Calls /api/public/customer-app/* with the customer's token (kept on the phone).
class Api {
  Api._();

  static final Api instance = Api._();
  static const _tokenKey = 'kart_token';
  static const _timeout = Duration(seconds: 60);

  String? token;

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    token = p.getString(_tokenKey);
  }

  Future<void> save(String? value) async {
    token = value;
    final p = await SharedPreferences.getInstance();
    if (value == null) {
      await p.remove(_tokenKey);
    } else {
      await p.setString(_tokenKey, value);
    }
  }

  Uri _uri(String path) => Uri.parse('$apiBaseUrl/api/public/customer-app$path');

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        if (token != null) 'Authorization': 'Bearer $token',
      };

  Future<dynamic> get(String path) => _send(() => http.get(_uri(path), headers: _headers));

  Future<dynamic> post(String path, Object body) =>
      _send(() => http.post(_uri(path), headers: _headers, body: jsonEncode(body)));

  Future<dynamic> _send(Future<http.Response> Function() call) async {
    final http.Response res;
    try {
      res = await call().timeout(_timeout);
    } on TimeoutException {
      throw const ApiException(0, 'NETWORK', 'Serverə qoşulmaq olmadı. İnterneti yoxlayın.');
    } on http.ClientException {
      throw const ApiException(0, 'NETWORK', 'Serverə qoşulmaq olmadı. İnterneti yoxlayın.');
    }
    final text = utf8.decode(res.bodyBytes);
    // a gateway error page (the server is still starting) has no JSON message; our own errors do
    final ours = text.trimLeft().startsWith('{');
    if ((res.statusCode == 502 || res.statusCode == 503 || res.statusCode == 504) && !ours) {
      throw const ApiException(0, 'NETWORK', 'Server oyanır. Bir neçə saniyə sonra yenidən cəhd edin.');
    }
    if (res.statusCode >= 200 && res.statusCode < 300) return text.isEmpty ? null : jsonDecode(text);
    var code = 'ERROR', message = 'Xəta baş verdi';
    try {
      final d = jsonDecode(text) as Map<String, dynamic>;
      code = (d['error'] as String?) ?? code;
      message = (d['message'] as String?) ?? message;
    } catch (_) {}
    throw ApiException(res.statusCode, code, message);
  }
}

/// Server-made barcode picture of a card number (what the till scans).
String barcodeUrl(String code) => '$apiBaseUrl/api/public/barcode.png?text=${Uri.encodeQueryComponent(code)}';
