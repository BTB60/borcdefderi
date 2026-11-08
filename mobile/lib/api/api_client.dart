import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

/// Business or transport error with the server's stable code and Azerbaijani message.
class ApiException implements Exception {
  final int status;
  final String code;
  final String message;
  final Map<String, String> fields;

  const ApiException(this.status, this.code, this.message, [this.fields = const {}]);

  @override
  String toString() => message;
}

/// Thin REST client. The store is never sent by the app: the server resolves it from the token.
class ApiClient {
  ApiClient({required this.baseUrl, http.Client? httpClient}) : _http = httpClient ?? http.Client();

  final String baseUrl;
  final http.Client _http;
  String? _token;

  void setToken(String? token) => _token = token;

  /// Called when a signed-in request gets 401 (token expired or account disabled); the session signs out.
  void Function()? onUnauthorized;

  Uri _uri(String path, [Map<String, String>? query]) {
    final base = Uri.parse(baseUrl);
    return base.replace(path: path, queryParameters: (query == null || query.isEmpty) ? null : query);
  }

  Map<String, String> _headers({bool json = false}) => {
        'Accept': 'application/json',
        if (json) 'Content-Type': 'application/json; charset=utf-8',
        if (_token != null) 'Authorization': 'Bearer $_token',
      };

  Future<dynamic> get(String path, {Map<String, String>? query}) =>
      _send(() => _http.get(_uri(path, query), headers: _headers()));

  Future<dynamic> post(String path, [Object? body]) => _send(() => _http.post(
        _uri(path),
        headers: _headers(json: true),
        body: body == null ? null : utf8.encode(jsonEncode(body)),
      ));

  Future<dynamic> put(String path, Object body) => _send(() => _http.put(
        _uri(path),
        headers: _headers(json: true),
        body: utf8.encode(jsonEncode(body)),
      ));

  Future<dynamic> _send(Future<http.Response> Function() call) async {
    final http.Response res;
    try {
      res = await call().timeout(const Duration(seconds: 25));
    } on http.ClientException {
      throw const ApiException(0, 'NETWORK', 'Serverlə əlaqə qurula bilmədi. İnternet bağlantınızı yoxlayın.');
    } on TimeoutException {
      throw const ApiException(0, 'NETWORK', 'Serverlə əlaqə qurula bilmədi. İnternet bağlantınızı yoxlayın.');
    }
    final body = res.body.isEmpty ? null : utf8.decode(res.bodyBytes);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return body == null ? null : jsonDecode(body);
    }
    var code = 'ERROR';
    var message = 'Xəta baş verdi';
    var fields = <String, String>{};
    if (body != null) {
      try {
        final data = jsonDecode(body) as Map<String, dynamic>;
        code = (data['error'] as String?) ?? code;
        message = (data['message'] as String?) ?? message;
        fields = ((data['fields'] as Map<String, dynamic>?) ?? {}).map((k, v) => MapEntry(k, v.toString()));
      } catch (_) {
        // non-JSON error body
      }
    }
    if (res.statusCode == 401 && _token != null && onUnauthorized != null) {
      onUnauthorized!();
    }
    throw ApiException(res.statusCode, code, message, fields);
  }

  void close() => _http.close();
}
