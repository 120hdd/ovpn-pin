"""Build Relay into a folder that is ready to run.

    python app/build.py
    python app/build.py --public-release

Three things this does beyond calling PyInstaller, each because leaving it
out produced a real mistake:

  It copies the servers and the credentials in. A dist folder that builds
  successfully and then cannot connect because the .ovpn files are somewhere
  else is not a build, it is homework.

  It deletes the working folder afterwards. PyInstaller leaves an
  intermediate Relay.exe in there which looks exactly like the real one,
  has no _internal beside it, and fails instantly when double-clicked. Two
  identical-looking exes where one is broken is a trap; the fix is to have
  one exe.

  It puts a shortcut on the Desktop, because "which of these folders was it"
  is not a question the person using this should ever have to answer.


Why --onedir rather than --onefile
----------------------------------

Measured, not assumed. Same app: onedir starts in about 1.4 s warm, onefile
in about 2.9 s. But the number that decides it is the FIRST run after a
download, where onefile spends around 35 seconds unpacking itself while
Defender reads every byte - with no window, no cursor change and no
explanation. That is indistinguishable from broken, and by then the person
has double-clicked it four more times. onedir also draws fewer antivirus
heuristics, since nothing is extracted at run time.

--noconsole so no black window sits behind the app. Note that this leaves
sys.stdout as None, which is why the proxy worker redirects its own output
before it does anything else.
"""

import ast
import ipaddress
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PUBLIC_RELEASE = '--public-release' in sys.argv
BUILD_ROOT = os.path.join(ROOT, 'release-build') if PUBLIC_RELEASE else ROOT
DIST = os.path.join(BUILD_ROOT, 'dist')
WORK = os.path.join(BUILD_ROOT, 'build')
NAME = 'Relay'
STARTERS = os.path.join(HERE, 'public-starters.json')

# The stash the run in progress is holding, so the failure path at the
# bottom of this file can hand it back. Module scope because main() is
# where it is taken and __main__ is where a crash is caught.
_KEPT = None

# Where the .ovpn files come from, in the order the app itself looks.
SERVER_SOURCES = ('servers', 'success', 'pinned')

# Legacy portable builds kept user data beside the exe. New frozen builds
# import it into LocalAppData on first run, but a personal rebuild must still
# preserve any old files under dist/ until that migration has happened.
# PyInstaller deletes dist/ whole on every run, which used to take the tunnel
# domain, accounts roster and Windscribe session with it.
# Surfshark looked like it survived, but only because .ovpn-auth happens to
# be copied back out of the repo below; nothing else had a copy anywhere.
#
# Deliberately a list rather than "everything in .state". A copy that was
# killed leaves proxy.state, proxy.log and system-proxy-before.json behind,
# and those describe a process and a set of registry values from a copy that
# no longer exists - carried forward, a fresh build inherits the last one's
# wreckage and offers to "restore" Windows proxy settings nobody set.
#
# The folders are on the list for the same reason as the files, and it is the
# more expensive half: pinned/ is minutes of resolving and knocking on
# addresses, success/ is an hour of sweeping, and neither is written by
# anything but the person using the app. A build deleted them and the app
# came back up saying "none pinned" beside a provider that had been pinned an
# hour earlier - which is exactly the report this list exists to answer.
KEEP = (
    '.state/settings.json',        # the port, the pick, and the tunnel domain
    '.state/accounts.json',        # the roster, sealed to this Windows account
    '.state/windscribe.json',      # a session that cost a captcha to get
    '.state/windscribe-meta.json',
    '.state/surfshark-meta.json',
    '.state/reach.json',           # what the last timing run measured
    '.state/owners.tsv',           # fallback only, as with .ovpn-auth below
    '.state/tunnel',               # which CDN addresses answered, and when
    '.windscribe-auth',            # what opens Windscribe's exits
    '.ovpn-auth',                  # fallback only - see restore_user_data
    '.state/dropped.json',         # where each set-aside exit came from
    'pinned',                      # what a pin run resolved - minutes of it
    'success',                     # and what a sweep measured
    'dropped',                     # taken out by hand, and still restorable -
                                   # a rebuild that lost these would make the
                                   # undo the one button that does not work
    'windscribe',                  # the fetched fleet, waiting to be pinned
    'configs',                     # whatever was dropped in the inbox by hand
    'servers',                     # the shipped set, plus anything added to it
)


def _count(path):
    """Files at or under this path, so a merge can report what it added."""
    if os.path.isfile(path):
        return 1
    return sum(len(files) for _, _, files in os.walk(path)) \
        if os.path.isdir(path) else 0


def _lift(src, dst):
    """Copy one entry across, merging rather than replacing a folder.

    All-or-nothing per path was wrong for the folders the build fills as
    well: it creates configs/ and servers/ out of the repo, so a whole
    carried folder was skipped as "already there" and everything the person
    had added to it went anyway. File by file, and whatever the build wrote
    wins, so both halves survive.
    """
    if os.path.isdir(src):
        for root, _, files in os.walk(src):
            here = os.path.join(dst, os.path.relpath(root, src)) \
                if root != src else dst
            os.makedirs(here, exist_ok=True)
            for f in files:
                target = os.path.join(here, f)
                if not os.path.exists(target):
                    shutil.copy2(os.path.join(root, f), target)
        return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if not os.path.exists(dst):
        shutil.copy2(src, dst)


def app_is_running():
    """Whether the built app is up, which decides whether this can start.

    Windows will not let a running executable be opened for writing, and that
    is a sharper test than a name in the process list: it answers about this
    exact file rather than about anything called Relay.exe. Renaming is not
    the test - Windows allows a running exe to be renamed, so that answered
    "free" every time and was worse than no check at all.

    It matters because the failure is silent and expensive. rmtree is passed
    ignore_errors, so a build started with the app open deletes everything it
    can, leaves the one file it cannot, and carries on into PyInstaller - and
    what it deleted is the servers, the credentials and the pinned exits.
    """
    exe = os.path.join(DIST, NAME, f'{NAME}.exe')
    if not os.path.isfile(exe):
        return False
    try:
        open(exe, 'r+b').close()
        return False
    except OSError:
        return True


def stash_user_data():
    """Take the last build's own files somewhere the delete cannot reach."""
    out = os.path.join(DIST, NAME)
    if not os.path.isdir(out):
        return None
    keep = tempfile.mkdtemp(prefix='relay-keep-')
    saved = 0
    for rel in KEEP:
        src = os.path.join(out, rel.replace('/', os.sep))
        if os.path.exists(src):
            _lift(src, os.path.join(keep, rel.replace('/', os.sep)))
            saved += 1
    if not saved:
        shutil.rmtree(keep, ignore_errors=True)
        return None
    return keep


def restore_user_data(keep, out):
    """Put them back, without overwriting what this build has just written.

    The build copies a fresh .ovpn-auth and owners.tsv out of the repo, and
    those are the newer answer. So anything already in place wins and this is
    only the fallback - which is what makes it safe to keep .ovpn-auth on the
    list at all.
    """
    if not keep:
        return
    put = []
    for rel in KEEP:
        src = os.path.join(keep, rel.replace('/', os.sep))
        dst = os.path.join(out, rel.replace('/', os.sep))
        if not os.path.exists(src):
            continue
        before = _count(dst)
        _lift(src, dst)
        gained = _count(dst) - before
        if gained:
            put.append(f'{rel} ({gained})' if gained > 1 else rel)
    shutil.rmtree(keep, ignore_errors=True)
    print('carried over: ' + (', '.join(put) if put else 'nothing to carry'))


def find_servers():
    for name in SERVER_SOURCES:
        path = os.path.join(ROOT, name)
        try:
            if any(f.endswith('.ovpn') for f in os.listdir(path)):
                return path
        except OSError:
            continue
    return None


def public_configs():
    """Only configs committed to git may enter a public build.

    A normal build deliberately includes whatever its owner has dropped into
    configs/.  That is useful on their machine and unsafe in a downloadable
    artifact, where an untracked config can contain a private endpoint or
    embedded key.  Refusing to guess when git is unavailable keeps the public
    mode fail-closed.
    """
    try:
        found = subprocess.run(
            ['git', '-C', ROOT, 'ls-files', '--', 'configs/*.ovpn'],
            capture_output=True, check=True, text=True, timeout=30).stdout
    except Exception as exc:
        raise RuntimeError(
            'A public release must be built from a git checkout so only '
            'tracked configs can be included.') from exc
    return [os.path.join(ROOT, line.strip().replace('/', os.sep))
            for line in found.splitlines() if line.strip()]


def dynamic_imports(path):
    """Imports PyInstaller cannot see inside a source file loaded at runtime.

    engine.py loads core/ovpn-proxy.py from its filename because the dash means
    it is not an importable module name.  PyInstaller therefore treats it as
    data and never walks its imports.  Deriving the hidden-import list from the
    file keeps a later standard-library import from producing another EXE that
    builds successfully and crashes on launch.
    """
    with open(path, encoding='utf-8') as handle:
        tree = ast.parse(handle.read(), filename=path)
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found.update(alias.name for alias in node.names)
        elif (isinstance(node, ast.ImportFrom) and node.level == 0
              and node.module):
            found.add(node.module)
    return sorted(found)


def _tracked(path):
    """Require a public-build input to be committed, not merely on disk."""
    rel = os.path.relpath(path, ROOT).replace(os.sep, '/')
    try:
        subprocess.run(
            ['git', '-C', ROOT, 'ls-files', '--error-unmatch', '--', rel],
            capture_output=True, check=True, text=True, timeout=30)
    except Exception as exc:
        raise RuntimeError(
            f'Public release input is not tracked by git: {rel}') from exc


def public_starters():
    """Read and validate the small, credential-free first-run server set."""
    _tracked(STARTERS)
    with open(STARTERS, encoding='utf-8') as handle:
        entries = json.load(handle)
    if not isinstance(entries, list) or not entries:
        raise RuntimeError('public-starters.json must contain a non-empty list')

    out, names = [], set()
    providers = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise RuntimeError('Every public starter must be an object')
        if set(entry) != {'provider', 'name', 'hostname', 'address', 'port'}:
            raise RuntimeError('A public starter has missing or unexpected fields')
        provider = entry['provider']
        name = entry['name']
        hostname = entry['hostname']
        address = entry['address']
        port = entry['port']
        try:
            ip = ipaddress.ip_address(address)
        except ValueError as exc:
            raise RuntimeError(f'Invalid starter address: {address}') from exc
        if ip.version != 4 or not ip.is_global:
            raise RuntimeError(f'Starter address must be a public IPv4: {address}')
        if provider == 'surfshark':
            if not hostname.endswith('.prod.surfshark.com') or port != 1443:
                raise RuntimeError(f'Invalid Surfshark starter: {name}')
            source = os.path.join(ROOT, 'configs', f'{hostname}_tcp.ovpn')
            _tracked(source)
            entry = dict(entry, source=source)
        elif provider == 'windscribe':
            if not hostname.endswith('.totallyacdn.com') or port != 443:
                raise RuntimeError(f'Invalid Windscribe starter: {name}')
        else:
            raise RuntimeError(f'Unknown starter provider: {provider}')
        if (not re.fullmatch(r'[a-z]{2}-[a-z]{3}', name)
                or name in names):
            raise RuntimeError(f'Invalid or duplicate starter name: {name}')
        names.add(name)
        providers.add(provider)
        out.append(entry)
    if providers != {'surfshark', 'windscribe'}:
        raise RuntimeError('Public starters must include both providers')
    return out


def write_public_starters(target):
    """Render the reviewed manifest into the bundle's ready-to-use servers/."""
    os.makedirs(target, exist_ok=True)
    written = []
    for entry in public_starters():
        host = entry['hostname']
        address = entry['address']
        port = entry['port']
        if entry['provider'] == 'surfshark':
            with open(entry['source'], encoding='utf-8') as handle:
                text = handle.read()
            pattern = rf'(?m)^(\s*remote\s+){re.escape(host)}(\s+{port}\s*)$'
            text, changed = re.subn(pattern, rf'\g<1>{address}\g<2>', text)
            if changed != 1:
                raise RuntimeError(
                    f'Expected one matching remote line in {entry["source"]}')
            filename = f'{entry["name"]}.prod.surfshark.com_tcp_{address}.ovpn'
        else:
            text = ('# Windscribe starter pinned by the Relay release build.\n'
                    f'# {host} -> {address}\n'
                    '# Used by Relay\'s HTTPS proxy path; not an OpenVPN profile.\n'
                    'client\n'
                    'dev tun\n'
                    'proto tcp\n'
                    f'remote {address} {port}\n')
            filename = f'{entry["name"]}.ws.{host}_{address}.ovpn'
        header = ('# Public starter: contains no account credentials.\n'
                  f'# Refresh source: {host}\n')
        path = os.path.join(target, filename)
        with open(path, 'w', encoding='utf-8', newline='\n') as handle:
            handle.write(header + text)
        written.append(path)
    return written


def _safe_public_server(path):
    """Reject credential-bearing constructs in a rendered starter config."""
    with open(path, encoding='utf-8') as handle:
        text = handle.read()
    if re.search(r'(?im)^\s*<(?:auth-user-pass|key|cert)>', text):
        return False
    for line in text.splitlines():
        words = line.strip().split()
        if words and words[0].lower() == 'auth-user-pass' and len(words) > 1:
            return False
        if words and words[0].lower() in {'username', 'password'}:
            return False
    return True


def version_string():
    """Product version used both in the PE metadata and by SignPath policy."""
    value = os.environ.get('RELAY_VERSION', '0.0.0-dev').strip()
    return value[1:] if value.lower().startswith('v') else value


def write_version_file():
    """Create PyInstaller version metadata from the release tag."""
    version = version_string()
    numbers = [int(n) for n in re.findall(r'\d+', version)[:4]]
    numbers += [0] * (4 - len(numbers))
    fixed = tuple(numbers)
    path = os.path.join(WORK, 'relay-version.txt')
    os.makedirs(WORK, exist_ok=True)
    body = f"""VSVersionInfo(
  ffi=FixedFileInfo(filevers={fixed!r}, prodvers={fixed!r}, mask=0x3f,
    flags=0x0, OS=0x40004, fileType=0x1, subtype=0x0, date=(0, 0)),
  kids=[StringFileInfo([StringTable('040904B0', [
    StringStruct('CompanyName', 'Relay contributors'),
    StringStruct('FileDescription', 'Relay desktop proxy'),
    StringStruct('FileVersion', '{version}'),
    StringStruct('InternalName', 'Relay'),
    StringStruct('OriginalFilename', 'Relay.exe'),
    StringStruct('ProductName', 'Relay'),
    StringStruct('ProductVersion', '{version}')])]),
    VarFileInfo([VarStruct('Translation', [1033, 1200])])])
"""
    with open(path, 'w', encoding='utf-8', newline='\n') as handle:
        handle.write(body)
    return path


def audit_public_output(out):
    """Stop before packaging if user data somehow crossed into the build."""
    forbidden = (
        '.env', '.ovpn-auth', '.windscribe-auth', '.state', 'accounts.json',
        'settings.json', 'windscribe.json', 'windscribe-meta.json',
        'surfshark-meta.json', 'owners.tsv', 'reach.json', 'dropped.json',
        'pinned', 'success', 'dropped', 'windscribe',
    )
    found = []
    for root, dirs, files in os.walk(out):
        rel_root = os.path.relpath(root, out)
        parts = [] if rel_root == '.' else rel_root.split(os.sep)
        for name in dirs + files:
            rel = os.path.join(*(parts + [name])) if parts else name
            if name in forbidden or rel in forbidden:
                found.append(rel)
    if found:
        raise RuntimeError(
            'Public build contains private/runtime data: ' + ', '.join(found))
    server_dir = os.path.join(out, 'servers')
    expected = {os.path.basename(path) for path in
                write_public_starters(os.path.join(out, '.starter-audit'))}
    shutil.rmtree(os.path.join(out, '.starter-audit'), ignore_errors=True)
    actual = set(os.listdir(server_dir)) if os.path.isdir(server_dir) else set()
    if actual != expected:
        raise RuntimeError('Public starter set differs from the reviewed manifest')
    unsafe = [name for name in actual
              if not _safe_public_server(os.path.join(server_dir, name))]
    if unsafe:
        raise RuntimeError('Public starter contains credentials: ' + ', '.join(unsafe))


def make_shortcut(target, folder, name, icon=None):
    """A .lnk via PowerShell, so there is nothing to install to make one."""
    link = os.path.join(folder, f'{name}.lnk')
    commands = [
        "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('"
        + link.replace("'", "''") + "');",
        "$s.TargetPath = '" + target.replace("'", "''") + "';",
        "$s.WorkingDirectory = '"
        + os.path.dirname(target).replace("'", "''") + "';",
    ]
    if icon:
        commands.append("$s.IconLocation = '"
                        + icon.replace("'", "''") + ",0';")
    commands.append("$s.Save()")
    script = ''.join(commands)
    try:
        subprocess.run(['powershell', '-NoProfile', '-Command', script],
                       capture_output=True, check=True, timeout=30)
        return link
    except Exception:
        return None


def stop_tunnel_client():
    """End a gost this app started, so dist/ can be deleted.

    It outlives the window on purpose - a connection should not drop because
    somebody closed a settings sheet - which also means it outlives the copy
    of the app that started it. Found by which process holds the port rather
    than by name: someone else's gost is not ours to kill.
    """
    if os.name != 'nt':
        return
    try:
        out = subprocess.run(['netstat', '-ano'], capture_output=True,
                             text=True, timeout=10).stdout
    except Exception:
        return
    for port in (9090, 9091, 9092):
        for line in out.splitlines():
            bits = line.split()
            if (len(bits) >= 5 and bits[0] == 'TCP' and bits[3] == 'LISTENING'
                    and bits[1].endswith(f':{port}')):
                try:
                    subprocess.run(['taskkill', '/PID', bits[4], '/F'],
                                   capture_output=True, timeout=10)
                    print(f'tunnel client on {port}: stopped so dist/ can go')
                except Exception:
                    pass
                break


def main():
    if os.name != 'nt':
        raise SystemExit('This builds a Windows app, so it has to run on Windows.')

    if os.path.abspath(os.getcwd()).startswith(os.path.abspath(DIST)):
        raise SystemExit(
            'Standing inside dist/ stops it being deleted, and the build then\n'
            'fails on a permission error. Run this from somewhere else.')

    # The tunnel client the last build's app started is still running, and
    # its log sits inside dist/. PyInstaller cleans that folder itself and
    # fails on the open handle with a permission error naming a file nobody
    # would connect to a build - so it is said here, where it can be acted on.
    # Before anything is touched. A build started while the app is open takes
    # dist/ apart, cannot remove the one file it is really after, and fails
    # somewhere later with a message about PyInstaller - by which point the
    # servers, the credentials and every pinned exit are gone.
    if not PUBLIC_RELEASE and app_is_running():
        raise SystemExit(
            'Relay is running, and a build deletes the folder it is running\n'
            'from. Quit it from its tray icon first, then build.')

    if not PUBLIC_RELEASE:
        stop_tunnel_client()

    # Out of the way before the delete, back in after it. Everything the last
    # build's app was told - the account, the tunnel domain, the session -
    # lives in the folder that is about to go.
    kept = None if PUBLIC_RELEASE else stash_user_data()
    global _KEPT
    _KEPT = kept

    for path in (DIST, WORK):
        shutil.rmtree(path, ignore_errors=True)
    version_file = write_version_file()

    sep = ';'          # PyInstaller's --add-data separator on Windows
    proxy_source = os.path.join(ROOT, 'core', 'ovpn-proxy.py')
    args = [
        sys.executable, '-m', 'PyInstaller',
        '--noconfirm', '--clean',
        '--onedir', '--noconsole',
        '--name', NAME,
        '--version-file', version_file,
        '--distpath', DIST,
        '--workpath', WORK,
        '--specpath', WORK,
        # The page, its stylesheet, its script and the vendored search.
        '--add-data', f'{os.path.join(HERE, "ui")}{sep}ui',
        '--add-data', f'{os.path.join(HERE, "assets")}{sep}assets',
        # The proxy lives in core/ in the repo and at the top of the bundle
        # once frozen; paths.py knows the difference.
        '--add-data', f'{proxy_source}{sep}.',
        '--hidden-import', 'winproxy',
        '--hidden-import', 'windscribe',
        '--hidden-import', 'engine',
        '--hidden-import', 'countries',
        '--hidden-import', 'paths',
        '--hidden-import', 'sweep',
        '--hidden-import', 'pin',
        # Named like the rest of them. main.py imports it, but every
        # local module here is declared rather than discovered, and one
        # that is not is a build that runs and a window that cannot
        # find its own tunnel.
        '--hidden-import', 'tunnel',
        # pystray is imported inside a function so a machine without it still
        # runs. PyInstaller only follows imports it can see statically, so
        # without these the tray silently does not exist in the built app -
        # which is exactly how the first build shipped.
        '--hidden-import', 'pystray',
        '--hidden-import', 'pystray._win32',
        os.path.join(HERE, 'main.py'),
    ]

    # ovpn-proxy.py is loaded dynamically from the bundled data path, so its
    # imports have to be made explicit to PyInstaller.  This includes difflib,
    # whose absence made the v0.2.0 executable fail before the window opened.
    for module in dynamic_imports(proxy_source):
        args[-1:-1] = ['--hidden-import', module]

    icon = os.path.join(HERE, 'assets', 'app.ico')
    if os.path.isfile(icon):
        args[-1:-1] = ['--icon', icon]

    print('building...', flush=True)
    subprocess.run(args, check=True)

    out = os.path.join(DIST, NAME)
    exe = os.path.join(out, f'{NAME}.exe')
    shortcut_icon = None
    if os.path.isfile(icon):
        shortcut_icon = os.path.join(out, f'{NAME}.ico')
        shutil.copy2(icon, shortcut_icon)

    # -- make it actually runnable ----------------------------------------

    if PUBLIC_RELEASE:
        target = os.path.join(out, 'servers')
        server_files = write_public_starters(target)
        print(f'servers: {len(server_files)} public starters rendered '
              '(Surfshark + Windscribe)')
    else:
        servers = find_servers()
    if not PUBLIC_RELEASE and servers:
        target = os.path.join(out, 'servers')
        os.makedirs(target, exist_ok=True)
        n = 0
        for f in os.listdir(servers):
            if f.endswith('.ovpn'):
                shutil.copy2(os.path.join(servers, f), os.path.join(target, f))
                n += 1
        print(f'servers: {n} copied from {os.path.basename(servers)}/')
    elif not PUBLIC_RELEASE:
        print('servers: NONE FOUND - the app will have nothing to connect to')

    # Somewhere obvious to put the files you download from the provider, and
    # whatever is already in it. Made rather than explained: "Pin them to
    # real addresses" reads this folder, and a first run whose answer is the
    # name of a folder that does not exist is a step nobody can follow - one
    # that exists and is empty is only half a step better, because the
    # section then opens saying it has nothing to do.
    inbox = os.path.join(out, 'configs')
    os.makedirs(inbox, exist_ok=True)
    n = 0
    if PUBLIC_RELEASE:
        config_files = public_configs()
    else:
        source = os.path.join(ROOT, 'configs')
        try:
            config_files = [os.path.join(source, f) for f in os.listdir(source)
                            if f.endswith('.ovpn')]
        except OSError:
            config_files = []
    for src in config_files:
        shutil.copy2(src, os.path.join(inbox, os.path.basename(src)))
        n += 1
    if n:
        print(f'inbox: {n} unpinned config(s) copied into configs/')
    else:
        print('inbox: configs/ made, empty - drop .ovpn files in it to pin them')

    # The two PowerShell files behind "Time the servers properly", and the
    # second of them is also the whole of "Pin them to real addresses". They are
    # copied rather than bundled: PyInstaller would put them inside _internal,
    # and Sweep-OvpnExits.ps1 dot-sources its neighbour by looking beside
    # itself and writes results into folders beside itself too - both of which
    # have to be where the person can see them, not inside a bundle.
    swept = 0
    for name in ('Sweep-OvpnExits.ps1', 'Resolve-OvpnRemote.ps1'):
        src = os.path.join(ROOT, 'windows', name)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(out, name))
            swept += 1
    if swept == 2:
        print('sweep scripts: copied')
    else:
        print('sweep scripts: MISSING - the app can connect, but "Time the '
              'servers properly" and "Pin them to real addresses" will both '
              'say they have nothing to run')

    # The tunnel client, and the script that sets up the far end of it.
    # Copied beside the exe for the same reason the PowerShell files are:
    # paths.gost_exe() looks in DATA_DIR, which frozen is here and not inside
    # _internal. Without this the app builds, runs, shows the whole tunnel
    # pane and then says it cannot find a client - which is a worse failure
    # than not offering the feature at all.
    tunnelled = 0
    for name in ('gost.exe', 'install-server.sh'):
        src = os.path.join(ROOT, 'tunnel', name)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(out, name))
            tunnelled += 1
    if tunnelled == 2:
        print('tunnel: client and installer copied')
    else:
        print('tunnel: MISSING - the app will connect through the provider, '
              'but "Your own tunnel" will say it has no client to run. Put '
              'gost.exe and install-server.sh in tunnel/ and build again.')

    # Who each address is rented from. Already paid for - one HTTP request per
    # hundred addresses, with a deliberate wait between them - and without it
    # the "one per company" groupings come out as zero and the chips are an
    # empty row, which reads as broken rather than as unasked.
    owners = ('' if PUBLIC_RELEASE
              else os.path.join(ROOT, '.state', 'owners.tsv'))
    if os.path.isfile(owners):
        state = os.path.join(out, '.state')
        os.makedirs(state, exist_ok=True)
        shutil.copy2(owners, os.path.join(state, 'owners.tsv'))
        print('landlords: copied')
    else:
        print('landlords: none on record - the app can look them up itself')

    auth = '' if PUBLIC_RELEASE else os.path.join(ROOT, '.ovpn-auth')
    if os.path.isfile(auth):
        shutil.copy2(auth, os.path.join(out, '.ovpn-auth'))
        print('credentials: copied')
    elif PUBLIC_RELEASE:
        print('credentials: intentionally omitted from the public build')
    else:
        print('credentials: .ovpn-auth NOT FOUND - the app cannot connect')

    # Last, so that everything the repo had to give is already in place and
    # this only fills the gaps it left.
    restore_user_data(kept, out)
    _KEPT = None                 # handed back; the failure path is done

    # -- remove the decoy --------------------------------------------------

    # PyInstaller leaves its own Relay.exe in the working folder. It looks
    # identical and does not work, having no _internal beside it. Better that
    # it does not exist.
    shutil.rmtree(WORK, ignore_errors=True)

    if PUBLIC_RELEASE:
        shutil.copy2(os.path.join(HERE, 'README.md'),
                     os.path.join(out, 'README.md'))
        audit_public_output(out)
        link = None
    else:
        link = make_shortcut(
            exe, os.path.join(os.path.expanduser('~'), 'Desktop'),
            NAME, shortcut_icon)

    print()
    print('done.')
    print(f'   app       {exe}')
    if link:
        print(f'   shortcut  {link}')
    if PUBLIC_RELEASE:
        print('   mode      public release (no credentials or user state)')
    print()
    print(f'   check it   {NAME}.exe --selftest')


if __name__ == '__main__':
    try:
        main()
    except BaseException:
        # A build that falls over after the delete has already taken dist/
        # apart, and the only copy of what was in it is a temp folder nobody
        # has been told about. That is not hypothetical: a build started with
        # the app still running deleted 352 pinned exits, the credentials and
        # the accounts roster, failed on the one file it could not remove,
        # and left all of it under %TEMP% where the person who lost it had no
        # reason to look. app_is_running() stops that particular way in; this
        # is for every other way, including Ctrl-C.
        if _KEPT:
            print()
            print('build failed - putting your files back.')
            restore_user_data(_KEPT, os.path.join(DIST, NAME))
        raise
