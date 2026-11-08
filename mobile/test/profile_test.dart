import 'dart:convert';

import 'package:borcdefteri_mobile/api/api_client.dart';
import 'package:borcdefteri_mobile/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test('Profile and Me carry phone and avatar version', () {
    final p = Profile.fromJson({
      'email': 'o@x.az', 'fullName': 'Nigar', 'phone': '+994501112233', 'platformRole': 'USER',
      'role': 'STORE_OWNER', 'storeName': 'Market', 'storePhone': null, 'storeAddress': 'Bakı',
      'hasAvatar': true, 'avatarVersion': 1762650000000,
    });
    expect(p.isOwner, isTrue);
    expect(p.avatarVersion, 1762650000000);
    final me = Me.fromJson({
      'userId': 'u', 'email': 'o@x.az', 'fullName': 'Nigar', 'platformRole': 'USER', 'role': 'EMPLOYEE',
      'storeName': 'Market', 'permissions': [], 'subscription': null, 'hasAvatar': false,
    });
    expect(me.hasAvatar, isFalse);
    expect(me.avatarVersion, isNull);
  });

  test('profile picture is uploaded as multipart with the bearer token', () async {
    late http.BaseRequest seen;
    final client = MockClient.streaming((req, body) async {
      seen = req;
      final bytes = await body.toBytes();
      expect(utf8.decode(bytes, allowMalformed: true), contains('filename="avatar.png"'));
      return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode({'email': 'o@x.az', 'fullName': 'N', 'hasAvatar': true, 'avatarVersion': 5}))),
          200);
    });
    final api = ApiClient(baseUrl: 'http://x', httpClient: client)..setToken('t');
    final res = await api.upload('/api/profile/avatar', 'file', [0x89, 0x50, 0x4E, 0x47], 'avatar.png', 'image/png')
        as Map<String, dynamic>;
    expect(seen.method, 'POST');
    expect(seen.url.path, '/api/profile/avatar');
    expect(seen.headers['Authorization'], 'Bearer t');
    expect(res['avatarVersion'], 5);
  });
}
