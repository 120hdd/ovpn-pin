import 'dart:async';

import 'package:flutter/services.dart';

/// What the window knows about the tunnel at one moment.
///
/// Everything the Kotlin side publishes, flattened - no nested maps, no
/// optional shapes. A state that can only be read one way is a state two
/// screens cannot disagree about.
class RelayState {
  const RelayState({
    this.connected = false,
    this.exit = '',
    this.exitName = '',
    this.status = 'not connected',
    this.asked = 0,
    this.total = 0,
    this.tookMs = 0,
  });

  final bool connected;
  final String exit;
  final String exitName;
  final String status;
  final int asked;
  final int total;
  final int tookMs;

  /// True while a race is under way: something is happening, and it has a
  /// number attached. Told apart from connected and from idle because it is
  /// the only one of the three that can show progress.
  bool get working => !connected && total > 0 && status.startsWith(RegExp('asked|racing'));

  bool get failed => status.startsWith('failed');

  factory RelayState.from(Map<Object?, Object?> m) => RelayState(
        connected: m['connected'] as bool? ?? false,
        exit: m['exit'] as String? ?? '',
        exitName: m['exitName'] as String? ?? '',
        status: m['status'] as String? ?? '',
        asked: m['asked'] as int? ?? 0,
        total: m['total'] as int? ?? 0,
        tookMs: m['tookMs'] as int? ?? 0,
      );
}

/// Where the configs have to be, and whether they are there.
class RelayPaths {
  const RelayPaths({
    required this.pinnedDir,
    required this.authFile,
    required this.configCount,
    required this.hasAuth,
  });

  final String pinnedDir;
  final String authFile;
  final int configCount;
  final bool hasAuth;

  bool get ready => configCount > 0 && hasAuth;

  factory RelayPaths.from(Map<Object?, Object?> m) => RelayPaths(
        pinnedDir: m['pinnedDir'] as String? ?? '',
        authFile: m['authFile'] as String? ?? '',
        configCount: m['configCount'] as int? ?? 0,
        hasAuth: m['hasAuth'] as bool? ?? false,
      );
}

/// The Dart end of the bridge.
///
/// Two channels, for two kinds of traffic: [connect] and friends are asked and
/// answered; [states] is pushed. The split is why progress can arrive eight
/// times a second without anything polling for it.
class Relay {
  static const _control = MethodChannel('relay/control');
  static const _status = EventChannel('relay/status');

  static Stream<RelayState> get states => _status
      .receiveBroadcastStream()
      .map((e) => RelayState.from(e as Map<Object?, Object?>));

  static Future<RelayState> state() async {
    final m = await _control.invokeMethod<Map<Object?, Object?>>('state');
    return RelayState.from(m ?? const {});
  }

  static Future<RelayPaths> paths() async {
    final m = await _control.invokeMethod<Map<Object?, Object?>>('paths');
    return RelayPaths.from(m ?? const {});
  }

  /// Returns null when the tunnel is starting, or the reason it will not.
  /// A string rather than an exception: "no configs" is something for the
  /// window to explain, not something that went wrong.
  static Future<String?> connect() => _control.invokeMethod<String>('connect');

  static Future<void> disconnect() => _control.invokeMethod<void>('disconnect');
}
