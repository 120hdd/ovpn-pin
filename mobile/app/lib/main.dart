import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'bridge.dart';
import 'theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const RelayApp());
}

class RelayApp extends StatelessWidget {
  const RelayApp({super.key});

  @override
  Widget build(BuildContext context) {
    SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Tone.bg,
      systemNavigationBarIconBrightness: Brightness.light,
    ));
    return MaterialApp(
      title: 'Relay',
      debugShowCheckedModeBanner: false,
      theme: relayTheme(),
      home: const Window(),
    );
  }
}

/// The whole window: `app/ui/index.html`, running unchanged.
///
/// There is no Dart layout here on purpose. The design lives in one place -
/// the desktop's - and this carries it rather than imitating it. What Flutter
/// contributes is the frame, the channel to the core, and the black behind the
/// page while it loads.
class Window extends StatefulWidget {
  const Window({super.key});

  @override
  State<Window> createState() => _WindowState();
}

class _WindowState extends State<Window> {
  static const _control = MethodChannel('relay/control');
  static const _status = EventChannel('relay/status');

  late final WebViewController _web;
  bool _ready = false;
  String? _broke;

  @override
  void initState() {
    super.initState();

    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      // The page paints its own background; leaving this transparent would
      // show white for the frame between attach and first paint.
      ..setBackgroundColor(Tone.bg)
      ..addJavaScriptChannel('Relay', onMessageReceived: _fromPage)
      ..setNavigationDelegate(NavigationDelegate(
        // Injected on every load rather than once: a reload would otherwise
        // leave the page with an api object whose promises never settle.
        onPageStarted: (_) => _web.runJavaScript(bridgeScript),
        onPageFinished: (_) {
          if (mounted) setState(() => _ready = true);
        },
        onWebResourceError: (e) {
          // Only the main document failing is worth a screen of its own. A
          // missing flag is a missing flag.
          if (e.isForMainFrame ?? false) {
            if (mounted) setState(() => _broke = e.description);
          }
        },
      ))
      ..loadFlutterAsset('assets/ui/index.html');

    _status.receiveBroadcastStream().listen(_fromCore);
  }

  /// One call from the page. The name and arguments go straight through to
  /// Kotlin; whatever comes back settles the promise the page is holding.
  Future<void> _fromPage(JavaScriptMessage message) async {
    Map<String, dynamic> call;
    try {
      call = jsonDecode(message.message) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    final id = call['id'];
    final name = call['name'] as String? ?? '';
    final args = (call['args'] as List?) ?? const [];

    try {
      final json = await _control
          .invokeMethod<String>('call', {'name': name, 'args': args});
      await _replyRaw(id, json ?? 'null');
    } on PlatformException catch (e) {
      await _reply(id, false, e.message ?? e.code);
    } catch (e) {
      await _reply(id, false, '$e');
    }
  }

  /// The value is JSON text already, straight from Go. Spliced in rather than
  /// decoded and re-encoded, so a hundred kilobytes of country list is parsed
  /// once - by the page, which was going to parse it anyway.
  Future<void> _replyRaw(Object? id, String json) =>
      _web.runJavaScript('window.__relayReply($id, true, $json);');

  /// The failing half, where the payload is a sentence rather than a shape.
  Future<void> _reply(Object? id, bool ok, Object? payload) {
    final encoded = jsonEncode(payload);
    return _web.runJavaScript('window.__relayReply($id, $ok, $encoded);');
  }

  /// The core pushing rather than answering: progress, connected, failed.
  void _fromCore(dynamic event) {
    if (event is! Map) return;
    final name = event['event'] as String?;
    if (name == null) return;
    // Already JSON text, from Kotlin. Encoding it again would hand the page
    // a string where it expects an object.
    final payload = event['payload'] as String? ?? '{}';
    _web.runJavaScript(emitScript(name, payload));
  }

  @override
  Widget build(BuildContext context) {
    final broke = _broke;
    return Scaffold(
      backgroundColor: Tone.bg,
      body: SafeArea(
        child: Stack(
          children: [
            if (broke == null) WebViewWidget(controller: _web),
            if (broke != null) _Broken(reason: broke),
            // A plain fill rather than a spinner: the page has its own
            // entrance animation, and a spinner handing over to it reads as
            // two loads instead of one.
            if (!_ready && broke == null)
              const ColoredBox(color: Tone.bg, child: SizedBox.expand()),
          ],
        ),
      ),
    );
  }
}

class _Broken extends StatelessWidget {
  const _Broken({required this.reason});

  final String reason;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('the window did not load',
                style: Theme.of(context)
                    .textTheme
                    .titleMedium
                    ?.copyWith(color: Tone.fail)),
            const SizedBox(height: 12),
            Text(reason,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.labelSmall),
          ],
        ),
      ),
    );
  }
}
