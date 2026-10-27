#!/usr/bin/env python3
"""Destructive only to unique synthetic migtest_* schemas on an isolated local MariaDB.

Run with Python 3.10+; MIGRATION_TEST_CLIENT and MIGRATION_TEST_PORT override
the bundled Windows client and loopback fixture port. No production connection,
credentials, real player records, or external Python packages are used.
"""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import re
import unittest
import uuid

import migrate_storage as migration


CLIENT = os.environ.get(
    'MIGRATION_TEST_CLIENT',
    r'E:\minecraftserver\fabricmod\MemorialTitles\tools\mariadb-test\mariadb-11.4.9-winx64\bin\mariadb.exe',
)
PORT = int(os.environ.get('MIGRATION_TEST_PORT', '43317'))
CONFIRMATION = 'MIGRATE-EMPTY-UNIFIED'


def utf8(value):
    return "CONVERT(0x" + value.encode('utf-8').hex() + " USING utf8mb4)"


class AuditedClient:
    """Keep all real migration SQL while rejecting any source-side mutation."""

    def __init__(self, delegate, sources, target, fault=None):
        self.delegate = delegate
        self.target = target
        self.fault = fault
        self.fired = False
        self.statements = []
        self.source_mutations = []
        self.source_write = re.compile(
            r'\b(?:INSERT\s+INTO|REPLACE\s+INTO|UPDATE|DELETE\s+FROM|ALTER\s+TABLE|'
            r'DROP\s+TABLE|TRUNCATE\s+(?:TABLE\s+)?)\s+`(?:'
            + '|'.join(re.escape(source) for source in sources) + r')`\.', re.I,
        )

    def text(self, statement):
        self.statements.append(statement)
        if self.source_write.search(statement):
            self.source_mutations.append(statement)
            raise AssertionError('Migration attempted to write a source schema')
        ddl = 'CREATE TABLE `' + self.target + '`.' in statement
        insert = 'INSERT INTO `' + self.target + '`.' in statement
        if not self.fired and self.fault == 'ddl-error' and ddl:
            self.fired = True
            # A real SQL error after earlier CREATE statements models an ambiguous,
            # partially executed DDL batch rather than a mocked database response.
            return self.delegate.text(statement + '\nINVALID_SYNTHETIC_DDL;')
        if not self.fired and (
            (self.fault == 'ddl-interrupt' and ddl)
            or (self.fault == 'insert-interrupt' and insert)
        ):
            self.fired = True
            self.delegate.text(statement)
            raise KeyboardInterrupt('synthetic interruption after server-side execution')
        return self.delegate.text(statement)

    def rows(self, statement):
        value = self.text(statement)
        return [line.split('\t') for line in value.splitlines()] if value else []


class MigrationChecks(unittest.TestCase):
    def setUp(self):
        self.key = 'migtest_' + uuid.uuid4().hex[:12]
        self.sources = [self.key + suffix for suffix in ('_pm', '_mt', '_df')]
        self.target = self.key + '_unified'
        self.schemas = self.sources + [self.target]
        self.transport = dict(kind='client', executable=CLIENT, host='127.0.0.1', port=PORT, user='root')
        self.sql = migration.MariaClient(self.transport)
        self.report_path = (
            Path(__file__).resolve().parents[2] / 'build' / 'migration-checks' / (self.key + '.json')
        )
        self.plan = {
            'format': 1, 'target': self.target,
            'sources': [dict(schema=schema, prefix=prefix)
                        for schema, prefix in zip(self.sources, ('pm_', 'mt_', 'df_'))],
            'transport': self.transport, 'reportPath': str(self.report_path),
        }
        self.audit = AuditedClient(self.sql, self.sources, self.target)
        self.migrator = migration.Migrator(self.plan, client=self.audit)
        self.addCleanup(self.clean_schemas)
        self.make_fixture()

    def clean_schemas(self):
        # Every destructive name originates in this instance's random fixture key.
        if any(not schema.startswith(self.key + '_') for schema in self.schemas):
            raise AssertionError('Fixture cleanup escaped its generated schema prefix')
        self.sql.text('SET FOREIGN_KEY_CHECKS=0;\n' + '\n'.join(
            'DROP DATABASE IF EXISTS ' + migration.identifier(schema) + ';' for schema in self.schemas
        ))

    def make_fixture(self):
        pm, mt, df = [migration.identifier(schema) for schema in self.sources]
        unicode_name = utf8("合成玩家🌿'\"\t换行\n末尾")
        payload = utf8(json.dumps({
            'title': '云归旧岫', 'emoji': '🐈', 'null': None, 'empty': '',
            'escaped': '\\n\t"', 'nested': {'values': [1, True, None]},
        }, ensure_ascii=False, separators=(',', ':')))
        sql = ['CREATE DATABASE ' + migration.identifier(schema)
               + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;' for schema in self.schemas]
        sql += [
            f'CREATE TABLE {pm}.pm_profiles (group_id INT NOT NULL, external_id BINARY(16) NOT NULL,'
            'name VARCHAR(128) NOT NULL, note LONGTEXT NULL, PRIMARY KEY(group_id,external_id)) ENGINE=InnoDB;',
            f'CREATE TABLE {pm}.pm_sequence (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,'
            'amount DECIMAL(20,8) NOT NULL DEFAULT 0, empty_text VARCHAR(10) NULL) ENGINE=InnoDB;',
            f'CREATE TABLE {mt}.mt_owners (group_id INT NOT NULL, external_id BINARY(16) NOT NULL,'
            'slot INT NOT NULL, payload JSON NOT NULL, nullable_text LONGTEXT NULL, octets LONGBLOB NULL,'
            'PRIMARY KEY(group_id,external_id,slot), CONSTRAINT mt_owner_profile '
            f'FOREIGN KEY(group_id,external_id) REFERENCES {pm}.pm_profiles(group_id,external_id) '
            'ON DELETE RESTRICT ON UPDATE CASCADE) ENGINE=InnoDB;',
            f'CREATE TABLE {df}.df_records (owner_group INT NULL, owner_id BINARY(16) NULL,'
            'owner_slot INT NULL, day DATE NOT NULL, payload JSON NOT NULL, PRIMARY KEY(day),'
            'CONSTRAINT df_record_owner FOREIGN KEY(owner_group,owner_id,owner_slot) '
            f'REFERENCES {mt}.mt_owners(group_id,external_id,slot) ON DELETE RESTRICT) ENGINE=InnoDB;',
        ]
        for schema, prefix in zip((pm, mt, df), ('pm', 'mt', 'df')):
            sql += [f'CREATE TABLE {schema}.{prefix}_schema (id INT PRIMARY KEY, version INT NOT NULL) ENGINE=InnoDB;',
                    f'INSERT INTO {schema}.{prefix}_schema VALUES(1,1);']
        sql += [
            f'INSERT INTO {pm}.pm_profiles VALUES'
            f'(7,0x00112233445566778899aabbccddeeff,{unicode_name},NULL),'
            f'(7,0xffeeddccbbaa99887766554433221100,{utf8("空值与空串")},\'\');',
            f'INSERT INTO {pm}.pm_sequence(id,amount,empty_text) VALUES(1,-1234567890.12345678,\'\'),(999,0,NULL);',
            f'DELETE FROM {pm}.pm_sequence WHERE id=999;',
            f'INSERT INTO {mt}.mt_owners VALUES'
            f'(7,0x00112233445566778899aabbccddeeff,3,{payload},NULL,0x00090a0d27ff807f),'
            f'(7,0xffeeddccbbaa99887766554433221100,4,{payload},\'\',UNHEX(\'\'));',
            f'INSERT INTO {df}.df_records VALUES'
            f'(7,0x00112233445566778899aabbccddeeff,3,\'2026-10-27\',{payload}),'
            f'(NULL,NULL,NULL,\'2026-10-28\',{payload});',
        ]
        self.sql.text('\n'.join(sql))
        self.initial = self.migrator.inspect()

    def copied(self, client=None):
        migrator = self.migrator if client is None else migration.Migrator(self.plan, client=client)
        report = migrator.copy(CONFIRMATION)
        self.assertEqual('copied-and-verified', report['phase'])
        return migrator, report

    def assert_sources_unchanged(self):
        for table in self.initial['tables']:
            self.assertEqual(table['fingerprint'], self.migrator.fingerprint(
                table['schema'], table['table'], table['columns']))
            self.assertEqual(table['autoIncrement'], self.migrator.auto_increment(
                table['schema'], table['table']))
        self.assertFalse(self.audit.source_mutations)

    def assert_quarantine(self, error_name):
        self.assertTrue(self.report_path.is_file())
        report = json.loads(self.report_path.read_text('utf-8'))
        self.assertEqual('failed-target-quarantined', report['phase'])
        self.assertEqual(error_name, report['error'])
        self.assertGreater(len(self.sql.rows('SELECT TABLE_NAME FROM information_schema.TABLES '
            'WHERE TABLE_SCHEMA=' + migration.literal(self.target))), 0)
        self.assert_sources_unchanged()
        with self.assertRaisesRegex(ValueError, 'not empty'):
            self.migrator.copy(CONFIRMATION)

    def test_lossless_unicode_null_binary_json_and_read_only_sources(self):
        migrator, report = self.copied()
        self.assertEqual(7, len(report['tables']))
        self.assertEqual(2, len(report['foreignKeys']))
        self.assert_sources_unchanged()
        for table in report['tables']:
            self.assertEqual(table['fingerprint'], migrator.fingerprint(
                self.target, table['table'], table['columns']))
        source = migration.identifier(self.sources[1])
        target = migration.identifier(self.target)
        projection = 'SELECT group_id,HEX(external_id),slot,HEX(payload),nullable_text IS NULL,'
        projection += 'HEX(nullable_text),HEX(octets) FROM {}.mt_owners ORDER BY slot;'
        self.assertEqual(self.sql.rows(projection.format(source)), self.sql.rows(projection.format(target)))
        self.assertEqual(self.sources, [entry['schema'] for entry in report['tables']
            if entry['table'].endswith('_schema')])

    def test_auto_increment_keeps_deleted_high_water_mark(self):
        migrator, report = self.copied()
        self.assertEqual(1000, migrator.auto_increment(self.target, 'pm_sequence'))
        item = next(t for t in report['tables'] if t['table'] == 'pm_sequence')
        self.assertEqual(1000, item['autoIncrement'])
        self.sql.text('INSERT INTO ' + migration.identifier(self.target)
            + '.pm_sequence(amount) VALUES(5);')
        self.assertEqual([['1000']], self.sql.rows('SELECT MAX(id) FROM '
            + migration.identifier(self.target) + '.pm_sequence;'))
        self.assert_sources_unchanged()

    def test_nonempty_target_and_missing_confirmation_refuse_without_source_writes(self):
        with self.assertRaisesRegex(ValueError, 'confirmation'):
            self.migrator.copy('')
        self.sql.text('CREATE TABLE ' + migration.identifier(self.target) + '.sentinel (id INT);')
        with self.assertRaisesRegex(ValueError, 'not empty'):
            self.migrator.copy(CONFIRMATION)
        self.assertFalse(self.report_path.exists())
        self.assert_sources_unchanged()

    def test_later_source_row_change_is_detected(self):
        migrator, report = self.copied()
        self.sql.text('UPDATE ' + migration.identifier(self.sources[0])
            + '.pm_sequence SET empty_text=\'changed\' WHERE id=1;')
        with self.assertRaisesRegex(RuntimeError, 'Source changed'):
            migrator.verify(report)

    def test_later_source_high_water_mark_change_is_detected(self):
        migrator, report = self.copied()
        self.sql.text('ALTER TABLE ' + migration.identifier(self.sources[0])
            + '.pm_sequence AUTO_INCREMENT=1300;')
        with self.assertRaisesRegex(RuntimeError, 'Source changed|Source.*increment'):
            migrator.verify(report)

    def test_later_source_ddl_change_is_detected_without_row_changes(self):
        migrator, report = self.copied()
        self.sql.text('ALTER TABLE ' + migration.identifier(self.sources[0])
            + '.pm_profiles ADD fixture_later_column VARCHAR(16) NULL;')
        with self.assertRaisesRegex(RuntimeError, 'Source table definition changed'):
            migrator.verify(report)

    def test_ddl_failure_marks_target_quarantined(self):
        fault = AuditedClient(self.sql, self.sources, self.target, 'ddl-error')
        with self.assertRaises(RuntimeError):
            migration.Migrator(self.plan, client=fault).copy(CONFIRMATION)
        self.assertTrue(fault.fired)
        self.assert_quarantine('RuntimeError')

    def test_ddl_interrupt_marks_target_quarantined(self):
        fault = AuditedClient(self.sql, self.sources, self.target, 'ddl-interrupt')
        with self.assertRaises(KeyboardInterrupt):
            migration.Migrator(self.plan, client=fault).copy(CONFIRMATION)
        self.assertTrue(fault.fired)
        self.assert_quarantine('KeyboardInterrupt')

    def test_copy_interrupt_marks_partial_target_quarantined(self):
        fault = AuditedClient(self.sql, self.sources, self.target, 'insert-interrupt')
        with self.assertRaises(KeyboardInterrupt):
            migration.Migrator(self.plan, client=fault).copy(CONFIRMATION)
        self.assertTrue(fault.fired)
        self.assert_quarantine('KeyboardInterrupt')

    def test_composite_foreign_keys_redirect_and_enforce_after_copy(self):
        migrator, report = self.copied()
        self.assertEqual({self.target}, {fk['parentSchema'] for fk in migrator.foreign_keys(self.target)})
        self.assertEqual([2, 3], sorted(len(fk['columns']) for fk in report['foreignKeys']))
        with self.assertRaises(RuntimeError):
            self.sql.text('INSERT INTO ' + migration.identifier(self.target)
                + '.df_records VALUES(99,0x00112233445566778899aabbccddeeff,3,\'2026-10-29\',\'{}\');')
        migrator.verify(report)
        self.assert_sources_unchanged()

    def test_removed_foreign_key_is_rejected(self):
        migrator, report = self.copied()
        self.sql.text('ALTER TABLE ' + migration.identifier(self.target)
            + '.df_records DROP FOREIGN KEY df_record_owner;')
        with self.assertRaisesRegex(RuntimeError, 'foreign key missing'):
            migrator.verify(report)

    def test_orphaned_composite_foreign_key_is_rejected_even_with_matching_hash(self):
        migrator, report = self.copied()
        self.sql.text('SET FOREIGN_KEY_CHECKS=0; UPDATE ' + migration.identifier(self.target)
            + '.df_records SET owner_slot=777 WHERE owner_group=7;')
        changed_report = copy.deepcopy(report)
        changed = next(t for t in changed_report['tables'] if t['table'] == 'df_records')
        # Make hash validation accept the target on purpose: FK integrity must be an
        # independent gate rather than an incidental consequence of row hashing.
        self.sql.text('SET FOREIGN_KEY_CHECKS=0; UPDATE ' + migration.identifier(self.sources[2])
            + '.df_records SET owner_slot=777 WHERE owner_group=7;')
        changed['fingerprint'] = migrator.fingerprint(
            changed['schema'], changed['table'], changed['columns'])
        with self.assertRaisesRegex(RuntimeError, 'orphan rows'):
            migrator.verify(changed_report)

    def test_platform_init_accepts_new_columns_tables_and_version_metadata(self):
        migrator, report = self.copied()
        target = migration.identifier(self.target)
        self.sql.text(f'ALTER TABLE {target}.pm_profiles ADD platform_flag BOOLEAN NOT NULL DEFAULT FALSE;'
            f'CREATE TABLE {target}.pm_global_profiles (uuid CHAR(36) PRIMARY KEY, name VARCHAR(64));'
            f'UPDATE {target}.pm_schema SET version=2 WHERE id=1;'
            f'UPDATE {target}.mt_schema SET version=2 WHERE id=1;'
            f'UPDATE {target}.df_schema SET version=2 WHERE id=1;'
            f'ALTER TABLE {target}.pm_sequence AUTO_INCREMENT=1200;')
        with self.assertRaisesRegex(RuntimeError, 'fingerprint mismatch|increment counter mismatch'):
            migrator.verify(copy.deepcopy(report))
        migrator.verify(report, after_platform_init=True)
        for table in report['tables']:
            if table['table'] not in migration.VERSION_TABLES:
                self.assertEqual(table['fingerprint'], migrator.fingerprint(
                    self.target, table['table'], table['columns']))
        self.assert_sources_unchanged()
        self.sql.text(f'UPDATE {target}.pm_profiles SET name=\'modified\' WHERE group_id=7;')
        with self.assertRaisesRegex(RuntimeError, 'fingerprint mismatch'):
            migrator.verify(report, after_platform_init=True)

    def test_lowered_auto_increment_is_rejected_without_row_changes(self):
        migrator, report = self.copied()
        self.sql.text('ALTER TABLE ' + migration.identifier(self.target) + '.pm_sequence AUTO_INCREMENT=10;')
        with self.assertRaisesRegex(RuntimeError, 'increment counter mismatch'):
            migrator.verify(report, after_platform_init=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
