"""Build اتصال into a folder that is ready to run.

    python app/build.py

Three things this does beyond calling PyInstaller, each because leaving it
out produced a real mistake:

  It copies the servers and the credentials in. A dist folder that builds
  successfully and then cannot connect because the .ovpn files are somewhere
  else is not a build, it is homework.

  It deletes the working folder afterwards. PyInstaller leaves an
  intermediate Ettesal.exe in there which looks exactly like the real one,
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
NAME = 'Ettesal'

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


def make_shortcut(target, folder, name):
    """A .lnk via PowerShell, so there is nothing to install to make one."""
    link = os.path.join(folder, f'{name}.lnk')
    script = (
        "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('"
        + link.replace("'", "''") + "');"
        "$s.TargetPath = '" + target.replace("'", "''") + "';"
        "$s.WorkingDirectory = '" + os.path.dirname(target).replace("'", "''") + "';"
        "$s.Save()")
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
        # The page, its stylesheet, its script, the two typefaces, the licences.
        '--add-data', f'{os.path.join(HERE, "ui")}{sep}ui',
        # The proxy is a sibling of app/ in the repo and a child of the bundle
        # once frozen; paths.py knows the difference.
        '--add-data', f'{os.path.join(ROOT, "ovpn-proxy.py")}{sep}.',
        '--hidden-import', 'winproxy',
        '--hidden-import', 'engine',
        '--hidden-import', 'countries',
        '--hidden-import', 'paths',
        os.path.join(HERE, 'main.py'),
    ]

    icon = os.path.join(HERE, 'ui', 'app.ico')
    if os.path.isfile(icon):
        args[-1:-1] = ['--icon', icon]

    print('building...', flush=True)
    subprocess.run(args, check=True)

    out = os.path.join(DIST, NAME)
    exe = os.path.join(out, f'{NAME}.exe')

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

    auth = os.path.join(ROOT, '.ovpn-auth')
    if os.path.isfile(auth):
        shutil.copy2(auth, os.path.join(out, '.ovpn-auth'))
        print('credentials: copied')
    else:
        print('credentials: .ovpn-auth NOT FOUND - the app cannot connect')

    # -- remove the decoy --------------------------------------------------

    # PyInstaller leaves its own Ettesal.exe in the working folder. It looks
    # identical and does not work, having no _internal beside it. Better that
    # it does not exist.
    shutil.rmtree(WORK, ignore_errors=True)

    link = make_shortcut(exe, os.path.join(os.path.expanduser('~'), 'Desktop'),
                         'Ettesal')

    print()
    print('done.')
    print(f'   app       {exe}')
    if link:
        print(f'   shortcut  {link}')
    print()
    print('   check it   Ettesal.exe --selftest')


if __name__ == '__main__':
    main()
