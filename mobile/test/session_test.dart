import 'dart:convert';

import 'package:borcdefteri_mobile/api/api_client.dart';
import 'package:borcdefteri_mobile/state/session.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Map<String, dynamic> _me() => {
      'userId': 'u', 'email': 'o@x.az', 'fullName': 'Owner', 'platformRole': 'USER', 'role': 'STORE_OWNER',
      'storeName': 'Market', 'permissions': [], 'subscription': null,
    };

void main() {
  test('login stores the token and exposes the user', () async {
    final client = MockClient((req) async {
      expect(req.url.path, '/api/auth/login');
      return http.Response(jsonEncode({'token': 'jwt-1', 'expiresInSeconds': 60, 'me': _me()}), 200);
    });
    final tokens = MemoryTokenStore();
    final session = Session(api: ApiClient(baseUrl: 'http://x', httpClient: client), tokens: tokens);
    await session.login('o@x.az', 'Owner#Passw0rd');
    expect(session.isSignedIn, isTrue);
    expect(session.me!.storeName, 'Market');
    expect(await tokens.read(), 'jwt-1');
  });

  test('restoring an expired token signs the user out', () async {
    final client = MockClient((_) async => http.Response(jsonEncode({'error': 'UNAUTHORIZED', 'message': 'x'}), 401));
    final tokens = MemoryTokenStore();
    await tokens.write('expired');
    final session = Session(api: ApiClient(baseUrl: 'http://x', httpClient: client), tokens: tokens);
    await session.restore();
    expect(session.isSignedIn, isFalse);
    expect(session.restoring, isFalse);
    expect(await tokens.read(), isNull);
  });

  test('being offline at start-up keeps the saved session (no forced logout)', () async {
    final client = MockClient((_) async => throw http.ClientException('offline'));
    final tokens = MemoryTokenStore();
    await tokens.write('valid-token');
    final session = Session(api: ApiClient(baseUrl: 'http://x', httpClient: client), tokens: tokens);
    await session.restore();
    expect(session.isSignedIn, isFalse);
    expect(session.restoreError, isNotNull);
    expect(await tokens.read(), 'valid-token');
  });

  test('a 401 after login signs the user out once the token itself is rejected', () async {
    var calls = 0;
    final client = MockClient((req) async {
      calls++;
      if (req.url.path == '/api/auth/login') {
        return http.Response(jsonEncode({'token': 't', 'expiresInSeconds': 1, 'me': _me()}), 200);
      }
      return http.Response.bytes(utf8.encode(jsonEncode({'error': 'UNAUTHORIZED', 'message': 'Giriş tələb olunur'})), 401);
    });
    final tokens = MemoryTokenStore();
    final session = Session(api: ApiClient(baseUrl: 'http://x', httpClient: client), tokens: tokens);
    await session.login('o@x.az', 'Owner#Passw0rd');
    await expectLater(session.api.get('/api/debts'), throwsA(isA<ApiException>()));
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(calls, 3); // login, debts, then /api/auth/me to confirm
    expect(session.isSignedIn, isFalse);
    expect(await tokens.read(), isNull);
  });

  test('a 401 from one endpoint does not sign out while the token is still valid', () async {
    // An older server answers 401 for an endpoint it does not have (e.g. /api/pocket).
    final client = MockClient((req) async {
      if (req.url.path == '/api/auth/login') {
        return http.Response(jsonEncode({'token': 't', 'expiresInSeconds': 1, 'me': _me()}), 200);
      }
      if (req.url.path == '/api/auth/me') return http.Response(jsonEncode(_me()), 200);
      return http.Response.bytes(utf8.encode(jsonEncode({'error': 'UNAUTHORIZED', 'message': 'Giriş tələb olunur'})), 401);
    });
    final tokens = MemoryTokenStore();
    final session = Session(api: ApiClient(baseUrl: 'http://x', httpClient: client), tokens: tokens);
    await session.login('o@x.az', 'Owner#Passw0rd');
    await expectLater(session.api.get('/api/pocket'), throwsA(isA<ApiException>()));
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(session.isSignedIn, isTrue);
    expect(await tokens.read(), 't');
  });

  test('receipt PDF is downloaded as bytes with the bearer token', () async {
    final client = MockClient((req) async {
      expect(req.headers['Authorization'], 'Bearer t');
      return http.Response.bytes(utf8.encode('%PDF-1.4'), 200, headers: {'content-type': 'application/pdf'});
    });
    final api = ApiClient(baseUrl: 'http://x', httpClient: client)..setToken('t');
    final bytes = await api.getBytes('/api/payments/p1/receipt');
    expect(utf8.decode(bytes.take(4).toList()), '%PDF');
  });

  test('logout clears the token', () async {
    final client = MockClient((_) async => http.Response(jsonEncode({'token': 't', 'expiresInSeconds': 1, 'me': _me()}), 200));
    final tokens = MemoryTokenStore();
    final session = Session(api: ApiClient(baseUrl: 'http://x', httpClient: client), tokens: tokens);
    await session.login('o@x.az', 'Owner#Passw0rd');
    await session.logout();
    expect(session.isSignedIn, isFalse);
    expect(await tokens.read(), isNull);
  });
}
