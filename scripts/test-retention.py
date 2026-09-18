#!/usr/bin/env python3
"""Exercise the shipped retention functions with failing object-store reads."""
from pathlib import Path
import subprocess
import tempfile
import unittest

RESOURCE = Path(__file__).resolve().parents[1] / 'green/src/resources/io/github/getcolors/valkey/tools/ansible/r2-env.sh'
OLD, MIDDLE, NEW = '20000101T000000Z', '20010101T000000Z', '20990101T000000Z'


class Retention(unittest.TestCase):
    def run_case(self, mode):
        source = RESOURCE.read_text()
        functions = source[source.index('r2_cat()'):source.index('# Age in hours')]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            fake = directory / 'rclone'
            fake.write_text('''#!/usr/bin/env python3
import json, os, sys
command, path = sys.argv[1:3]
mode = os.environ['MOCK_MODE']
old, middle, new = '20000101T000000Z', '20010101T000000Z', '20990101T000000Z'
if command == 'purge':
    with open(os.environ['PURGE_LOG'], 'a') as stream: stream.write(path + '\\n')
    sys.exit(0)
if command == 'lsf':
    if mode == 'list-error':
        print('AccessDenied', file=sys.stderr); sys.exit(3)
    print('\\n'.join(x + '/' for x in ([old] if mode == 'singleton' else [old, middle, new])))
elif command == 'lsjson':
    stamp = path.rstrip('/').split('/')[-1]
    if mode == 'marker-list-error' and stamp == middle:
        print('connection reset', file=sys.stderr); sys.exit(3)
    print(json.dumps([] if mode == 'missing-marker' and stamp == old else [{'Name': '.complete'}]))
elif command == 'cat':
    stamp = path.split('/')[-2]
    if mode in ('read-error', 'late-read-error') and stamp == (middle if mode == 'read-error' else new):
        print('403 AccessDenied', file=sys.stderr); sys.exit(3)
    if mode != 'empty-marker' or stamp != old: print('completed')
else:
    raise SystemExit('unexpected mocked command: ' + command)
''')
            fake.chmod(0o755)
            log = directory / 'purges'
            import os
            environment = dict(os.environ, PATH=str(directory) + ':' + os.environ['PATH'],
                               MOCK_MODE=mode, PURGE_LOG=str(log))
            result = subprocess.run(['bash', '-c', 'set -euo pipefail\nRETENTION_DAYS=7\n' + functions + '\nprune_sets backup:shared/valkey-vultr/valkey || exit $?' ],
                                    env=environment, capture_output=True, text=True)
            purges = log.read_text().splitlines() if log.exists() else []
            return result, purges

    def test_read_errors_never_purge_even_after_valid_old_sets(self):
        for mode in ('list-error', 'marker-list-error', 'read-error', 'late-read-error'):
            with self.subTest(mode=mode):
                result, purges = self.run_case(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(purges, [])

    def test_absent_and_empty_markers_are_retained(self):
        for mode in ('missing-marker', 'empty-marker'):
            with self.subTest(mode=mode):
                result, purges = self.run_case(mode)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(purges, ['backup:shared/valkey-vultr/valkey/' + MIDDLE])

    def test_only_expired_completed_sets_are_pruned(self):
        result, purges = self.run_case('success')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(purges, ['backup:shared/valkey-vultr/valkey/' + x for x in (OLD, MIDDLE)])

    def test_newest_completed_set_is_retained_even_when_expired(self):
        result, purges = self.run_case('singleton')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(purges, [])


if __name__ == '__main__':
    unittest.main()
