import 'dart:convert';

import 'package:borcdefteri_mobile/api/api_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test('sends the bearer token and decodes JSON', () async {
    String? seenAuth;
    final client = MockClient((req) async {
      seenAuth = req.headers['Authorization'];
      return http.Response(jsonEncode({'ok': true}), 200, headers: {'content-type': 'application/json'});
    });
    final api = ApiClient(baseUrl: 'http://localhost:8080', httpClient: client)..setToken('tok');
    final res = await api.get('/api/auth/me') as Map<String, dynamic>;
    expect(seenAuth, 'Bearer tok');
    expect(res['ok'], isTrue);
  });

  test('maps a server error body to ApiException with code and status', () async {
    final client = MockClient((_) async => http.Response.bytes(
          utf8.encode(jsonEncode({'error': 'PAYMENT_EXCEEDS_BALANCE', 'message': 'Çox məbləğ'})), 409,
          headers: {'content-type': 'application/json; charset=utf-8'}));
    final api = ApiClient(baseUrl: 'http://localhost:8080', httpClient: client);
    await expectLater(
      api.post('/api/debts/1/payments', {'amount': '1'}),
      throwsA(isA<ApiException>()
          .having((e) => e.code, 'code', 'PAYMENT_EXCEEDS_BALANCE')
          .having((e) => e.status, 'status', 409)),
    );
  });

  test('a network failure becomes a NETWORK ApiException', () async {
    final client = MockClient((_) async => throw http.ClientException('down'));
    final api = ApiClient(baseUrl: 'http://localhost:8080', httpClient: client);
    await expectLater(api.get('/api/health'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'NETWORK')));
  });
}
