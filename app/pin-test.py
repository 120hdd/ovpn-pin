#!/usr/bin/env python3
"""Feed the reader the lines Resolve-OvpnRemote.ps1 really prints.

    python app/pin-test.py

Same bargain as sweep-test.py. The window follows a pinning run by reading
the resolver's output, so the two are joined by nothing but the exact wording
of eight lines. Every line below is one of the script's own messages, filled
in - captured from a real run, not invented here. If somebody rewrites one of
them this stops matching and says which, rather than a window that shows an
empty list while the folder quietly fills up.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pin                                                   # noqa: E402


OUT = r"""
  Resolve-OvpnRemote
  pins the remote line of an OpenVPN config to a real IP, over DoH

  Resolver
  --------
         using the proxy you named: http://127.0.0.1:10808
         provider: cloudflare (https://cloudflare-dns.com/dns-query)

  Configs (5)
  -----------
  [ ok ] ad-leu.prod.surfshark.com_tcp_62.197.152.133.ovpn  62.197.152.133:1443  reachable
  [ ok ] ad-leu.prod.surfshark.com_tcp_62.197.152.67.ovpn  62.197.152.67:1443  reachable
  [warn] ae-dub.prod.surfshark.com_tcp_146.70.102.205.ovpn  146.70.102.205:1443  NOT reachable
  [ ok ] al-tia.prod.surfshark.com_udp_31.171.154.67.ovpn  31.171.154.67:1194  (udp - not testable)
  [warn] de-fra.prod.surfshark.com: threw away 10.10.34.35 - not a public address
  [fail] de-fra.prod.surfshark.com_tcp.ovpn: could not resolve de-fra.prod.surfshark.com
  [warn] notes.ovpn: no remote line, skipped

  Done
  ----
  [ ok ] 4 file(s) written, 2 skipped
         in: C:\Users\ehsan\ovpn-pin\pinned
         The originals were not touched.
"""

seen = []
p = pin.Pin()
p.state = 'running'
p.out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'nowhere')
for line in OUT.splitlines():
    p._saw(line, lambda x: seen.append(x))

failed = []


def check(name, got, want):
    if got == want:
        print(f'  ok    {name}')
    else:
        print(f'  FAIL  {name}\n          got  {got!r}\n          want {want!r}')
        failed.append(name)


print('reading what the resolver says')

check('the proxy it used came through',
      [x['via'] for x in seen if x['phase'] == 'route'],
      ['http://127.0.0.1:10808'])
check('the folder count came through',
      [x['total'] for x in seen if x['phase'] == 'resolving'], [5])

rows = p.results
check('one row per file written, plus one per file skipped', len(rows), 6)
check('outcomes', [r['outcome'] for r in rows],
      ['reachable', 'reachable', 'unreachable', 'udp', 'skipped', 'skipped'])
check('addresses', [r['ip'] for r in rows],
      ['62.197.152.133', '62.197.152.67', '146.70.102.205', '31.171.154.67',
       None, None])
check('ports', [r['port'] for r in rows],
      [1443, 1443, 1443, 1194, None, None])

# Two addresses for one hostname is two files and one config's worth of
# progress. A bar that counted files would run past its own total.
check('two files from one config count once',
      [x['done'] for x in seen if x['phase'] == 'result'],
      [1, 1, 2, 3, 4, 5])
check('each file remembers what it was made from',
      [r['source'] for r in rows],
      ['ad-leu.prod.surfshark.com_tcp.ovpn',
       'ad-leu.prod.surfshark.com_tcp.ovpn',
       'ae-dub.prod.surfshark.com_tcp.ovpn',
       'al-tia.prod.surfshark.com_udp.ovpn',
       'de-fra.prod.surfshark.com_tcp.ovpn',
       'notes.ovpn'])
check('why the skipped ones were skipped',
      [r['detail'] for r in rows[4:]],
      ['could not resolve de-fra.prod.surfshark.com',
       'no remote line, skipped'])

# The censor being caught in the act. It is not a file and not a result, so
# it must not land in either.
check('the forged answer was reported and not counted',
      [(x['host'], x['addresses']) for x in seen if x['phase'] == 'forged'],
      [('de-fra.prod.surfshark.com', '10.10.34.35')])

p._finish(lambda x: seen.append(x))
done = seen[-1]
# read counts configs, not files: five went in, six rows came out, and the
# folder was finished either way.
check('the tally at the end',
      (done['written'], done['reachable'], done['unreachable'],
       done['skipped'], done['read'], done['total']),
      (4, 2, 1, 2, 5, 5))


#--------------------------------------------------- and when it will not run

print()
print('the one it refuses to resolve directly')

DIRECT_FAILED = r"""
  Resolve-OvpnRemote
  Resolver
  --------
  [fail] DoH did not answer directly, and -NoProxy says not to look for a proxy. Nothing was resolved. Start your proxy and name it with -Proxy http://127.0.0.1:PORT.
"""

q = pin.Pin()
q.state = 'running'
last = None
for line in DIRECT_FAILED.splitlines():
    said = q._saw(line, lambda x: None)
    if said:
        last = said
check('the reason survives as the error',
      last and last.startswith('DoH did not answer directly'), True)
check('nothing was counted as done', len(q.results), 0)


#------------------------------------------------------ the two answers, said

print()
print('the window always says which route, never lets the script choose')

for route, port, want in [('proxy', 10808, ['-Proxy', 'http://127.0.0.1:10808']),
                          ('proxy', 2080, ['-Proxy', 'http://127.0.0.1:2080']),
                          ('direct', 10808, ['-NoProxy'])]:
    argv = pin.Pin()._argv(r'C:\in', r'C:\out', route, port, 4, True)
    check(f'{route} {port}', [a for a in argv if a in ('-Proxy', '-NoProxy')
                              or a.startswith('http://')],
          want)

check('the reachability check can be turned off',
      '-NoTest' in pin.Pin()._argv(r'C:\in', r'C:\out', 'direct', 0, 4, False),
      True)
check('and is on by default',
      '-NoTest' in pin.Pin()._argv(r'C:\in', r'C:\out', 'direct', 0, 4, True),
      False)

# A path with a space in it goes to Popen as one element of a list, which is
# the whole reason this does not go through Start-Process the way the sweep
# has to. Worth pinning down anyway: it is the failure that is silent.
argv = pin.Pin()._argv(r'C:\Program Files\my configs', r'C:\out', 'direct',
                       0, 4, True)
check('a folder with a space in it stays one argument',
      argv[argv.index('-Path') + 1], r'C:\Program Files\my configs')


#------------------------------------------------------------- what is refused

print()
print('what it will not start')

box = os.path.dirname(os.path.abspath(__file__))
kinds = [b['kind'] for b in pin.Pin().blockers(box, box, 'direct', 0, quick=True)]
check('pinning a folder on top of itself', 'same-folder' in kinds, True)
kinds = [b['kind'] for b in pin.Pin().blockers(box, box + 'x', 'direct', 0,
                                               quick=True)]
check('a folder with no configs in it', 'no-configs' in kinds, True)


#------------------------------------------------------------------ the knobs

print()
print('the numbers are cleaned before they are used')

for typed, want in [('10808', 10808), (10808, 10808), ('', 10808),
                    ('0', 10808), ('70000', 10808), ('2080', 2080),
                    (' 7890 ', 7890), (None, 10808), ('abc', 10808)]:
    check(f'port {typed!r}', pin.clean_port(typed), want)
for typed, want in [(4, 4), ('2', 2), (0, 1), (99, 32), (None, 4), ('x', 4)]:
    check(f'addresses per config {typed!r}', pin.clean_max_ips(typed), want)


print()
print(', '.join(failed) + ' failed' if failed else 'all pass')
raise SystemExit(1 if failed else 0)
