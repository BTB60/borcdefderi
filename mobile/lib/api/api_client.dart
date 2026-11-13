import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

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

  /// The free hosting plan sleeps when idle and needs up to ~1 minute to wake, so requests wait long enough.
  static const _timeout = Duration(seconds: 90);

  /// Fire-and-forget ping on app start so the server is already waking while the user types.
  void warmUp() {
    _http.get(_uri('/api/health')).timeout(_timeout).then((_) {}, onError: (_) {});
  }

  /// Called when a signed-in request gets 401 (token expired or account disabled); the session signs out.
  void Function()? onUnauthorized;

  /// Called when the server answers 402 SUBSCRIPTION_REQUIRED (the store's subscription ended).
  void Function()? onSubscriptionRequired;

  /// [path] may carry its own query (e.g. `/export?format=pdf`); it is merged with [query] instead of being escaped.
  Uri _uri(String path, [Map<String, String>? query]) {
    final base = Uri.parse(baseUrl);
    final rel = Uri.parse(path);
    final q = {...rel.queryParameters, ...?query};
    return base.replace(path: rel.path, queryParameters: q.isEmpty ? null : q);
  }

  Map<String, String> _headers({bool json = false}) => {
        'Accept': 'application/json',
        if (json) 'Content-Type': 'application/json; charset=utf-8',
        if (_token != null) 'Authorization': 'Bearer $_token',
      };

  Future<dynamic> get(String path, {Map<String, String>? query}) =>
      _send(() => _http.get(_uri(path, query), headers: _headers()));

  /// Downloads a protected file (e.g. the PDF receipt) as bytes.
  Future<List<int>> getBytes(String path) async {
    final http.Response res;
    try {
      res = await _http.get(_uri(path), headers: {..._headers(), 'Accept': '*/*'}).timeout(_timeout);
    } on http.ClientException {
      throw const ApiException(0, 'NETWORK', 'Serverlə əlaqə qurula bilmədi. İnternet bağlantınızı yoxlayın.');
    } on TimeoutException {
      throw const ApiException(0, 'NETWORK', 'Serverlə əlaqə qurula bilmədi. İnternet bağlantınızı yoxlayın.');
    }
    if (res.statusCode >= 200 && res.statusCode < 300) return res.bodyBytes;
    if (res.statusCode == 401 && _token != null && onUnauthorized != null) onUnauthorized!();
    throw ApiException(res.statusCode, 'DOWNLOAD_FAILED', 'Fayl yüklənə bilmədi');
  }

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
      res = await call().timeout(_timeout);
    } on http.ClientException {
      throw const ApiException(0, 'NETWORK', 'Serverlə əlaqə qurula bilmədi. İnternet bağlantınızı yoxlayın.');
    } on TimeoutException {
      throw const ApiException(0, 'NETWORK', 'Serverlə əlaqə qurula bilmədi. İnternet bağlantınızı yoxlayın.');
    }
    if (res.statusCode == 502 || res.statusCode == 503 || res.statusCode == 504) {
      throw const ApiException(0, 'NETWORK', 'Server oyanır. Bir neçə saniyədən sonra yenidən cəhd edin.');
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
    if (res.statusCode == 402 && code == 'SUBSCRIPTION_REQUIRED' && onSubscriptionRequired != null) {
      onSubscriptionRequired!();
    }
    throw ApiException(res.statusCode, code, message, fields);
  }

  Future<dynamic> delete(String path) => _send(() => _http.delete(_uri(path), headers: _headers()));

  /// Multipart upload of one file (e.g. the profile picture).
  Future<dynamic> upload(String path, String field, List<int> bytes, String fileName, String contentType,
          {Map<String, String> fields = const {}}) =>
      _send(() async {
        final req = http.MultipartRequest('POST', _uri(path))
          ..headers.addAll(_headers())
          ..fields.addAll(fields)
          ..files.add(http.MultipartFile.fromBytes(field, bytes, filename: fileName, contentType: MediaType.parse(contentType)));
        return http.Response.fromStream(await _http.send(req));
      });

  void close() => _http.close();
}
