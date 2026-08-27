#!/usr/bin/env python3
"""Feed the reader the lines Sweep-OvpnExits.ps1 really prints.

    python app/sweep-test.py

The window follows a sweep by reading its log, which means the two are joined
by nothing but the exact wording of a handful of lines. Every line below is
one of the script's own messages, filled in. If somebody rewrites one of them
this stops matching and says which - rather than a two-hour sweep running
behind a window that shows nothing and cannot say why.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sweep                                                 # noqa: E402


LOG = r"""
  Sweep (3 of 533 configs)
         Each one is connected for real, judged, and dropped again. That is the
         Reckon on 1 minutes, longer if a lot of them are dead.
         also testing: chatgpt.com, youtube.com

  Before connecting anything
         Cloudflare sees you as 5.6.7.8 in IR right now

         [1/3] de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn  146.70.160.213:443 tcp
  [ ok ] up in 4.2s - kept as 04.2s-de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn
  [ ok ] clean     exit 146.70.160.99 DE  (3 served) [6.1s]
         serves chatgpt.com - kept in sitetest\chatgpt-com\04.2s-de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn
         serves youtube.com - kept in sitetest\youtube-com\04.2s-de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn

         [2/3] jp-tok.prod.surfshark.com_tcp_146.70.211.107.ovpn  146.70.211.107:443 tcp
  [ ok ] up in 11.8s - kept as 11.8s-jp-tok.prod.surfshark.com_tcp_146.70.211.107.ovpn
  [warn] partly    exit 146.70.211.9 JP  (2 served, 1 refused: chatgpt.com challenged) [14.0s]
         no longer serves chatgpt.com - dropped from that folder
         serves youtube.com - kept in sitetest\youtube-com\11.8s-jp-tok.prod.surfshark.com_tcp_146.70.211.107.ovpn

         [3/3] mk-skp.prod.surfshark.com_tcp_217.9.244.83.ovpn  217.9.244.83:443 tcp
  [fail] did not come up - auth failed [15.2s]
"""

seen = []
s = sweep.Sweep()
s.state = 'running'
for line in LOG.splitlines():
    s._saw(line, lambda p: seen.append(p))

failed = []


def check(name, got, want):
    if got == want:
        print(f'  ok    {name}')
    else:
        print(f'  FAIL  {name}\n          got  {got!r}\n          want {want!r}')
        failed.append(name)


progress = [p for p in seen if p['phase'] == 'testing']
check('every config was counted', [(p['done'], p['total']) for p in progress],
      [(1, 3), (2, 3), (3, 3)])
check('names came through',
      [p['name'] for p in progress],
      ['de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn',
       'jp-tok.prod.surfshark.com_tcp_146.70.211.107.ovpn',
       'mk-skp.prod.surfshark.com_tcp_217.9.244.83.ovpn'])
check('addresses came through', [p['ip'] for p in progress],
      ['146.70.160.213', '146.70.211.107', '217.9.244.83'])

rows = s.results
check('one row per config', len(rows), 3)
check('outcomes', [r['outcome'] for r in rows], ['up', 'up', 'noconnect'])
check('handshake times', [r['seconds'] for r in rows], [4.2, 11.8, None])
check('verdicts', [r['verdict'] for r in rows], ['clean', 'partly', None])
check('exit addresses', [r['exit'] for r in rows],
      ['146.70.160.99', '146.70.211.9', None])
check('why the third failed', rows[2]['detail'], 'auth failed')

check('first server served both sites',
      [(x['host'], x['served']) for x in rows[0]['sites']],
      [('chatgpt.com', True), ('youtube.com', True)])
check('second served one and was refused the other',
      [(x['host'], x['served']) for x in rows[1]['sites']],
      [('chatgpt.com', False), ('youtube.com', True)])


#------------------------------------------------------- what counts as a site

print()
print('what is accepted in the sites field')

# The sweep's own check is `^[A-Za-z0-9._-]+$`, which accepts a bare word -
# and a bare word is probed as https://word/, fails, and is counted against
# the exit. So a typo would quietly mark every server dirty. This is stricter
# on purpose, and says what it threw away.
for typed, want_kept, want_dropped in [
        ('chatgpt.com, youtube.com', ['chatgpt.com', 'youtube.com'], []),
        ('https://chatgpt.com/  openai.com', ['chatgpt.com', 'openai.com'], []),
        ('HTTPS://Www.Reddit.com/r/x?a=1', ['www.reddit.com'], []),
        ('chatgpt.com,chatgpt.com', ['chatgpt.com'], []),
        ('example.com:8443', ['example.com'], []),
        ('trailing.dot.com.', ['trailing.dot.com'], []),
        ('a.b.c.co.uk', ['a.b.c.co.uk'], []),
        ('not a host!!, ok.com', ['ok.com'], ['not', 'a', 'host!!']),
        ('localhost', [], ['localhost']),
        ('-bad-.com', [], ['-bad-.com']),
        ('', [], []),
]:
    kept, dropped = sweep.split_sites(typed)
    check(f'{typed!r}', (kept, dropped), (want_kept, want_dropped))


#--------------------------------------------- the folder names have to match

print()
print('folder names must match the ones the sweep writes')

# Get-NameTag, which is what names the folder inside sitetest\. If this drifts
# the window lists folders that were never written and misses the ones that
# were.
for host, want in [('chatgpt.com', 'chatgpt-com'),
                   ('www.reddit.com', 'www-reddit-com'),
                   ('a-very-long-hostname-that-goes-past-it.example.com',
                    'a-very-long-hostname-that-go')]:
    check(f'{host} -> {want}', sweep.name_tag(host), want)



#--------------------------------------------------- arguments, in one piece

print()
print('arguments survive the trip to an elevated PowerShell')

# The wrapper starts the sweep with Start-Process -ArgumentList, which joins
# an array with spaces and quotes nothing itself. A company called
# "M247 AS9009" and a servers folder under "Program Files" both have to come
# out the far end whole, and the failure is silent: the script would simply
# see different parameters than it was given.
import shutil                                                # noqa: E402
import subprocess                                            # noqa: E402
import tempfile                                              # noqa: E402

box = tempfile.mkdtemp(prefix='ovpn-argv-')
try:
    echo = os.path.join(box, 'echo args.ps1')       # a space in the path too
    with open(echo, 'w', encoding='utf-8') as f:
        f.write('foreach ($a in $args) { Write-Output $a }\n')

    passed = ['-PinnedDir', r'C:\Program Files\some servers',
              '-Landlord', 'M247 AS9009,Cyberzonehub AS209854',
              '-OnePerLandlord',
              '-Site', 'chatgpt.com,youtube.com',
              '-First', '20',
              '-Force']
    argv = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', echo] + passed

    out = os.path.join(box, 'out.txt')
    err = os.path.join(box, 'err.txt')
    subprocess.run(
        ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
         'Start-Process powershell -ArgumentList '
         + sweep._ps_array(argv)
         + ' -NoNewWindow -Wait -RedirectStandardOutput '
         + sweep._ps_string(out) + ' -RedirectStandardError '
         + sweep._ps_string(err)],
        capture_output=True, text=True, timeout=90)

    with open(out, encoding='utf-8', errors='replace') as f:
        got = [l.rstrip('\r') for l in f.read().splitlines() if l.strip()]
    check('each one arrived whole', got, passed)
except Exception as exc:                                      # noqa: BLE001
    print(f'  FAIL  could not run the argument check - {exc!r}')
    failed.append('argument check')
finally:
    shutil.rmtree(box, ignore_errors=True)


print()
print('failed: ' + ', '.join(failed) if failed else 'all pass')
raise SystemExit(1 if failed else 0)
