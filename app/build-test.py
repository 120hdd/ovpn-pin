#!/usr/bin/env python3
"""Release-boundary tests: public inputs stay useful and credential-free."""

import importlib.util
import os
import re
import tempfile


HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location('relay_build',
                                              os.path.join(HERE, 'build.py'))
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


def test_public_starters():
    starters = build.public_starters()
    assert len(starters) == 10
    assert sum(s['provider'] == 'surfshark' for s in starters) == 5
    assert sum(s['provider'] == 'windscribe' for s in starters) == 5

    with tempfile.TemporaryDirectory() as folder:
        written = build.write_public_starters(folder)
        assert len(written) == 10
        assert len({os.path.basename(p) for p in written}) == 10
        for path in written:
            text = open(path, encoding='utf-8').read()
            assert build._safe_public_server(path)
            assert re.search(r'(?m)^remote (?:\d{1,3}\.){3}\d{1,3} \d+$', text)
            assert 'Public starter: contains no account credentials.' in text
            assert not re.search(r'(?im)^\s*remote\s+[^\d\s]', text)
        surfshark = [p for p in written if '.prod.surfshark.com_' in p]
        windscribe = [p for p in written if '.ws.' in p]
        assert len(surfshark) == len(windscribe) == 5
        assert all('<ca>' in open(p, encoding='utf-8').read()
                   for p in surfshark)
        assert all('not an OpenVPN profile' in open(p, encoding='utf-8').read()
                   for p in windscribe)


def test_dynamic_proxy_imports():
    proxy = os.path.join(os.path.dirname(HERE), 'core', 'ovpn-proxy.py')
    imports = build.dynamic_imports(proxy)
    assert 'difflib' in imports
    assert 'concurrent.futures' in imports
    assert 'urllib.request' in imports
    assert len(imports) == len(set(imports))


def test_version_file():
    old = os.environ.get('RELAY_VERSION')
    old_work = build.WORK
    try:
        with tempfile.TemporaryDirectory() as folder:
            build.WORK = folder
            os.environ['RELAY_VERSION'] = 'v2.7.3-beta.1'
            text = open(build.write_version_file(), encoding='utf-8').read()
            assert "filevers=(2, 7, 3, 1)" in text
            assert "StringStruct('ProductName', 'Relay')" in text
            assert "StringStruct('ProductVersion', '2.7.3-beta.1')" in text
    finally:
        build.WORK = old_work
        if old is None:
            os.environ.pop('RELAY_VERSION', None)
        else:
            os.environ['RELAY_VERSION'] = old


if __name__ == '__main__':
    tests = [value for name, value in sorted(globals().items())
             if name.startswith('test_') and callable(value)]
    for test in tests:
        test()
        print('ok', test.__name__)
    print(f'{len(tests)} tests passed')
