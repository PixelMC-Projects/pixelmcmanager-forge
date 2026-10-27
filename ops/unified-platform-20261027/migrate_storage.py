#!/usr/bin/env python3
"""Lossless, empty-target MariaDB copy. Never mutates or drops a source schema.

Production uses the database container's protected defaults file. Local fixtures
may use a loopback-only client transport. No application credentials are printed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

IDENTIFIER = re.compile(r'[a-z][a-z0-9_]{0,63}\Z')
RESERVED = {'mysql', 'sys', 'information_schema', 'performance_schema'}
VERSION_TABLES = {'pm_schema', 'mt_schema', 'df_schema'}


def identifier(value):
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value) or value in RESERVED:
        raise ValueError('Invalid application schema/table identifier')
    return '`' + value + '`'


def literal(value):
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise ValueError('Invalid metadata identifier')
    return "'" + value + "'"


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError('Report must not be a symbolic link')
    temporary = path.with_name('.' + path.name + '.' + str(os.getpid()) + '.tmp')
    with temporary.open('x', encoding='utf-8') as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


class MariaClient:
    def __init__(self, config):
        if config['kind'] == 'docker':
            container = config['container']
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', container):
                raise ValueError('Invalid database container')
            defaults = config['defaultsFile']
            if not defaults.startswith('/data/secrets/') or '..' in defaults.split('/'):
                raise ValueError('Use the protected database client configuration')
            self.command = ['docker', 'exec', '-i', '--user', '0', container,
                'mariadb', '--defaults-extra-file=' + defaults]
        elif config['kind'] == 'client':
            if config.get('host') != '127.0.0.1' or config.get('user') != 'root':
                raise ValueError('Direct transport is limited to isolated loopback fixtures')
            executable = Path(config['executable'])
            if not executable.is_absolute() or not executable.is_file():
                raise ValueError('Local fixture client executable missing')
            port = int(config['port'])
            if not 1 <= port <= 65535:
                raise ValueError('Invalid fixture port')
            self.command = [str(executable), '--host=127.0.0.1', '--port=' + str(port), '--user=root']
        else:
            raise ValueError('Unknown SQL transport')
        self.command += ['--default-character-set=utf8mb4', '--batch', '--skip-column-names',
            '--raw', '--quick', '--connect-timeout=10']

    def text(self, statement):
        result = subprocess.run(self.command, input=statement.encode('utf-8'),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        if result.returncode:
            # SQL contains only validated identifiers, but never echo commands or credentials.
            raise RuntimeError('Database command failed; source data was not modified')
        return result.stdout.decode('utf-8').rstrip('\r\n')

    def rows(self, statement):
        value = self.text(statement)
        return [line.split('\t') for line in value.splitlines()] if value else []


class Migrator:
    def __init__(self, plan, client=None):
        if plan.get('format') != 1:
            raise ValueError('Unknown migration plan format')
        self.plan = plan
        self.target = plan['target']
        identifier(self.target)
        self.sources = plan['sources']
        if not self.sources or len(self.sources) > 32:
            raise ValueError('Unexpected source count')
        schemas = set()
        for source in self.sources:
            identifier(source['schema'])
            prefix = source['prefix']
            if not re.fullmatch(r'[a-z][a-z0-9_]*_', prefix):
                raise ValueError('Invalid namespace prefix')
            if source['schema'] == self.target or source['schema'] in schemas:
                raise ValueError('Source and target schemas must be distinct')
            schemas.add(source['schema'])
        self.sql = client or MariaClient(plan['transport'])
        self.report_path = Path(plan['reportPath'])
        if not self.report_path.is_absolute():
            raise ValueError('Use an absolute protected report path')

    def inventory(self):
        found = []
        names = set()
        for source in self.sources:
            schema = source['schema']
            tables = self.sql.rows('SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.TABLES '
                'WHERE TABLE_SCHEMA=' + literal(schema) + ' ORDER BY TABLE_NAME;')
            if not tables:
                raise ValueError('Source schema is absent or empty: ' + schema)
            for table, kind in tables:
                identifier(table)
                if kind != 'BASE TABLE' or not table.startswith(source['prefix']) or table in names:
                    raise ValueError('Unregistered/conflicting source table: ' + schema + '.' + table)
                names.add(table)
                columns = self.sql.rows('SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='
                    + literal(schema) + ' AND TABLE_NAME=' + literal(table) + ' ORDER BY ORDINAL_POSITION;')
                columns = [row[0] for row in columns]
                for column in columns:
                    identifier(column)
                generated = self.sql.rows('SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='
                    + literal(schema) + ' AND TABLE_NAME=' + literal(table) + " AND EXTRA LIKE '%GENERATED%';")
                if generated:
                    raise ValueError('Generated columns need an explicitly reviewed adapter')
                found.append({'schema': schema, 'table': table, 'columns': columns})
            if self.sql.rows('SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=' + literal(schema)):
                raise ValueError('Source triggers need an explicitly reviewed adapter')
            if self.sql.rows('SELECT ROUTINE_NAME FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=' + literal(schema)):
                raise ValueError('Source routines need an explicitly reviewed adapter')
        return found

    def fingerprint(self, schema, table, columns):
        # Hash each typed SQL value without exporting any player data; then sort row hashes.
        # Length + per-value SHA preserves NULL/empty and avoids large CONCAT packets.
        expressions = []
        for column in columns:
            col = identifier(column)
            expressions.append("IF(" + col + " IS NULL,'N',CONCAT('V',OCTET_LENGTH(CAST("
                + col + " AS BINARY)),':',SHA2(CAST(" + col + " AS BINARY),256)))")
        query = 'SELECT SHA2(CONCAT(' + ','.join(expressions) + '),256) AS row_hash FROM '
        query += identifier(schema) + '.' + identifier(table) + ' ORDER BY row_hash;'
        rows = self.sql.rows(query)
        digest = hashlib.sha256()
        for row in rows:
            if len(row) != 1 or not re.fullmatch(r'[0-9a-f]{64}', row[0]):
                raise RuntimeError('Unexpected row fingerprint')
            digest.update(row[0].encode('ascii') + b'\n')
        return {'rows': len(rows), 'sha256': digest.hexdigest()}

    def foreign_keys(self, schema):
        rows = self.sql.rows('SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,'
            'REFERENCED_COLUMN_NAME,REFERENCED_TABLE_SCHEMA FROM information_schema.KEY_COLUMN_USAGE '
            'WHERE TABLE_SCHEMA=' + literal(schema) + ' AND REFERENCED_TABLE_SCHEMA IS NOT NULL '
            'ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION;')
        groups = {}
        for table, constraint, column, parent, parent_column, parent_schema in rows:
            for name in (table, column, parent, parent_column, parent_schema):
                identifier(name)
            key = (table, constraint)
            entry = groups.setdefault(key, {'table': table, 'name': constraint,
                'parent': parent, 'parentSchema': parent_schema, 'columns': [], 'parentColumns': []})
            if entry['parent'] != parent or entry['parentSchema'] != parent_schema:
                raise RuntimeError('Inconsistent foreign key metadata')
            entry['columns'].append(column)
            entry['parentColumns'].append(parent_column)
        return list(groups.values())

    def auto_increment(self, schema, table):
        rows = self.sql.rows('SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='
            + literal(schema) + ' AND TABLE_NAME=' + literal(table) + ';')
        if len(rows) != 1:
            raise RuntimeError('Expected table missing')
        return None if rows[0][0] == 'NULL' else int(rows[0][0])

    def ddl_hash(self, schema, table):
        value = self.sql.text('SHOW CREATE TABLE ' + identifier(schema) + '.' + identifier(table) + ';')
        return hashlib.sha256(value.encode('utf-8')).hexdigest()

    def inspect(self):
        tables = self.inventory()
        for table in tables:
            table['fingerprint'] = self.fingerprint(table['schema'], table['table'], table['columns'])
            table['autoIncrement'] = self.auto_increment(table['schema'], table['table'])
            table['ddlSha256'] = self.ddl_hash(table['schema'], table['table'])
        foreign_keys = [fk for source in self.sources for fk in self.foreign_keys(source['schema'])]
        return {'format': 1, 'phase': 'inspected', 'target': self.target, 'tables': tables,
            'foreignKeys': foreign_keys,
            'at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}

    def copy(self, confirmation):
        if confirmation != 'MIGRATE-EMPTY-UNIFIED':
            raise ValueError('Explicit empty-target migration confirmation required')
        if not self.sql.rows('SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=' + literal(self.target)):
            raise ValueError('Target schema must be provisioned separately')
        if self.sql.rows('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=' + literal(self.target)):
            raise ValueError('Target is not empty; original schemas remain intact')
        report = self.inspect()
        report['phase'] = 'copying'
        atomic_json(self.report_path, report)
        try:
            ddls = []
            for table in report['tables']:
                schema, name = table['schema'], table['table']
                value = self.sql.text('SHOW CREATE TABLE ' + identifier(schema) + '.' + identifier(name) + ';')
                header, ddl = value.split('\t', 1)
                if header != name or not ddl.startswith('CREATE TABLE ' + identifier(name)):
                    raise RuntimeError('Unexpected source DDL')
                ddl = ddl.replace('CREATE TABLE ' + identifier(name),
                    'CREATE TABLE ' + identifier(self.target) + '.' + identifier(name), 1)
                # Only explicit FK qualifiers are redirected; column defaults/data are untouched.
                for source in self.sources:
                    ddl = re.sub(r'(REFERENCES\s+)' + re.escape(identifier(source['schema'])) + r'\.',
                        lambda match: match.group(1) + identifier(self.target) + '.', ddl)
                ddls.append(ddl + ';')
            # Preserve the original CREATE, including FKs and AUTO_INCREMENT counters.
            self.sql.text('USE ' + identifier(self.target) + '; SET FOREIGN_KEY_CHECKS=0;\n'
                + '\n'.join(ddls) + '\nSET FOREIGN_KEY_CHECKS=1;')
            for table in report['tables']:
                columns = ','.join(identifier(c) for c in table['columns'])
                self.sql.text('SET FOREIGN_KEY_CHECKS=0; INSERT INTO ' + identifier(self.target) + '.'
                    + identifier(table['table']) + ' (' + columns + ') SELECT ' + columns + ' FROM '
                    + identifier(table['schema']) + '.' + identifier(table['table']) + '; SET FOREIGN_KEY_CHECKS=1;')
            self.verify(report)
            report['phase'] = 'copied-and-verified'
            report['verifiedAt'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
            atomic_json(self.report_path, report)
            return report
        except BaseException as error:
            report.update(phase='failed-target-quarantined', error=type(error).__name__)
            atomic_json(self.report_path, report)
            raise

    def verify(self, report=None, after_platform_init=False):
        report = report or json.loads(self.report_path.read_text('utf-8'))
        if report['target'] != self.target:
            raise ValueError('Report target mismatch')
        for table in report['tables']:
            source = self.fingerprint(table['schema'], table['table'], table['columns'])
            if source != table['fingerprint']:
                raise RuntimeError('Source changed during maintenance: ' + table['table'])
            if self.auto_increment(table['schema'], table['table']) != table.get('autoIncrement'):
                raise RuntimeError('Source auto increment counter changed: ' + table['table'])
            if self.ddl_hash(table['schema'], table['table']) != table.get('ddlSha256'):
                raise RuntimeError('Source table definition changed: ' + table['table'])
            if after_platform_init and table['table'] in VERSION_TABLES:
                # Version markers can legitimately advance; all business tables remain exact.
                table['versionMetadataMayAdvance'] = True
                continue
            target = self.fingerprint(self.target, table['table'], table['columns'])
            if target != source:
                raise RuntimeError('Target row count/fingerprint mismatch: ' + table['table'])
            expected = table.get('autoIncrement')
            actual = self.auto_increment(self.target, table['table'])
            if expected is not None and (actual is None or actual < expected
                    or (not after_platform_init and actual != expected)):
                raise RuntimeError('Target auto increment counter mismatch: ' + table['table'])
        actual_keys = self.foreign_keys(self.target)
        if any(fk['parentSchema'] != self.target for fk in actual_keys):
            raise RuntimeError('Target foreign key still references an old schema')
        def signature(fk):
            return (fk['table'], fk['name'], fk['parent'], tuple(fk['columns']), tuple(fk['parentColumns']))
        actual_signatures = {signature(fk) for fk in actual_keys}
        for expected in report.get('foreignKeys', []):
            if signature(expected) not in actual_signatures:
                raise RuntimeError('Target foreign key missing: ' + expected['table'])
        for fk in actual_keys:
            present = ' AND '.join('c.' + identifier(c) + ' IS NOT NULL' for c in fk['columns'])
            joined = ' AND '.join('p.' + identifier(p) + '=c.' + identifier(c)
                for c, p in zip(fk['columns'], fk['parentColumns']))
            count = self.sql.rows('SELECT COUNT(*) FROM ' + identifier(self.target) + '.'
                + identifier(fk['table']) + ' c WHERE ' + present + ' AND NOT EXISTS (SELECT 1 FROM '
                + identifier(self.target) + '.' + identifier(fk['parent']) + ' p WHERE ' + joined + ');')
            if count != [['0']]:
                raise RuntimeError('Target foreign key orphan rows: ' + fk['table'])
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inspect', 'copy', 'verify'])
    parser.add_argument('plan', type=Path)
    parser.add_argument('--confirm', default='')
    parser.add_argument('--after-platform-init', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    migrator = Migrator(json.loads(args.plan.read_text('utf-8')))
    if args.action == 'copy':
        result = migrator.copy(args.confirm)
    elif args.action == 'verify':
        result = migrator.verify(after_platform_init=args.after_platform_init)
    else:
        result = migrator.inspect()
    print(json.dumps({'phase': result['phase'], 'target': result['target'],
        'tables': len(result['tables']), 'rows': sum(t['fingerprint']['rows'] for t in result['tables'])}, indent=2))


if __name__ == '__main__':
    main()
