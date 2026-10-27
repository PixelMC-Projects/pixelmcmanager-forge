#!/usr/bin/env python3
"""Offline AST/extracted-function checks. Never run the protected worker as a module.

All subprocesses are mocked and all paths resolve to synthetic temporary fixtures;
these tests cannot contact Docker, OSS, the production database or game server.
"""
from __future__ import annotations

import ast
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import update_offsite


WORKER = Path(os.environ.get(
    'OFFSITE_TEST_WORKER',
    r'C:\Users\Administrator\Documents\ChatGPT\服务器综合\outputs\dailyfortunes-deploy-20261027\backup.current.py',
))
MANAGER_JAR = '/data/releases/0.6.0/pixelmc-manager-service-0.6.0.jar'
DATABASES = ['dailyfortunes', 'memorialtitles', 'pixelmc_manager', 'pixelmc_platform']


def fixture_path(*parts):
    # Emulate production POSIX path checks on Windows without mapping any real
    # /home or /data path. ROOT/BASE are independently injected native temp paths.
    return (PurePosixPath(*parts)
        if parts and isinstance(parts[0], str) and parts[0].startswith('/') else Path(*parts))


def function_map(source):
    return {node.name: node for node in ast.parse(source).body if isinstance(node, ast.FunctionDef)}


def extracted(source, names, namespace):
    functions = function_map(source)
    module = ast.Module(body=[functions[name] for name in names], type_ignores=[])
    exec(compile(ast.fix_missing_locations(module), '<offline-backup-functions>', 'exec'), namespace)
    return namespace


class OffsiteChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = WORKER.read_text('utf-8')
        cls.candidate = update_offsite.patch(cls.original)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pixelmc-offsite-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.base = self.root / 'offsite'
        self.manager = self.root / 'pixelmc-manager'
        self.base.mkdir()
        self.manager.mkdir()
        (self.root / 'secrets').mkdir()
        (self.root / 'secrets/root-password').write_text('SYNTHETIC_FIXTURE_NOT_A_CREDENTIAL')
        self.config = {
            'storageMode': 'unified',
            'database': {'url': 'jdbc:mariadb://fixture:3306/pixelmc_platform'},
            'dataDirectory': 'custom-state/nested',
        }
        self.save_config()
        self.conf = {
            'database_container': 'SYNTHETIC_DATABASE_CONTAINER',
            'game_config': str(self.root / 'game-config'),
            'upload_kib_per_second': 625, 'download_kib_per_second': 1024,
            'region': 'synthetic-region', 'host': 'synthetic-host', 'tag': 'synthetic-tag',
            'keep_within': '12m', 'keep_within_daily': '30d',
        }
        self.run = Mock(side_effect=self.fake_process)
        self.names = list(DATABASES)
        self.commandline = b'/usr/bin/java\x00-Xmx512m\x00-jar\x00' + MANAGER_JAR.encode() + b'\x00'
        self.process_error = False
        self.create_archive = True
        self.calls = []
        self.env = {
            'ROOT': self.root, 'BASE': self.base, 'CONF': self.conf,
            'Path': fixture_path, 'subprocess': SimpleNamespace(run=self.run, PIPE=subprocess.PIPE),
            'json': json, 'dt': dt, 'os': os, 'tempfile': tempfile, 'shutil': shutil,
            'hashlib': hashlib, 'redact': lambda value: '[REDACTED FIXTURE ERROR]',
        }
        extracted(self.candidate, ['discover_databases', 'running_manager_service_jar',
            'prepare_snapshot', 'copy_path', 'file_hash', 'timestamp', 'atomic_json', 'restic', 'prune'], self.env)

    def save_config(self):
        (self.manager / 'manager.json').write_text(json.dumps(self.config))

    def fake_process(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if '--execute' in command:
            return SimpleNamespace(returncode=1 if self.process_error else 0,
                stdout=('\n'.join(self.names) + '\n').encode(), stderr=b'synthetic-error')
        if '/proc/1/cmdline' in command:
            return SimpleNamespace(returncode=0, stdout=self.commandline, stderr=b'')
        if '/proc/1/cwd' in command:
            return SimpleNamespace(returncode=0, stdout=b'/data\n', stderr=b'')
        if 'mariadb-dump' in command:
            kwargs['stdout'].write(b'-- SYNTHETIC SQL ONLY\n' * 20)
            return SimpleNamespace(returncode=0, stdout=b'', stderr=b'')
        if 'backup' in command and 'java' in command:
            if self.create_archive:
                destination = self.manager / self.config['dataDirectory'] / 'backups/manager'
                destination.mkdir(parents=True, exist_ok=True)
                (destination / 'manager-fixture.zip').write_bytes(b'SYNTHETIC_ARCHIVE_NOT_A_REAL_BACKUP')
                (destination / 'manager-fixture.zip.sha256').write_text('SYNTHETIC_CHECKSUM')
            return SimpleNamespace(returncode=0, stdout=b'', stderr=b'')
        if command[0] == str(self.base / 'bin/restic'):
            return SimpleNamespace(returncode=0, stdout='[]', stderr='')
        raise AssertionError('Unexpected subprocess in an offline test')

    def discover(self):
        return self.env['discover_databases'](b'SYNTHETIC_DEFAULTS')

    def jar(self, arguments):
        self.commandline = b'\x00'.join(value.encode() for value in arguments) + b'\x00'
        return self.env['running_manager_service_jar']()

    def test_patch_is_parseable_and_preserves_unrelated_worker_logic(self):
        original = function_map(self.original)
        candidate = function_map(self.candidate)
        self.assertEqual(set(original), set(candidate))
        for name in set(original) - {'discover_databases', 'running_manager_service_jar', 'prepare_snapshot'}:
            self.assertEqual(ast.dump(original[name]), ast.dump(candidate[name]), name)
        before = [node for node in ast.parse(self.original).body if not isinstance(node, ast.FunctionDef)]
        after = [node for node in ast.parse(self.candidate).body if not isinstance(node, ast.FunctionDef)]
        self.assertEqual([ast.dump(node) for node in before], [ast.dump(node) for node in after])
        self.assertNotIn('systemctl', self.candidate)
        self.assertEqual(self.original, WORKER.read_text('utf-8'), 'original worker remains untouched')

    def test_patch_refuses_unknown_repeated_or_already_modified_source(self):
        with self.assertRaises(ValueError):
            update_offsite.patch(self.candidate)
        with self.assertRaises(ValueError):
            update_offsite.patch(self.original.replace(
                '                   "--databases", *databases]', 'changed-known-anchor'))

    def test_service_only_hotfix_path_is_accepted(self):
        hotfix = '/data/releases/0.6.0.web.1/pixelmc-manager-service-0.6.0.web.1.jar'
        self.assertEqual(hotfix, self.jar(['/usr/bin/java', '-jar', hotfix, 'run', 'manager.json']))

    def test_discovers_all_four_databases_using_protected_stdin_defaults(self):
        self.assertEqual(DATABASES, self.discover())
        command, kwargs = self.calls[-1]
        query = command[command.index('--execute') + 1]
        for name in DATABASES:
            self.assertIn("'" + name + "'", query)
        self.assertIn('--defaults-extra-file=/dev/stdin', command)
        self.assertEqual(b'SYNTHETIC_DEFAULTS', kwargs['input'])
        self.assertNotIn('SYNTHETIC_DEFAULTS', ' '.join(command))

    def test_missing_unified_or_mismatched_unified_url_refuses(self):
        self.names.remove('pixelmc_platform')
        with self.assertRaisesRegex(RuntimeError, 'Unified application database is missing'):
            self.discover()
        self.names.append('pixelmc_platform')
        self.config['database']['url'] = 'jdbc:mariadb://fixture:3306/other_database'
        self.save_config()
        with self.assertRaisesRegex(RuntimeError, 'Unified application database is missing'):
            self.discover()

    def test_invalid_or_failed_discovery_cannot_upload_partial_snapshot(self):
        for names in [DATABASES + ['unregistered_database'], DATABASES + ['dailyfortunes'],
                      ['dailyfortunes', 'pixelmc_platform']]:
            self.names = names
            with self.assertRaisesRegex(RuntimeError, 'Unexpected database discovery'):
                self.discover()
        self.names = list(DATABASES)
        self.process_error = True
        with self.assertRaisesRegex(RuntimeError, 'Database discovery failed'):
            self.discover()
        self.assertEqual('[REDACTED FIXTURE ERROR]', (self.base / 'last-dump-error.log').read_text())

    def test_new_service_absolute_path_is_allowed(self):
        self.assertEqual(MANAGER_JAR, self.jar(['/usr/bin/java', '-Xmx512m', '-jar', MANAGER_JAR]))

    def test_arbitrary_relative_and_traversal_service_paths_are_rejected(self):
        for candidate in [
            '/data/releases/0.6.0/unreviewed.jar', '/tmp/pixelmc-manager-service-0.6.0.jar',
            'releases/0.6.0/pixelmc-manager-service-0.6.0.jar',
            '/data/releases/0.6.0/../0.6.0/pixelmc-manager-service-0.6.0.jar',
        ]:
            with self.assertRaisesRegex(RuntimeError, 'Unrecognized running Manager service'):
                self.jar(['/usr/bin/java', '-jar', candidate])
        for arguments in [['/bin/sh', '-jar', MANAGER_JAR],
                          ['/usr/bin/java', '-jar', MANAGER_JAR, '-jar', MANAGER_JAR],
                          ['/usr/bin/java', '-jar']]:
            with self.assertRaises(RuntimeError):
                self.jar(arguments)

    def test_previous_exact_relative_service_retains_cwd_validation(self):
        relative = 'releases/0.5.1/pixelmc-manager-service.jar'
        self.assertEqual('/data/' + relative, self.jar(['/usr/bin/java', '-jar', relative]))
        self.assertTrue(any('/proc/1/cwd' in command for command, _ in self.calls))

    def test_snapshot_uses_configured_nondefault_data_directory_and_complete_sql(self):
        current, manifest = self.env['prepare_snapshot']()
        self.assertTrue((current / 'manager/manager-fixture.zip').is_file())
        self.assertFalse((self.manager / 'data').exists(), 'hard-coded old data path was not used')
        self.assertEqual(DATABASES, manifest['databases'])
        dump = next(command for command, _ in self.calls if 'mariadb-dump' in command)
        self.assertIn('--single-transaction', dump)
        self.assertIn('--skip-lock-tables', dump)
        self.assertIn('--hex-blob', dump)
        excluded = {arg for arg in dump if arg.startswith('--ignore-table=')}
        self.assertEqual({'--ignore-table=' + schema + '.' + table
            for schema in ('pixelmc_manager', 'pixelmc_platform')
            for table in ('pm_metrics', 'pm_long_ticks', 'pm_tick_groups')}, excluded)
        self.assertEqual(DATABASES, dump[dump.index('--databases') + 1:])
        backup = next(command for command, _ in self.calls if 'java' in command and 'backup' in command)
        self.assertEqual(MANAGER_JAR, backup[backup.index('-jar') + 1])

    def test_absolute_traversal_and_missing_data_directories_refuse(self):
        for directory in ['/etc', '../outside', 'safe/../../outside']:
            self.config['dataDirectory'] = directory
            self.save_config()
            self.create_archive = False
            with self.assertRaisesRegex(RuntimeError, 'Unexpected Manager data directory'):
                self.env['prepare_snapshot']()
        self.config['dataDirectory'] = 'missing-directory'
        self.save_config()
        with self.assertRaisesRegex(RuntimeError, 'durable data directory is missing or unsafe'):
            self.env['prepare_snapshot']()

    def test_intermediate_symlink_data_directory_refuses(self):
        outside = self.root / 'outside-manager-root'
        link = self.manager / 'linked-parent'
        data_root = link / 'inner'
        archives = data_root / 'backups/manager'
        archives.mkdir(parents=True)
        (archives / 'manager-fixture.zip').write_bytes(b'SYNTHETIC_OUTSIDE_MANAGER_INPUT')
        self.config['dataDirectory'] = 'linked-parent/inner'
        self.save_config()
        self.create_archive = False
        original_symlink, original_resolve = Path.is_symlink, Path.resolve

        def virtual_symlink(path):
            return path == link or original_symlink(path)

        def virtual_resolve(path, strict=False):
            if path == data_root:
                return outside / 'inner'
            if path == link:
                return outside
            return original_resolve(path, strict=strict)

        # Windows does not grant symlink creation here. Model the same filesystem
        # observations explicitly: the final directory is ordinary, its parent
        # is a link, and resolving the final path escapes the Manager root.
        with patch.object(Path, 'is_symlink', virtual_symlink), patch.object(Path, 'resolve', virtual_resolve):
            with self.assertRaisesRegex(RuntimeError, 'unsafe|Unexpected Manager data directory'):
                self.env['prepare_snapshot']()

    def test_upload_and_download_bandwidth_remain_configuration_driven(self):
        self.assertEqual('[]', self.env['restic']('snapshots'))
        command, _ = self.calls[-1]
        self.assertEqual('625', command[command.index('--limit-upload') + 1])
        self.assertEqual('1024', command[command.index('--limit-download') + 1])
        self.assertIn('s3.connections=2', command)

    def test_retention_values_are_unchanged_and_no_maintenance_process_is_run(self):
        (self.base / 'status.json').write_text(json.dumps({'last_success': dt.datetime.now(dt.timezone.utc).isoformat()}))
        restic = Mock(return_value='[]')
        self.env['restic'] = restic
        self.env['prune']()
        forget = restic.call_args_list[0].args
        self.assertEqual('12m', forget[forget.index('--keep-within') + 1])
        self.assertEqual('30d', forget[forget.index('--keep-within-daily') + 1])
        self.assertEqual(('prune', '--max-unused', '20%'), restic.call_args_list[1].args)
        self.run.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
