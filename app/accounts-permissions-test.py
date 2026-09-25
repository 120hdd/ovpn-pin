#!/usr/bin/env python3
"""A Windows account can still read and update its private roster."""

import builtins
import json
import os
import subprocess
import tempfile
from unittest import mock
from pathlib import Path

import accounts
import windscribe


def test_locked_roster():
    if os.name != 'nt':
        print('skip Windows ACL test')
        return

    with tempfile.TemporaryDirectory() as tmp:
        folder = Path(tmp) / 'Rubika Stock' / 'AppData' / 'Local' / 'Relay' / '.state'
        folder.mkdir(parents=True)
        store = folder / 'accounts.json'
        store.write_text(json.dumps({
            'accounts': [{'id': 'older', 'provider': 'surfshark',
                          'label': 'Older', 'username': 'older'}],
            'active': {},
        }), encoding='utf-8')

        old_store = accounts.STORE
        old_username = os.environ.get('USERNAME')
        accounts.STORE = str(store)
        # The environment name can be stale or different from the token SID.
        os.environ['USERNAME'] = 'SYSTEM'
        try:
            result = subprocess.run(
                ['icacls', str(store), '/inheritance:r',
                 '/grant:r', 'SYSTEM:F'],
                capture_output=True)
            assert result.returncode == 0, result.stderr
            try:
                store.read_text(encoding='utf-8')
            except PermissionError:
                # A normal account must recover from the damaged ACL.
                state = accounts.load()
            else:
                # Hosted runners may have an elevated token that can read
                # the file despite its broken user ACL. Exercise the same
                # recovery path on those runners without relying on their
                # effective permissions.
                original_open = builtins.open
                denied = False

                def deny_first_read(file, *args, **kwargs):
                    nonlocal denied
                    if not denied and os.fspath(file) == str(store):
                        denied = True
                        raise PermissionError(13, 'Permission denied', str(store))
                    return original_open(file, *args, **kwargs)

                with mock.patch('builtins.open', side_effect=deny_first_read):
                    state = accounts.load()
                assert denied

            assert state['accounts'][0]['id'] == 'older'
            accounts.put('surfshark', 'Newer', 'newer')
            assert {a['id'] for a in accounts.load()['accounts']} >= {'older'}
            assert {a['username'] for a in accounts.load()['accounts']} == {
                'older', 'newer'}
            assert windscribe._lock_down(str(store))
            accounts.put('surfshark', 'Newest', 'newest')
            assert len(accounts.load()['accounts']) == 3
        finally:
            subprocess.run(['icacls', str(store), '/reset'],
                           capture_output=True)
            accounts.STORE = old_store
            if old_username is None:
                os.environ.pop('USERNAME', None)
            else:
                os.environ['USERNAME'] = old_username


if __name__ == '__main__':
    test_locked_roster()
    print('ok test_locked_roster')
