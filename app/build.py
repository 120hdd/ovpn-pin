"""Build Relay into a folder that is ready to run.

    python app/build.py

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

import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DIST = os.path.join(ROOT, 'dist')
WORK = os.path.join(ROOT, 'build')
NAME = 'Relay'

# Where the .ovpn files come from, in the order the app itself looks.
SERVER_SOURCES = ('servers', 'success', 'pinned')


def find_servers():
    for name in SERVER_SOURCES:
        path = os.path.join(ROOT, name)
        try:
            if any(f.endswith('.ovpn') for f in os.listdir(path)):
                return path
        except OSError:
            continue
    return None


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


def main():
    if os.name != 'nt':
        raise SystemExit('This builds a Windows app, so it has to run on Windows.')

    if os.path.abspath(os.getcwd()).startswith(os.path.abspath(DIST)):
        raise SystemExit(
            'Standing inside dist/ stops it being deleted, and the build then\n'
            'fails on a permission error. Run this from somewhere else.')

    for path in (DIST, WORK):
        shutil.rmtree(path, ignore_errors=True)

    sep = ';'          # PyInstaller's --add-data separator on Windows
    args = [
        sys.executable, '-m', 'PyInstaller',
        '--noconfirm', '--clean',
        '--onedir', '--noconsole',
        '--name', NAME,
        '--distpath', DIST,
        '--workpath', WORK,
        '--specpath', WORK,
        # The page, its stylesheet, its script and the vendored search.
        '--add-data', f'{os.path.join(HERE, "ui")}{sep}ui',
        '--add-data', f'{os.path.join(HERE, "assets")}{sep}assets',
        # The proxy lives in core/ in the repo and at the top of the bundle
        # once frozen; paths.py knows the difference.
        '--add-data', f'{os.path.join(ROOT, "core", "ovpn-proxy.py")}{sep}.',
        '--hidden-import', 'winproxy',
        '--hidden-import', 'windscribe',
        '--hidden-import', 'engine',
        '--hidden-import', 'countries',
        '--hidden-import', 'paths',
        '--hidden-import', 'sweep',
        '--hidden-import', 'pin',
        # pystray is imported inside a function so a machine without it still
        # runs. PyInstaller only follows imports it can see statically, so
        # without these the tray silently does not exist in the built app -
        # which is exactly how the first build shipped.
        '--hidden-import', 'pystray',
        '--hidden-import', 'pystray._win32',
        os.path.join(HERE, 'main.py'),
    ]

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

    servers = find_servers()
    if servers:
        target = os.path.join(out, 'servers')
        os.makedirs(target, exist_ok=True)
        n = 0
        for f in os.listdir(servers):
            if f.endswith('.ovpn'):
                shutil.copy2(os.path.join(servers, f), os.path.join(target, f))
                n += 1
        print(f'servers: {n} copied from {os.path.basename(servers)}/')
    else:
        print('servers: NONE FOUND - the app will have nothing to connect to')

    # Somewhere obvious to put the files you download from the provider, and
    # whatever is already in it. Made rather than explained: "Pin them to
    # real addresses" reads this folder, and a first run whose answer is the
    # name of a folder that does not exist is a step nobody can follow - one
    # that exists and is empty is only half a step better, because the
    # section then opens saying it has nothing to do.
    inbox = os.path.join(out, 'configs')
    os.makedirs(inbox, exist_ok=True)
    source = os.path.join(ROOT, 'configs')
    n = 0
    try:
        for f in os.listdir(source):
            if f.endswith('.ovpn'):
                shutil.copy2(os.path.join(source, f), os.path.join(inbox, f))
                n += 1
    except OSError:
        pass
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

    # Who each address is rented from. Already paid for - one HTTP request per
    # hundred addresses, with a deliberate wait between them - and without it
    # the "one per company" groupings come out as zero and the chips are an
    # empty row, which reads as broken rather than as unasked.
    owners = os.path.join(ROOT, '.state', 'owners.tsv')
    if os.path.isfile(owners):
        state = os.path.join(out, '.state')
        os.makedirs(state, exist_ok=True)
        shutil.copy2(owners, os.path.join(state, 'owners.tsv'))
        print('landlords: copied')
    else:
        print('landlords: none on record - the app can look them up itself')

    auth = os.path.join(ROOT, '.ovpn-auth')
    if os.path.isfile(auth):
        shutil.copy2(auth, os.path.join(out, '.ovpn-auth'))
        print('credentials: copied')
    else:
        print('credentials: .ovpn-auth NOT FOUND - the app cannot connect')

    # -- remove the decoy --------------------------------------------------

    # PyInstaller leaves its own Relay.exe in the working folder. It looks
    # identical and does not work, having no _internal beside it. Better that
    # it does not exist.
    shutil.rmtree(WORK, ignore_errors=True)

    link = make_shortcut(exe, os.path.join(os.path.expanduser('~'), 'Desktop'),
                         NAME, shortcut_icon)

    print()
    print('done.')
    print(f'   app       {exe}')
    if link:
        print(f'   shortcut  {link}')
    print()
    print(f'   check it   {NAME}.exe --selftest')


if __name__ == '__main__':
    main()
