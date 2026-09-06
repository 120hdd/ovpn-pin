"""The two questions the app gets wrong quietly.

    python app/engine-test.py

Neither of these is about the network, and neither shows up as an error
message that means what it says - which is why they are worth a file.

  Whether a process is still running. Windows keeps a process object alive
  for as long as anybody holds a handle to it, and this app holds one on
  every proxy it starts. Asking `OpenProcess` therefore answers "yes, still
  running" about a process that was force-killed half an hour ago - so the
  dead proxy's record survived, and the next connect was refused by its own
  worker with "a proxy is already up on port 8877", naming a pid that no
  longer existed. The app connected exactly once per run and then said "the
  connection opened but did not come up" forever.

  Which exits belong to whom. One folder can hold both providers, only the
  filename says which, and getting it wrong means opening a Windscribe exit
  with a Surfshark credential - which does not fail loudly, it just refuses
  everything.
"""

import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import accounts                                              # noqa: E402
import engine                                                # noqa: E402
import windscribe as ws                                      # noqa: E402

px = engine.px
PASS, FAIL = [], []


def check(name, ok, detail=''):
    (PASS if ok else FAIL).append(name)
    print(f'  [{" ok " if ok else "FAIL"}] {name}{"  " + detail if detail else ""}')
    return ok


def section(title):
    print(f'\n{title}')


def test_alive():
    section('whether a process is really running')
    check('this process is alive', px.alive(os.getpid()))
    check('a pid that never existed is not', not px.alive(999999))
    check('nothing is not', not px.alive(0) and not px.alive(None))

    # The whole bug, reproduced: start one, kill it, and keep the handle -
    # which is exactly what the engine does with the proxies it spawns.
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'],
                             creationflags=0x08 | 0x200 if os.name == 'nt' else 0)
    try:
        check('a running child is alive', px.alive(child.pid))
        px.kill(child.pid)
        time.sleep(1.2)
        # Before the fix this said True, because `child` is still held here.
        check('a killed child is NOT alive while its handle is still held',
              not px.alive(child.pid),
              'this is the one that made the app connect only once')
        child.poll()
        check('and still not after it is reaped', not px.alive(child.pid))
    finally:
        try:
            child.kill()
        except Exception:
            pass


def test_state_file_forgets_the_dead():
    section('the record of what is running')
    was = px.STATE_PATH
    with tempfile.TemporaryDirectory() as tmp:
        px.STATE_PATH = os.path.join(tmp, 'proxy.state')
        try:
            px.put_states([{'pid': 999999, 'host': '127.0.0.1', 'port': '8877',
                            'ip': '1.2.3.4', 'name': 'gone.example.com',
                            'since': '2026-01-01 00:00:00'}])
            check('a record for a dead pid is written',
                  os.path.isfile(px.STATE_PATH))
            check('but reading leaves it out', px.read_states() == [])
            check('and nothing is claimed for that port',
                  px.read_state(8877) is None)
            # Which is what makes writing back what was read the cleanup.
            px.put_states(px.read_states())
            check('writing back what was read removes the file',
                  not os.path.isfile(px.STATE_PATH))
        finally:
            px.STATE_PATH = was


class _NoProxy:
    def engage(self, *a, **k):
        return None

    def restore(self, *a, **k):
        return None


def test_missing_credential_is_quiet():
    section('a credential that is not there')
    import contextlib
    import io as _io

    with tempfile.TemporaryDirectory() as tmp:
        e = engine.Engine(_NoProxy(), folder=tmp)
        e.auth_file = os.path.join(tmp, 'nothing-here')

        buf = _io.StringIO()
        with contextlib.redirect_stdout(buf):
            try:
                e.credentials()
                said = 'it returned something'
            except RuntimeError as exc:
                said = str(exc)
            name = e.username()
        noise = buf.getvalue()

        check('it says no-credentials', said == 'no-credentials', said)
        check('and username is None rather than a crash', name is None)
        # The point. px.read_auth prints four lines about the Surfshark
        # manual-setup page and exits, which is right for a command line and
        # wrong behind a window: the caller catches it and carries on, having
        # already printed [fail] where somebody can read it and conclude that
        # something broke.
        check('and nothing at all is printed', noise == '',
              repr(noise[:60]) if noise else '')

        # A file that exists but is not two lines is the same answer.
        half = os.path.join(tmp, 'half')
        with open(half, 'w', encoding='utf-8') as f:
            f.write('only-a-username\n')
        e.auth_file = half
        buf = _io.StringIO()
        with contextlib.redirect_stdout(buf):
            try:
                e.credentials()
                said = 'it returned something'
            except RuntimeError as exc:
                said = str(exc)
        check('half a credentials file is no credentials',
              said == 'no-credentials', said)
        check('and that is quiet too', buf.getvalue() == '')

        # And a real one still reads, blank lines and all.
        good = os.path.join(tmp, 'good')
        with open(good, 'w', encoding='utf-8') as f:
            f.write('\nuser-here\n\npass-here\n\n')
        e.auth_file = good
        check('a real one comes back', e.credentials() == ('user-here', 'pass-here'),
              str(e.credentials()))


def test_provider_routing():
    section('which exits belong to whom')
    with tempfile.TemporaryDirectory() as tmp:
        for name, body in (
            ('de-fra.prod.surfshark.com_tcp_1.2.3.4.ovpn', '1.2.3.4'),
            ('nl-ams.prod.surfshark.com_tcp_1.2.3.5.ovpn', '1.2.3.5'),
            ('at-vie' + ws.MARK + 'at-007.totallyacdn.com_1.2.3.6.ovpn', '1.2.3.6'),
        ):
            with open(os.path.join(tmp, name), 'w', encoding='utf-8') as f:
                f.write(f'# host -> {body}\nclient\nremote {body} 443\n')

        e = engine.Engine(_NoProxy(), folder=tmp)
        got = {s.file: s.provider for s in e.servers()}
        check('three configs read', len(got) == 3, str(len(got)))
        check('the Windscribe one is known by its name',
              [p for f, p in got.items() if ws.MARK in f] == [accounts.WINDSCRIBE])
        check('and the others are not',
              sorted(p for f, p in got.items() if ws.MARK not in f)
              == [accounts.SURFSHARK, accounts.SURFSHARK])

        e.providers = {accounts.WINDSCRIBE}
        check('the filter narrows to one provider', len(e.servers()) == 1)
        e.providers = {accounts.SURFSHARK}
        check('and to the other', len(e.servers()) == 2)
        e.providers = {accounts.SURFSHARK, accounts.WINDSCRIBE}
        check('and back to both', len(e.servers()) == 3)
        e.providers = None
        check('None means all of them', len(e.servers()) == 3)

        # The reason any of it matters.
        ws_one = next(s for s in e.servers() if ws.MARK in s.file)
        ss_one = next(s for s in e.servers() if ws.MARK not in s.file)
        check('a Windscribe exit is opened with the Windscribe credential',
              e.auth_file_for(ws_one) == ws.AUTH_FILE)
        check('and a Surfshark one with the other',
              e.auth_file_for(ss_one) == e.auth_file)

        cat = e.catalogue()
        by = {c['code']: c['by'] for c in cat}
        check('the catalogue counts each provider separately',
              by.get('at') == {accounts.WINDSCRIBE: 1}
              and by.get('de') == {accounts.SURFSHARK: 1}, str(by))


def test_two_folders():
    section('reading more than one folder')
    with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
        one = 'de-fra.prod.surfshark.com_tcp_1.2.3.4.ovpn'
        two = 'at-vie' + ws.MARK + 'at-007.totallyacdn.com_1.2.3.6.ovpn'
        for folder, name in ((a, one), (b, two)):
            with open(os.path.join(folder, name), 'w', encoding='utf-8') as f:
                f.write('# h -> 1.2.3.4\nclient\nremote 1.2.3.4 443\n')
        # And the same config in both, which must not count twice.
        with open(os.path.join(b, one), 'w', encoding='utf-8') as f:
            f.write('# h -> 1.2.3.4\nclient\nremote 1.2.3.4 443\n')

        e = engine.Engine(_NoProxy(), folder=a)
        check('one folder sees one', len(e.servers()) == 1)
        e.folders = [a, b]
        check('two folders see both providers', len(e.servers()) == 2,
              str(sorted(s.provider for s in e.servers())))
        check('and the config in both folders is counted once',
              sum(1 for s in e.servers() if s.file == one) == 1)
        check('the primary folder wins for a repeated name',
              next(s for s in e.servers() if s.file == one).path.startswith(a))


def main():
    print(__doc__.strip().splitlines()[0])
    test_alive()
    test_state_file_forgets_the_dead()
    test_missing_credential_is_quiet()
    test_provider_routing()
    test_two_folders()
    print(f'\n{len(PASS)} passed, {len(FAIL)} failed')
    for name in FAIL:
        print(f'  failed: {name}')
    return 1 if FAIL else 0


if __name__ == '__main__':
    raise SystemExit(main())
