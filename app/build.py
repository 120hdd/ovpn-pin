"""Build اتصال into a folder the user can double-click into.

    python app/build.py

--onedir rather than --onefile, and the difference is not a preference.
Measured on this machine with the same app: onedir starts in about 1.4 s,
onefile in about 2.9 s warm - but the number that decides it is the FIRST run
after a download, where onefile spends about 35 seconds unpacking itself into
a temp folder while Defender reads every byte. Thirty-five seconds with no
window, no cursor change and no explanation is indistinguishable from broken,
and the person on the other end has already double-clicked it four more
times. onedir also draws fewer antivirus heuristics, since nothing is
extracted at run time, and leaves no _MEI temp folders behind.

--noconsole so no black window appears behind the app. Note that this makes
sys.stdout None, which is why nothing in this app prints for a living.
"""

import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DIST = os.path.join(ROOT, 'dist')
NAME = 'Ettesal'


def main():
    if os.name != 'nt':
        raise SystemExit('This builds a Windows app, so it has to run on Windows.')

    for path in (DIST, os.path.join(ROOT, 'build')):
        shutil.rmtree(path, ignore_errors=True)

    sep = ';'          # PyInstaller's --add-data separator on Windows
    args = [
        sys.executable, '-m', 'PyInstaller',
        '--noconfirm', '--clean',
        '--onedir', '--noconsole',
        '--name', NAME,
        '--distpath', DIST,
        '--workpath', os.path.join(ROOT, 'build'),
        '--specpath', os.path.join(ROOT, 'build'),
        # The page, its stylesheet, its script and the two typefaces.
        '--add-data', f'{os.path.join(HERE, "ui")}{sep}ui',
        # The proxy itself is a sibling of the app folder in the repo and a
        # child of it once bundled, so engine.py looks in both.
        '--add-data', f'{os.path.join(ROOT, "ovpn-proxy.py")}{sep}.',
        '--hidden-import', 'winproxy',
        '--hidden-import', 'engine',
        '--hidden-import', 'countries',
        os.path.join(HERE, 'main.py'),
    ]

    icon = os.path.join(HERE, 'ui', 'app.ico')
    if os.path.isfile(icon):
        args[-1:-1] = ['--icon', icon]

    print('building...', flush=True)
    subprocess.run(args, check=True)

    out = os.path.join(DIST, NAME)
    print()
    print(f'built: {out}')
    print(f'run:   {os.path.join(out, NAME + ".exe")}')
    print()
    print('That folder is the whole app. It still needs, beside it:')
    print('   servers/      the pinned .ovpn files')
    print('   .ovpn-auth    the username and password, one per line')


if __name__ == '__main__':
    main()
