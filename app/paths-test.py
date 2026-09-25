#!/usr/bin/env python3
"""Frozen Relay keeps mutable data in the user's profile."""

import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path


def test_frozen_data_outside_install():
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        install = root / 'Program Files' / 'Relay'
        local = root / 'user' / 'AppData' / 'Local'
        (install / 'servers').mkdir(parents=True)
        (install / 'configs').mkdir()
        (install / '.state').mkdir()
        (install / 'servers' / 'starter.ovpn').write_text('bundled')
        (install / 'servers' / 'new.ovpn').write_text('new')
        (install / 'configs' / 'manual.ovpn').write_text('manual')
        (install / '.state' / 'accounts.json').write_text('sealed account')
        (install / '.state' / 'settings.json').write_text(json.dumps({
            'folder': str(install / 'servers'),
            'pinFolder': str(install / 'configs'),
            'source': 'folder:' + str(install / 'servers'),
            'external': str(root / 'elsewhere')}))
        (install / '.state' / 'dropped.json').write_text(json.dumps({
            'servers/old.ovpn': {'from': str(install / 'servers')}}))
        (install / '.state' / 'proxy.state').write_text('stale process')
        (install / '.state' / 'system-proxy-before.json').write_text('stale proxy')
        (install / '.ovpn-auth').write_text('service credentials')
        (install / 'gost.exe').write_bytes(b'client')
        (install / 'Resolve-OvpnRemote.ps1').write_text('script')

        data = local / 'Relay'
        (data / 'servers').mkdir(parents=True)
        (data / 'servers' / 'starter.ovpn').write_text('user version')

        old_exe = sys.executable
        old_frozen = getattr(sys, 'frozen', None)
        old_local = os.environ.get('LOCALAPPDATA')
        try:
            sys.executable = str(install / 'Relay.exe')
            sys.frozen = True
            os.environ['LOCALAPPDATA'] = str(local)
            spec = importlib.util.spec_from_file_location(
                'relay_frozen_paths', Path(__file__).with_name('paths.py'))
            paths = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(paths)
        finally:
            sys.executable = old_exe
            if old_frozen is None:
                del sys.frozen
            else:
                sys.frozen = old_frozen
            if old_local is None:
                os.environ.pop('LOCALAPPDATA', None)
            else:
                os.environ['LOCALAPPDATA'] = old_local

        assert Path(paths.DATA_DIR) == data
        assert Path(paths.AUTH_FILE) == data / '.ovpn-auth'
        assert Path(paths.SCRIPTS_DIR) == install
        assert Path(paths.gost_exe()) == install / 'gost.exe'
        class ProxyPaths:
            pass
        proxy_paths = ProxyPaths()
        paths.point_proxy_module_at_data(proxy_paths)
        assert Path(proxy_paths.TUNNEL_SETTINGS) == data / '.state' / 'tunnel.json'
        assert Path(proxy_paths.TUNNEL_STATE) == data / '.state' / 'tunnel'
        paths.prepare_data()
        assert (data / 'servers' / 'starter.ovpn').read_text() == 'user version'
        assert (data / 'servers' / 'new.ovpn').read_text() == 'new'
        assert (data / 'configs' / 'manual.ovpn').read_text() == 'manual'
        assert (data / '.state' / 'accounts.json').read_text() == 'sealed account'
        settings = json.loads((data / '.state' / 'settings.json').read_text())
        assert settings['folder'] == str(data / 'servers')
        assert settings['pinFolder'] == str(data / 'configs')
        assert settings['source'] == 'folder:' + str(data / 'servers')
        assert settings['external'] == str(root / 'elsewhere')
        dropped = json.loads((data / '.state' / 'dropped.json').read_text())
        assert dropped['servers/old.ovpn']['from'] == str(data / 'servers')
        assert not (data / '.state' / 'proxy.state').exists()
        assert not (data / '.state' / 'system-proxy-before.json').exists()
        assert (data / '.ovpn-auth').read_text() == 'service credentials'
        (data / '.state' / 'accounts.json').write_text('new user version')
        (data / 'servers' / 'later.ovpn').write_text('user later')
        paths.prepare_data()
        assert (data / 'servers' / 'later.ovpn').read_text() == 'user later'
        assert (data / '.state' / 'accounts.json').read_text() == 'new user version'


if __name__ == '__main__':
    test_frozen_data_outside_install()
    print('ok test_frozen_data_outside_install')
