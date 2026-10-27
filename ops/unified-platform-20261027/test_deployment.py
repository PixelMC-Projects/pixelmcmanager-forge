#!/usr/bin/env python3
"""Pure local deployment rehearsal with synthetic files and mocked host services.

No Docker, MariaDB, MCSM, systemctl or game process is contacted. Tests call the
real deploy.backup/migrate/activate functions against disposable temporary roots.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import deploy


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def hash_bytes(value):
    return hashlib.sha256(value).hexdigest()


class FakeDaemon:
    def __init__(self, owner):
        self.owner = owner

    def request(self, action, data):
        self.owner.mcsm_calls.append((action, copy.deepcopy(data)))
        if action not in {'instance/detail', 'instance/update'}:
            raise AssertionError('Deployment attempted an unauthorized MCSM operation')
        instance = data['instanceUuid']
        if action == 'instance/update':
            self.owner.mcsm_update_count += 1
            self.owner.mcsm_configs[instance] = copy.deepcopy(data['config'])
            if self.owner.fail_second_update and self.owner.mcsm_update_count == 2:
                # The remote update committed before its response was lost. A
                # rollback must restore both instances, including this one.
                raise RuntimeError('synthetic second MCSM response failure')
        return {'data': {'status': 0, 'config': copy.deepcopy(self.owner.mcsm_configs[instance])}}

    def close(self):
        self.owner.mcsm_closed += 1


class DeploymentChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pixelmc-deployment-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.game, self.dbroot = self.root / 'game', self.root / 'database'
        self.web = self.dbroot / 'web'
        for path in (self.game, self.dbroot, self.web):
            path.mkdir(parents=True, exist_ok=True)
        self.site = {
            'gameRoot': str(self.game), 'databaseRoot': str(self.dbroot), 'webRoot': str(self.web),
            'gameInstance': 'a' * 32, 'webInstance': 'b' * 32,
            'gameContainer': 'SYNTHETIC_GAME', 'webContainer': 'SYNTHETIC_WEB',
            'databaseContainer': 'SYNTHETIC_DATABASE', 'databaseNetwork': 'SYNTHETIC_NETWORK',
            'daemonConfig': str(self.root / 'daemon.json'), 'restartModule': str(self.root / 'restart.py'),
            'databaseDefaultsFile': '/data/secrets/SYNTHETIC_TEST_DEFAULTS.cnf',
        }
        deploy.configure(self.site)
        self.ws, self.gs, self.backup_root, self.new_data = deploy.WS, deploy.GS, deploy.BACKUP, deploy.NEW_DATA
        for directory in (self.ws, self.gs, self.web / 'releases', self.game / 'mods',
                          self.dbroot / 'secrets', self.dbroot / 'offsite'):
            directory.mkdir(parents=True, exist_ok=True)
        self.old_config = {
            'storageMode': 'legacy', 'dataDirectory': 'durable-original/nested',
            'database': {'url': 'jdbc:mariadb://synthetic:3306/pixelmc_manager', 'user': 'FIXTURE_USER', 'password': 'FIXTURE_ONLY'},
            'titlesDatabase': {'url': 'jdbc:mariadb://synthetic:3306/memorialtitles'},
            'fortunesDatabase': {'url': 'jdbc:mariadb://synthetic:3306/dailyfortunes'},
            'bind': '0.0.0.0', 'port': 18765, 'titlesEnabled': True, 'fortunesEnabled': True,
        }
        write_json(self.web / 'manager.json', self.old_config)
        self.old_data = self.web / self.old_config['dataDirectory']
        for name, data in {
            'credentials/bridge.json': b'SYNTHETIC_BRIDGE_BYTES',
            'titles/avatars/fixture.png': b'SYNTHETIC_AVATAR_BYTES',
            'titles/web-accounts.json': b'{"synthetic-hash":"unchanged"}',
            'platform-state.json': b'{"synthetic":true}',
        }.items():
            path = self.old_data / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.title_path = self.game / 'config/memorialtitles/server.json'
        self.daily_path = self.game / 'config/dailyfortunes/server.json'
        self.auto_path = self.game / 'automodpack/automodpack-server.json'
        self.offsite_path = self.dbroot / 'offsite/backup.py'
        write_json(self.title_path, {'mode': 'database', 'synthetic-setting': 1})
        write_json(self.daily_path, {'mode': 'database', 'synthetic-setting': 2})
        self.old_mods = {
            'pixelmcmanager-old.jar': b'ORIGINAL_MANAGER',
            'memorialtitles-old.jar': b'ORIGINAL_TITLES',
            'dailyfortunes-old.jar': b'ORIGINAL_FORTUNES',
            'FramedBlocks-old.jar': b'ORIGINAL_FRAMED_BLOCKS',
            'unrelated-mod.jar': b'UNRELATED_MUST_REMAIN',
        }
        for name, value in self.old_mods.items():
            (self.game / 'mods' / name).write_bytes(value)
        self.auto_original = {
            'syncedFiles': ['/mods/' + name for name in self.old_mods],
            'requireHostFingerprint': True, 'allowInvalidCertificates': False,
            'hostIp': 'synthetic-host.invalid', 'synthetic-security-field': ['keep', 'unchanged'],
        }
        write_json(self.auto_path, self.auto_original)
        self.offsite_path.write_bytes(b'# SYNTHETIC ORIGINAL WORKER\n')
        write_json(self.dbroot / 'offsite/config.json', {'synthetic': True, 'upload': 625})
        (self.dbroot / 'secrets/root-password').write_bytes(b'SYNTHETIC_NOT_A_REAL_SECRET\n')
        self.original_mcsm = {
            deploy.GI: {
                'cwd': str(self.game),
                'startCommand': 'bash pixelmcmanager/activate-manager.sh java -Xms2G -Xmx4G -XX:+UseG1GC @unix_args.txt nogui',
                'docker': {'image': 'fixture-java17', 'cpuSet': '0-6', 'memory': 8192},
                'eventTask': {'autoRestart': False, 'synthetic': 'game-event'},
                'nickname': 'SYNTHETIC GAME',
            },
            deploy.WI: {
                'cwd': str(self.web),
                'startCommand': 'bash deployment/manager-ui-0.5.2-20261026/launch-manager.sh java -Xmx512m -Duser.timezone=Asia/Shanghai -jar /data/old-manager.jar /data/manager.json',
                'docker': {'image': 'fixture-java17', 'cpuSet': '7', 'memory': 1024},
                'eventTask': {'autoRestart': True, 'synthetic': 'web-event'},
                'nickname': 'SYNTHETIC WEB',
            },
        }
        self.mcsm_configs = copy.deepcopy(self.original_mcsm)
        write_json(self.ws / 'pre-maintenance-mcsm.json', self.original_mcsm)
        self.mcsm_calls, self.process_calls, self.migration_calls, self.sql_calls = [], [], [], []
        self.mcsm_closed = self.mcsm_update_count = 0
        self.fail_second_update = False
        self.target_exists = False
        self.container_states = {
            deploy.GAME_CONTAINER: {'Running': False, 'Restarting': False, 'Pid': 0},
            deploy.WEB_CONTAINER: {'Running': False, 'Restarting': False, 'Pid': 0},
            deploy.DB_CONTAINER: {'Running': True, 'Restarting': False, 'Pid': 991},
        }
        self.timer_enabled = True
        self.bundle = self.make_bundle()
        owner = self

        class FakeMigrator:
            def __init__(self, plan):
                self.plan = copy.deepcopy(plan)

            def inspect(self):
                owner.migration_calls.append(('inspect', copy.deepcopy(self.plan)))
                return {'format': 1, 'phase': 'inspected', 'target': deploy.TARGET, 'tables': []}

            def copy(self, confirmation):
                owner.assertEqual('MIGRATE-EMPTY-UNIFIED', confirmation)
                owner.migration_calls.append(('copy', copy.deepcopy(self.plan)))
                owner.target_exists = True
                return {'format': 1, 'phase': 'copied-and-verified', 'target': deploy.TARGET, 'tables': []}

            def verify(self, report=None, after_platform_init=False):
                owner.migration_calls.append(('verify', after_platform_init))
                return report or {'format': 1, 'phase': 'copied-and-verified', 'target': deploy.TARGET, 'tables': []}

        class FakeSQL:
            def __init__(self, transport):
                owner.assertEqual(deploy.TRANSPORT, transport)

            def text(self, statement):
                owner.sql_calls.append(('text', statement))
                if not statement.startswith('CREATE DATABASE pixelmc_platform '):
                    raise AssertionError('Unexpected SQL mutation in local deployment rehearsal')
                return ''

            def rows(self, statement):
                owner.sql_calls.append(('rows', statement))
                if "SCHEMA_NAME='pixelmc_platform'" in statement:
                    return [['pixelmc_platform']] if owner.target_exists else []
                if 'retentionDays' in statement:
                    return [['30']]
                if statement.startswith('SELECT COUNT(*) FROM pixelmc_platform.pm_profiles'):
                    return [['2']]
                if 'UNION SELECT uuid FROM' in statement or (statement.startswith('SELECT uuid FROM pixelmc_platform.pm_profiles ')
                        and 'pm_profile_exclusions' in statement):
                    return [['00000000-0000-0000-0000-000000000001'], ['00000000-0000-0000-0000-000000000002']]
                raise AssertionError('Unexpected SQL read in local deployment rehearsal')

        self.addCleanup(patch.stopall)
        patch.object(deploy, 'Migrator', FakeMigrator).start()
        patch.object(deploy, 'MariaClient', FakeSQL).start()
        patch.object(deploy, 'daemon', lambda: FakeDaemon(self)).start()
        patch.object(deploy, 'subprocess', SimpleNamespace(
            run=self.fake_run, check_output=self.fake_check_output,
            PIPE=subprocess.PIPE, STDOUT=subprocess.STDOUT, DEVNULL=subprocess.DEVNULL,
        )).start()

    def make_bundle(self):
        mods = ['pixelmcmanager-forge-1.20.1-0.6.0.jar', 'memorialtitles-forge-1.20.1-0.5.0-dev.jar',
                'dailyfortunes-forge-1.20.1-0.2.0.jar', 'FramedBlocks-9.4.4.jar']
        values = {name: ('SYNTHETIC NEW ' + name).encode() for name in mods}
        values.update({
            'pixelmc-manager-service-0.6.0.jar': b'SYNTHETIC_NEW_WEB_SERVICE',
            'backup.unified.py': b'# SYNTHETIC UNIFIED OFFSITE WORKER\n',
            'start-game.sh': b'#!/bin/sh\nexit 0\n', 'classes/UnifiedReadiness.class': b'SYNTHETIC_CLASS',
        })
        for name, value in values.items():
            path = self.ws / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(value)
        bundle = {
            'files': {name: hash_bytes(value) for name, value in values.items()},
            'mods': mods, 'clientMods': mods[1:], 'oldOffsiteSha256': deploy.sha(self.offsite_path),
            'expectedActiveMods': {name: hash_bytes(value) for name, value in self.old_mods.items()
                if name != 'unrelated-mod.jar'},
        }
        write_json(self.ws / 'bundle.json', bundle)
        return bundle

    def fake_check_output(self, command, **kwargs):
        self.process_calls.append((command, kwargs))
        if command[:2] != ['docker', 'inspect'] or len(command) != 3:
            raise AssertionError('Unexpected subprocess check_output')
        return json.dumps([{'State': self.container_states[command[2]],
            'NetworkSettings': {'Networks': {deploy.NETWORK: {'IPAddress': '192.0.2.10'}}}}]).encode()

    def fake_run(self, command, **kwargs):
        self.process_calls.append((command, kwargs))
        if 'mariadb-dump' in command:
            kwargs['stdout'].write(b'-- SYNTHETIC SQL BACKUP ONLY\n' * 100)
            return SimpleNamespace(returncode=0, stdout=b'', stderr=b'')
        if command[:2] == ['systemctl', 'disable']:
            self.timer_enabled = False
            return SimpleNamespace(returncode=0, stdout=b'', stderr=b'')
        if command and command[0] == 'java' and 'platform-init' in command:
            config = json.loads(Path(command[-1]).read_text())
            self.assertEqual(str(self.new_data), config['dataDirectory'])
            self.assertEqual('jdbc:mariadb://192.0.2.10:3306/pixelmc_platform', config['database']['url'])
            destination = self.new_data / 'platform/web-accounts.json'
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(self.new_data / 'titles/web-accounts.json', destination)
            return SimpleNamespace(returncode=0, stdout=b'SYNTHETIC PLATFORM INIT OK', stderr=b'')
        if command[:2] == ['bash', '-n']:
            return SimpleNamespace(returncode=0, stdout=b'', stderr=b'')
        raise AssertionError('Unexpected subprocess run in local deployment rehearsal')

    def activation_fixture(self):
        self.backup_root.mkdir(parents=True)
        write_json(self.backup_root / 'mcsm-configs.json', self.original_mcsm)
        write_json(self.ws / 'migration-plan.json', {'format': 1, 'target': deploy.TARGET,
            'sources': deploy.SOURCES, 'transport': deploy.TRANSPORT, 'reportPath': str(self.ws / 'migration-report.json')})
        write_json(self.ws / 'migration-complete.json', {'phase': 'verified', 'target': deploy.TARGET})
        config = copy.deepcopy(self.old_config)
        config.update(storageMode='unified', dataDirectory=self.new_data.name)
        config['database'] = {'url': 'jdbc:mariadb://synthetic:3306/pixelmc_platform',
            'user': 'pixelmc_platform', 'password': 'SYNTHETIC_NEW_CONFIG_ONLY'}
        write_json(self.ws / 'manager-next.json', config)
        return config

    def files_before_activation(self):
        return {path: path.read_bytes() for path in [self.web / 'manager.json', self.title_path,
            self.daily_path, self.auto_path, self.offsite_path] if path.exists()}

    def assert_no_service_control(self):
        self.assertTrue(all(action in {'instance/detail', 'instance/update'} for action, _ in self.mcsm_calls))
        forbidden = {'open', 'start', 'restart', 'kill', 'stop', 'shutdown'}
        for command, _ in self.process_calls:
            self.assertFalse(forbidden.intersection(command), command)
        self.assertEqual({
            deploy.GAME_CONTAINER: {'Running': False, 'Restarting': False, 'Pid': 0},
            deploy.WEB_CONTAINER: {'Running': False, 'Restarting': False, 'Pid': 0},
            deploy.DB_CONTAINER: {'Running': True, 'Restarting': False, 'Pid': 991},
        }, self.container_states)

    def test_activation_success_updates_only_intended_files_and_configs_without_starting(self):
        next_config = self.activation_fixture()
        deploy.activate()
        self.assertEqual(next_config, json.loads((self.web / 'manager.json').read_text()))
        self.assertEqual('manager', json.loads(self.title_path.read_text())['mode'])
        self.assertEqual('auto', json.loads(self.daily_path.read_text())['mode'])
        active = {path.name: deploy.sha(path) for path in (self.game / 'mods').glob('*.jar')}
        expected = {name: self.bundle['files'][name] for name in self.bundle['mods']}
        expected['unrelated-mod.jar'] = hash_bytes(self.old_mods['unrelated-mod.jar'])
        self.assertEqual(expected, active)
        auto = json.loads(self.auto_path.read_text())
        self.assertEqual({k: v for k, v in self.auto_original.items() if k != 'syncedFiles'},
            {k: v for k, v in auto.items() if k != 'syncedFiles'})
        self.assertEqual(['/mods/unrelated-mod.jar'] + ['/mods/' + name for name in self.bundle['clientMods']], auto['syncedFiles'])
        self.assertEqual((self.ws / 'backup.unified.py').read_bytes(), self.offsite_path.read_bytes())
        game_args = shlex.split(self.mcsm_configs[deploy.GI]['startCommand'])
        self.assertEqual(['bash', 'pixelmcmanager/unified-20261027/start-game.sh'], game_args[:2])
        self.assertEqual(shlex.split(self.original_mcsm[deploy.GI]['startCommand'])[2:], game_args[2:])
        web_args = shlex.split(self.mcsm_configs[deploy.WI]['startCommand'])
        self.assertIn('/data/releases/0.6.0/pixelmc-manager-service-0.6.0.jar', web_args)
        self.assertIn('-Xmx512m', web_args)
        for instance in (deploy.GI, deploy.WI):
            self.assertEqual(self.original_mcsm[instance]['docker'], self.mcsm_configs[instance]['docker'])
        self.assertFalse(self.mcsm_configs[deploy.WI]['eventTask']['autoRestart'])
        self.assertFalse(self.timer_enabled)
        self.assertEqual('ready-for-manual-start', json.loads((self.ws / 'activation-complete.json').read_text())['phase'])
        self.assert_no_service_control()

    def test_second_mcsm_update_failure_rolls_back_files_mods_and_both_remote_configs(self):
        self.activation_fixture()
        files = self.files_before_activation()
        original_data = deploy.tree_hashes(self.old_data)
        self.fail_second_update = True
        with self.assertRaisesRegex(RuntimeError, 'second MCSM response failure'):
            deploy.activate()
        for path, original in files.items():
            self.assertEqual(original, path.read_bytes(), str(path.relative_to(self.root)))
        self.assertEqual(self.old_mods, {path.name: path.read_bytes() for path in (self.game / 'mods').glob('*.jar')})
        self.assertEqual(self.original_mcsm, self.mcsm_configs)
        self.assertEqual(original_data, deploy.tree_hashes(self.old_data))
        self.assertTrue(self.timer_enabled, 'legacy path watcher was not disabled before both updates succeeded')
        failure = json.loads((self.ws / 'activation-failure.json').read_text())
        self.assertTrue(failure['rollbackComplete'])
        self.assertFalse(failure['servicesStarted'])
        self.assertTrue(failure['targetDatabaseRetained'])
        self.assertTrue(failure['originalSchemasRetained'])
        self.assertFalse((self.ws / 'activation-complete.json').exists())
        self.assertFalse((self.gs / 'activation-complete.json').exists())
        updates = [data['instanceUuid'] for action, data in self.mcsm_calls if action == 'instance/update']
        self.assertEqual([deploy.GI, deploy.WI, deploy.GI, deploy.WI], updates)
        self.assert_no_service_control()

    def test_missing_original_daily_config_is_removed_again_after_activation_failure(self):
        self.activation_fixture()
        self.daily_path.unlink()
        self.fail_second_update = True
        with self.assertRaisesRegex(RuntimeError, 'second MCSM response failure'):
            deploy.activate()
        self.assertFalse(self.daily_path.exists())
        self.assertTrue(json.loads((self.ws / 'activation-failure.json').read_text())['rollbackComplete'])
        self.assert_no_service_control()

    def test_nondefault_original_data_directory_is_backed_up_and_copied_before_platform_init(self):
        hashes = deploy.tree_hashes(self.old_data)
        self.assertEqual(self.old_data, deploy.current_data())
        self.assertFalse((self.web / 'data').exists())
        deploy.backup()
        self.assertEqual(hashes, json.loads((self.backup_root / 'original-web-data.json').read_text()))
        with tarfile.open(self.backup_root / 'durable-files.tar.gz', 'r:gz') as archive:
            names = archive.getnames()
        self.assertTrue(any('/durable-original/nested/credentials/bridge.json' in name.replace('\\', '/') for name in names))
        deploy.migrate()
        for name, expected in hashes.items():
            self.assertEqual(expected, deploy.sha(self.new_data / name))
        self.assertEqual(hashes, deploy.tree_hashes(self.old_data))
        self.assertEqual(self.old_config, json.loads((self.web / 'manager.json').read_text()))
        next_config = json.loads((self.ws / 'manager-next.json').read_text())
        self.assertEqual(self.new_data.name, next_config['dataDirectory'])
        self.assertEqual(30, next_config['backupRetentionDays'])
        self.assertEqual('unified', next_config['storageMode'])
        self.assertEqual('verified', json.loads((self.ws / 'migration-complete.json').read_text())['phase'])
        self.assertTrue(any(action == 'copy' for action, _ in self.migration_calls))
        self.assertFalse(self.new_data == self.web / 'data')
        self.assert_no_service_control()

    def test_modified_original_data_after_backup_refuses_before_database_provisioning(self):
        deploy.backup()
        (self.old_data / 'platform-state.json').write_bytes(b'SYNTHETIC_CONCURRENT_CHANGE')
        with self.assertRaisesRegex(RuntimeError, 'Original application files changed'):
            deploy.migrate()
        self.assertFalse(self.sql_calls)
        self.assertFalse(self.new_data.exists())
        self.assertEqual(self.old_config, json.loads((self.web / 'manager.json').read_text()))
        self.assert_no_service_control()


if __name__ == '__main__':
    unittest.main(verbosity=2)
