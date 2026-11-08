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

  test('a 401 on any request after login signs the user out', () async {
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
    await Future<void>.delayed(Duration.zero);
    expect(calls, 2);
    expect(session.isSignedIn, isFalse);
    expect(await tokens.read(), isNull);
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
