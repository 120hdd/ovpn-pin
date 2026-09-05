// The window is a thin thing: it draws whatever RelayState says and calls one
// method. So this tests the part with a decision in it - which of the three
// states a given RelayState is - rather than pumping widgets to find out that
// Flutter can lay out a column.
import 'package:flutter_test/flutter_test.dart';
import 'package:relay/relay.dart';

void main() {
  test('an idle state is neither working nor failed', () {
    const s = RelayState();
    expect(s.connected, isFalse);
    expect(s.working, isFalse);
    expect(s.failed, isFalse);
  });

  test('a race in progress reads as working', () {
    const s = RelayState(status: 'asked 8 of 147', asked: 8, total: 147);
    expect(s.working, isTrue);
    expect(s.failed, isFalse);
  });

  test('a connected state is not working, whatever the counters say', () {
    const s = RelayState(
        connected: true, status: 'connected via x', asked: 8, total: 147);
    expect(s.working, isFalse);
  });

  test('a failure reads as failed rather than as working', () {
    const s = RelayState(status: 'failed: no proxy for this account', total: 147);
    expect(s.failed, isTrue);
    expect(s.working, isFalse);
  });

  test('paths are only ready with both halves', () {
    const nothing = RelayPaths(
        pinnedDir: '/a', authFile: '/b', configCount: 0, hasAuth: false);
    const configsOnly = RelayPaths(
        pinnedDir: '/a', authFile: '/b', configCount: 147, hasAuth: false);
    const both = RelayPaths(
        pinnedDir: '/a', authFile: '/b', configCount: 147, hasAuth: true);
    expect(nothing.ready, isFalse);
    expect(configsOnly.ready, isFalse);
    expect(both.ready, isTrue);
  });
}
