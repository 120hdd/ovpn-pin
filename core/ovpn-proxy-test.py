#!/usr/bin/env python3
"""What the proxy does with the four shapes of client that reach it.

    python ovpn-proxy-test.py

No exit is involved and nothing goes on the wire. A fake upstream stands in
for Surfshark's HTTPS proxy and does the one thing a real one does that broke
this: demand Proxy-Authorization on EVERY request rather than only the first.

That is the whole reason this file exists. The proxy used to put the
credentials on the first request of a connection and then hand the rest over
as an untouched byte pipe, which is correct for a browser - HTTPS is one
CONNECT and then an opaque tunnel - and wrong for anything speaking plain
HTTP down a kept-alive connection. Telegram does exactly that, and what it
looked like from the outside was a connection that worked for a second, died,
came back, and died again, once a second, for as long as you watched it. The
browser on the same proxy was perfect the whole time, which is what made it
look like Telegram's problem.

A test rather than a note, because the fix lives in the middle of the
forwarding path and the failure is silent everywhere a browser can see.
"""

import importlib.util
import os
import socket
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))

spec = importlib.util.spec_from_file_location(
    'ovpn_proxy', os.path.join(HERE, 'ovpn-proxy.py'))
px = importlib.util.module_from_spec(spec)
spec.loader.exec_module(px)

asked = []          # (request line, did it carry our credentials?)
bodies = []


#--------------------------------------------------------------------- fakes

def upstream_conn(conn, refuse_connect=False, dawdle=0):
    """A proxy that authenticates every request, like every real one."""
    buf = b''
    while True:
        try:
            chunk = conn.recv(4096)
        except OSError:
            break
        if not chunk:
            break
        buf += chunk
        while b'\r\n\r\n' in buf:
            head, _, buf = buf.partition(b'\r\n\r\n')
            lines = head.split(b'\r\n')
            line = lines[0].decode('latin-1')
            authed = any(l.lower().startswith(b'proxy-authorization:')
                         for l in lines)
            asked.append((line, authed))

            if not authed:
                conn.sendall(b'HTTP/1.1 407 Proxy Authentication Required\r\n'
                             b'Content-Length: 0\r\n\r\n')
                continue

            if line.startswith('CONNECT'):
                if refuse_connect:
                    conn.sendall(b'HTTP/1.1 403 Forbidden\r\n'
                                 b'Content-Length: 0\r\n\r\n')
                    conn.close()
                    return
                conn.sendall(b'HTTP/1.1 200 Connection established\r\n\r\n')
                # Past the 200 this is a tunnel, so echo whatever arrives -
                # including anything the client pipelined behind the CONNECT,
                # which is the part that used to land in front of the parser.
                while True:
                    if buf:
                        conn.sendall(b'echo:' + buf)
                        buf = b''
                    try:
                        more = conn.recv(4096)
                    except OSError:
                        break
                    if not more:
                        break
                    buf = more
                conn.close()
                return

            size, chunked = 0, False
            for l in lines[1:]:
                name, _, value = l.partition(b':')
                name, value = name.strip().lower(), value.strip().lower()
                if name == b'content-length':
                    size = int(value)
                if name == b'transfer-encoding' and value not in (b'', b'identity'):
                    chunked = True

            if chunked:
                # Read the frames off, so that whatever follows the body is
                # seen as the next request rather than as garbage. The proxy
                # used to stop forwarding heads at this point and turn the
                # connection into a pipe, and the only way to notice is to
                # have an upstream here that keeps parsing.
                got = b''
                while True:
                    while b'\r\n' not in buf:
                        more = conn.recv(4096)
                        if not more:
                            break
                        buf += more
                    if b'\r\n' not in buf:
                        break
                    line_, _, buf = buf.partition(b'\r\n')
                    n = int(line_.split(b';')[0].strip(), 16)
                    while len(buf) < n + 2:
                        more = conn.recv(4096)
                        if not more:
                            break
                        buf += more
                    got += buf[:n]
                    buf = buf[n + 2:]
                    if n == 0:
                        break
                bodies.append(got)
            else:
                while len(buf) < size:
                    more = conn.recv(4096)
                    if not more:
                        break
                    buf += more
                if size:
                    bodies.append(buf[:size])
                buf = buf[size:]
            # A server that holds the request open before answering, which is
            # what a chat client's long poll asks one to do.
            if dawdle:
                time.sleep(dawdle)
            conn.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok')
    conn.close()


def start_upstream(refuse_connect=False, dawdle=0):
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    s.listen(8)

    def loop():
        while True:
            conn, _ = s.accept()
            threading.Thread(target=upstream_conn,
                             args=(conn, refuse_connect, dawdle),
                             daemon=True).start()
    threading.Thread(target=loop, daemon=True).start()
    return s.getsockname()[1]


def start_proxy(up_port, exit_timeout=5):
    """The real serve_one, with the TLS-to-the-exit part stubbed out. Only
    Exit.connect is replaced: everything the test exercises is the shipped
    code.

    exit_timeout stands in for the 20 seconds create_connection really puts on
    that socket - the budget for reaching a server, which is not the budget a
    connection that has been opened should be held to.
    """
    class FakeExit(px.Exit):
        def __init__(self):
            px.Exit.__init__(self, '127.0.0.1', up_port, 'fake', 'u', 'p')

        def connect(self):
            return socket.create_connection(('127.0.0.1', up_port),
                                            timeout=exit_timeout)

    exit_ = FakeExit()
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    s.listen(8)

    def loop():
        while True:
            conn, who = s.accept()
            # The source port too, exactly as serve() hands it over - it is
            # what the ledger looks the calling program up by, and a harness
            # that dropped it would test a path the real one does not take.
            threading.Thread(target=px.serve_one,
                             args=(conn, exit_, True, who[1]),
                             daemon=True).start()
    threading.Thread(target=loop, daemon=True).start()
    return s.getsockname()[1]


#------------------------------------------------------------------ checking

failed = []


def check(name, got, want):
    if got == want:
        print(f'  ok    {name}')
        return
    print(f'  FAIL  {name}')
    print(f'          got  {got!r}')
    print(f'          want {want!r}')
    failed.append(name)


def first_line(raw):
    return raw.split(b'\r\n', 1)[0].decode('latin-1', 'replace') if raw \
        else '<the proxy closed the connection>'


#--------------------------------------------------------------------- tests

def test_keepalive():
    print('\nthree requests down one kept-alive connection - what Telegram does')
    asked.clear()
    port = start_proxy(start_upstream())
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    replies = []
    for _ in range(3):
        c.sendall(b'POST http://149.154.167.51/api HTTP/1.1\r\n'
                  b'Host: 149.154.167.51\r\nContent-Length: 0\r\n'
                  b'Connection: keep-alive\r\n\r\n')
        replies.append(first_line(c.recv(200)))
    check('every reply is 200', replies, ['HTTP/1.1 200 OK'] * 3)
    check('every request carried our credentials', [a for _, a in asked],
          [True] * 3)
    check('one connection to the exit served all three', len(asked), 3)
    c.close()


def test_body():
    print('\na request with a body, and another behind it')
    asked.clear()
    bodies.clear()
    port = start_proxy(start_upstream())
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'POST http://x/api HTTP/1.1\r\nHost: x\r\n'
              b'Content-Length: 11\r\n\r\nhello world')
    first = first_line(c.recv(200))
    c.sendall(b'GET http://x/next HTTP/1.1\r\nHost: x\r\n\r\n')
    second = first_line(c.recv(200))
    check('the body arrived whole', bodies, [b'hello world'])
    check('both were answered', [first, second], ['HTTP/1.1 200 OK'] * 2)
    check('the one after the body was authorised too',
          [a for _, a in asked], [True, True])
    c.close()


def test_pipelining():
    print('\ntwo requests in one write, before either is answered')
    asked.clear()
    port = start_proxy(start_upstream())
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'GET http://x/a HTTP/1.1\r\nHost: x\r\n\r\n'
              b'GET http://x/b HTTP/1.1\r\nHost: x\r\n\r\n')
    time.sleep(0.4)
    got = c.recv(4096)
    check('both answered', got.count(b'200 OK'), 2)
    check('both authorised', [a for _, a in asked], [True, True])
    check('and in the order they were sent',
          [l.split(' ')[1] for l, _ in asked], ['http://x/a', 'http://x/b'])
    c.close()


def test_connect():
    print('\nCONNECT, with the client pipelining bytes behind it')
    port = start_proxy(start_upstream())
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'CONNECT example.com:443 HTTP/1.1\r\n'
              b'Host: example.com:443\r\n\r\nEARLY')
    time.sleep(0.4)
    got = c.recv(4096)
    check('the 200 reaches the client', got.startswith(b'HTTP/1.1 200'), True)
    check('the early bytes went into the tunnel rather than in front of it',
          b'echo:EARLY' in got, True)
    c.sendall(b'later')
    time.sleep(0.3)
    check('and the tunnel carries on', c.recv(4096), b'echo:later')
    c.close()


def test_connect_refused():
    print('\nCONNECT the exit refuses')
    port = start_proxy(start_upstream(refuse_connect=True))
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'CONNECT example.com:443 HTTP/1.1\r\n'
              b'Host: example.com:443\r\n\r\n')
    time.sleep(0.3)
    check('the refusal is passed on rather than swallowed',
          first_line(c.recv(4096)), 'HTTP/1.1 403 Forbidden')
    c.close()


def test_long_poll():
    print('\nan exit that takes longer to answer than it took to reach')
    # The socket to the exit arrives with create_connection's timeout on it -
    # the budget for finding a server, not for waiting on one that has been
    # found. Held to that, a client long-polling between messages is cut off
    # every time the far end pauses, which is most of what a chat client does.
    # One second here stands in for the real twenty.
    port = start_proxy(start_upstream(dawdle=2.5), exit_timeout=1)
    c = socket.create_connection(('127.0.0.1', port), timeout=10)
    c.settimeout(10)
    c.sendall(b'GET http://x/poll HTTP/1.1\r\nHost: x\r\n\r\n')
    check('the answer still arrives', first_line(c.recv(200)), 'HTTP/1.1 200 OK')
    c.close()


def test_dead_exit():
    print('\nnothing listening at the exit')
    dead = socket.socket()
    dead.bind(('127.0.0.1', 0))
    dead_port = dead.getsockname()[1]
    dead.close()
    port = start_proxy(dead_port)
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'GET http://x/ HTTP/1.1\r\nHost: x\r\n\r\n')
    check('answered 502 rather than hanging', first_line(c.recv(200)),
          'HTTP/1.1 502 Bad Gateway')
    c.close()


def test_chunked():
    print('\na chunked request, and the connection carrying on after it')
    # The same failure this whole file is about, reached by a different door.
    # A body with no Content-Length used to make the proxy give up on parsing
    # and hand the rest of the connection over as a pipe - so every request
    # after this one went out with no credentials and came back 407, which is
    # the once-a-second death all over again for any client that ever sends
    # one.
    asked.clear()
    bodies.clear()
    port = start_proxy(start_upstream())
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'POST http://x/api HTTP/1.1\r\nHost: x\r\n'
              b'Transfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n'
              b'5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n')
    first = first_line(c.recv(200))
    c.sendall(b'GET http://x/after HTTP/1.1\r\nHost: x\r\n\r\n')
    second = first_line(c.recv(200))
    c.sendall(b'GET http://x/again HTTP/1.1\r\nHost: x\r\n\r\n')
    third = first_line(c.recv(200))
    check('the chunked body arrived whole', bodies, [b'hello world'])
    check('all three were answered', [first, second, third],
          ['HTTP/1.1 200 OK'] * 3)
    check('the ones after the chunked body were authorised too',
          [a for _, a in asked], [True, True, True])
    c.close()


def test_socks5():
    print('\na SOCKS5 client - what Telegram sends when it is not on HTTP')
    # tdesktop sets useTcp = (proxyType != Type::Http), so its HTTP setting
    # turns MTProto-over-TCP off entirely and sends a request per message
    # instead. SOCKS5 is the only way to ask it for a tunnel, and the exit
    # still sees nothing but the CONNECT it always saw.
    asked.clear()
    port = start_proxy(start_upstream())
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'\x05\x01\x00')
    check('no authentication is asked for', c.recv(2), b'\x05\x00')
    # CONNECT x:443, as a name, so the exit is the one that resolves it
    c.sendall(b'\x05\x01\x00\x03' + bytes([1]) + b'x' + (443).to_bytes(2, 'big'))
    check('the tunnel is granted', c.recv(10)[:2], b'\x05\x00')
    check('and it left as a CONNECT, authorised',
          asked, [('CONNECT x:443 HTTP/1.1', True)])
    # Past the reply it is a pipe, and the fake upstream echoes.
    c.sendall(b'ping')
    check('bytes cross the tunnel', c.recv(200), b'echo:ping')
    c.close()


def test_socks5_refused():
    print('\na SOCKS5 client when the exit will not have it')
    asked.clear()
    port = start_proxy(start_upstream(refuse_connect=True))
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'\x05\x01\x00')
    c.recv(2)
    c.sendall(b'\x05\x01\x00\x03' + bytes([1]) + b'x' + (443).to_bytes(2, 'big'))
    # 0x05 is "connection refused" - said in SOCKS5's own words rather than
    # by dropping the connection, which a client cannot tell from a crash.
    check('refused in a way the client understands', c.recv(10)[:2], b'\x05\x05')
    c.close()


def test_meter():
    print('\nthe meter under a tunnel')
    # Reset rather than read as a delta: everything above ran through the
    # same module-level counter, and a test that only checked it went up
    # would pass on a meter that counted the same byte twice.
    px.METER['up'] = px.METER['down'] = 0
    port = start_proxy(start_upstream())
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'CONNECT example.com:443 HTTP/1.1\r\n'
              b'Host: example.com:443\r\n\r\n')
    time.sleep(0.4)
    c.recv(4096)
    # Past the 200 the tunnel is opaque and every byte in it is countable.
    # The head and the exit's answer are not counted - they are the proxy
    # talking to the exit on its own account, not traffic being carried.
    c.sendall(b'x' * 4000)
    time.sleep(0.4)
    back = c.recv(65536)
    check('what went out is counted, and only once',
          px.METER['up'], 4000)
    # The upstream stub answers b'echo:' + what it was sent.
    check('and what came back is counted the other way',
          px.METER['down'], len(back))
    check('the two directions are told apart',
          px.METER['down'] == 4005 and px.METER['up'] == 4000, True)
    c.close()

    # And the reading a window would actually read.
    stop = threading.Event()
    threading.Thread(target=px.meter_writer, args=(port, stop),
                     daemon=True).start()
    time.sleep(1.3)
    got = px.read_traffic(port)
    stop.set()
    check('the file says what the counter says',
          got and (got['up'], got['down']) == (4000, 4005), True)
    check('and says which process is claiming it',
          got and got['pid'] == os.getpid(), True)
    px.clear_traffic(port)
    check('and goes when the proxy does', px.read_traffic(port), None)


def test_ledger():
    print('\nthe ledger: where it went, and who asked')
    px.METER['up'] = px.METER['down'] = 0
    px.LEDGER.rows.clear()
    port = start_proxy(start_upstream())

    # A tunnel, and plain HTTP to somewhere else down a second connection.
    c = socket.create_connection(('127.0.0.1', port), timeout=5)
    c.settimeout(5)
    c.sendall(b'CONNECT news.example.com:443 HTTP/1.1\r\n'
              b'Host: news.example.com:443\r\n\r\n')
    time.sleep(0.4)
    c.recv(4096)
    c.sendall(b'x' * 3000)
    time.sleep(0.3)
    c.recv(65536)

    d = socket.create_connection(('127.0.0.1', port), timeout=5)
    d.settimeout(5)
    d.sendall(b'GET http://plain.example.org/a HTTP/1.1\r\n'
              b'Host: plain.example.org\r\n\r\n')
    time.sleep(0.4)
    d.recv(65536)

    rows, total = px.LEDGER.snapshot(20)
    by = {r['host']: r for r in rows}
    check('both destinations are listed, and only those',
          sorted(by), ['news.example.com', 'plain.example.org'])
    check('the port is not part of the name', total, 2)
    check('the tunnel is credited what crossed it',
          by['news.example.com']['up'], 3000)
    check('and the plain request the other way',
          by['plain.example.org']['down'] > 0, True)
    check('the ledger adds up to the meter',
          sum(r['up'] + r['down'] for r in rows),
          px.METER['up'] + px.METER['down'])

    # This test opened both connections, so the program it names is this one.
    # Only on Windows: nothing else has a table to look the port up in.
    if os.name == 'nt':
        check('and names the program that opened it',
              by['news.example.com']['app'], os.path.basename(sys.executable))
        check('with its pid', by['news.example.com']['pid'], os.getpid())

    check('open connections are marked live',
          by['news.example.com']['live'], 1)
    c.close()
    d.close()
    time.sleep(0.5)
    after = {r['host']: r for r in px.LEDGER.snapshot(20)[0]}
    # The tunnel only. A kept-alive plain-HTTP connection stays live until
    # the exit lets go of its end too - serve_http waits on the response pump
    # after the client has gone - and while that socket is open the row
    # saying so is the truth, not a leak.
    check('and stop being once they close',
          after['news.example.com']['live'], 0)
    check('but what they moved is still on the books',
          after['news.example.com']['up'], 3000)

    # The file the window reads.
    px.write_hosts(port, *px.LEDGER.snapshot(20), time.time())
    got = px.read_hosts(port)
    check('the file carries the rows', len(got['rows']), 2)
    check('and says whose they are', got['pid'], os.getpid())
    px.clear_hosts(port)
    check('and goes when the proxy does', px.read_hosts(port), None)


def main():
    for test in (test_keepalive, test_body, test_pipelining, test_connect,
                 test_connect_refused, test_long_poll, test_dead_exit,
                 test_chunked, test_socks5, test_socks5_refused, test_meter,
                 test_ledger):
        test()
    print()
    if failed:
        print('failed: ' + ', '.join(failed))
        print()
        return 1
    print('all pass')
    print()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
