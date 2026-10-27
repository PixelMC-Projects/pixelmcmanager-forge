#!/usr/bin/env python3
"""Explicit maintenance phases. No phase starts, stops, restarts or kills a service.

Run on the host, from a protected payload directory. Source schemas are retained.
The operator separately schedules stopserver and starts DB/web/game in order.
"""
from pathlib import Path
import argparse
import copy
import gzip
import hashlib
import json
import os
import runpy
import secrets
import re
import shlex
import shutil
import subprocess
import tarfile
import time

from migrate_storage import Migrator, MariaClient, atomic_json

SOURCES = [{'schema': 'pixelmc_manager', 'prefix': 'pm_'}, {'schema': 'memorialtitles', 'prefix': 'mt_'}, {'schema': 'dailyfortunes', 'prefix': 'df_'}]
TARGET = 'pixelmc_platform'


def configure(site):
    global GAME, DBROOT, WEB, GI, WI, GAME_CONTAINER, WEB_CONTAINER, DB_CONTAINER
    global GS, WS, BACKUP, NEW_DATA, TRANSPORT, NETWORK, DAEMON_CONFIG, RESTART_MODULE
    GAME, DBROOT, WEB = (Path(site[key]) for key in ('gameRoot', 'databaseRoot', 'webRoot'))
    for path in (GAME, DBROOT, WEB):
        if not path.is_absolute() or '..' in path.parts or path.is_symlink() or not path.is_dir():
            raise ValueError('Use existing absolute non-symlink site roots')
    if not WEB.is_relative_to(DBROOT) or GAME == WEB:
        raise ValueError('Unexpected site root relationships')
    GI, WI = site['gameInstance'], site['webInstance']
    if GI == WI or any(not re.fullmatch('[a-f0-9]{32}', value) for value in (GI, WI)):
        raise ValueError('Distinct MCSM instance identities required')
    GAME_CONTAINER, WEB_CONTAINER, DB_CONTAINER = (site[key] for key in ('gameContainer', 'webContainer', 'databaseContainer'))
    NETWORK = site['databaseNetwork']
    if any(not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', value)
            for value in (GAME_CONTAINER, WEB_CONTAINER, DB_CONTAINER, NETWORK)):
        raise ValueError('Invalid container or network name')
    DAEMON_CONFIG, RESTART_MODULE = Path(site['daemonConfig']), Path(site['restartModule'])
    if not DAEMON_CONFIG.is_absolute() or not RESTART_MODULE.is_absolute():
        raise ValueError('Absolute daemon paths required')
    GS = GAME / 'pixelmcmanager/unified-20261027'
    WS = WEB / 'deployment/unified-20261027'
    BACKUP = DBROOT / 'maintenance-backups/unified-20261027'
    NEW_DATA = WEB / 'data-unified-20261027'
    TRANSPORT = {'kind': 'docker', 'container': DB_CONTAINER, 'defaultsFile': site['databaseDefaultsFile']}


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def inspect_container(name):
    result = subprocess.run(['docker', 'inspect', name], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    if result.returncode:
        message = result.stderr.decode('utf-8', errors='replace').lower()
        if result.stdout.strip() in (b'', b'[]') and ('no such object:' in message or 'no such container:' in message):
            # MCSManager removes stopped Docker containers; absence is a stopped state.
            return {'State': {'Running': False, 'Restarting': False, 'Pid': 0, 'Status': 'absent', 'StartedAt': ''}}
        raise RuntimeError('Cannot verify Docker state; maintenance phase refused')
    return json.loads(result.stdout)[0]


def tree_hashes(directory):
    result = {}
    for file in directory.rglob('*'):
        if file.is_symlink():
            raise RuntimeError('Unexpected symlink in durable application data')
        if file.is_file():
            result[str(file.relative_to(directory))] = sha(file)
    return result


def current_data():
    config = json.loads((WEB / 'manager.json').read_text())
    relative = Path(config['dataDirectory'])
    if relative.is_absolute() or '..' in relative.parts:
        raise RuntimeError('Unexpected original data directory')
    directory = WEB / relative
    if not directory.resolve().is_relative_to(WEB.resolve()) or directory.is_symlink() or not directory.is_dir():
        raise RuntimeError('Original durable data directory is unsafe or absent')
    return directory


def stopped():
    for name in (GAME_CONTAINER, WEB_CONTAINER):
        state = inspect_container(name)['State']
        if state['Running'] or state.get('Restarting') or state['Pid']:
            raise RuntimeError('Game and web must both be fully stopped before this phase')
    if not inspect_container(DB_CONTAINER)['State']['Running']:
        raise RuntimeError('Database must be running for migration')


def daemon():
    return runpy.run_path(str(RESTART_MODULE))['DaemonClient'](str(DAEMON_CONFIG))


def verified_payload(payload):
    bundle = json.loads((payload / 'bundle.json').read_text())
    for name, expected in bundle['files'].items():
        file = payload / name
        if Path(name).is_absolute() or '..' in Path(name).parts or not file.is_file() or file.is_symlink() or sha(file) != expected:
            raise RuntimeError('Payload hash/path mismatch')
    return bundle


def stage(payload):
    bundle = verified_payload(payload)
    if GS.exists() or WS.exists():
        raise RuntimeError('Stage already exists; inspect it rather than overwriting')
    before = {n: inspect_container(n)['State'] for n in (GAME_CONTAINER, WEB_CONTAINER, DB_CONTAINER)}
    shutil.copytree(payload, WS)
    client = daemon()
    try:
        configurations = {}
        for instance, root in ((GI, GAME), (WI, WEB)):
            config = client.request('instance/detail', {'instanceUuid': instance})['data']['config']
            if Path(config['cwd']).resolve() != root.resolve():
                raise RuntimeError('MCSM instance path does not match this site')
            configurations[instance] = config
        atomic_json(WS / 'pre-maintenance-mcsm.json', configurations)
    finally:
        client.close()
    GS.mkdir(mode=0o700, parents=True)
    (GS / 'classes').mkdir(mode=0o700)
    for file in ['start-game.sh', 'classes/UnifiedReadiness.class']:
        shutil.copy2(WS / file, GS / file)
    subprocess.run(['bash', '-n', str(GS / 'start-game.sh')], check=True)
    after = {n: inspect_container(n)['State'] for n in before}
    if before != after:
        raise RuntimeError('Service state changed during staging; inspect before proceeding')
    atomic_json(WS / 'stage-report.json', {'phase': 'staged-only', 'files': bundle['files'], 'state': after})
    print('Verified payload staged; active mods, configs and service states were not changed.')


def backup():
    stopped()
    if BACKUP.exists():
        raise RuntimeError('Maintenance backup already exists; inspect rather than overwrite')
    BACKUP.mkdir(mode=0o700, parents=True)
    client = daemon()
    try:
        configurations = {}
        for instance in (GI, WI):
            detail = client.request('instance/detail', {'instanceUuid': instance})['data']
            if detail['status'] != 0:
                raise RuntimeError('MCSM must also report both instances stopped')
            configurations[instance] = detail['config']
        atomic_json(BACKUP / 'mcsm-configs-at-shutdown.json', configurations)
        original = json.loads((WS / 'pre-maintenance-mcsm.json').read_text())
        for instance in (GI, WI):
            allowed = copy.deepcopy(original[instance])
            allowed['eventTask']['autoRestart'] = configurations[instance]['eventTask']['autoRestart']
            if 'lastDatetime' in configurations[instance]:
                allowed['lastDatetime'] = configurations[instance]['lastDatetime']
                original[instance]['lastDatetime'] = configurations[instance]['lastDatetime']
            if allowed != configurations[instance]:
                raise RuntimeError('Instance configuration changed since staging; inspect before migration')
        atomic_json(BACKUP / 'mcsm-configs.json', original)
    finally:
        client.close()
    sql_file = BACKUP / 'original-three-schemas.sql'
    command = ['docker', 'exec', '-i', '--user', '0', DB_CONTAINER, 'mariadb-dump',
        '--defaults-extra-file=' + TRANSPORT['defaultsFile'], '--single-transaction', '--quick',
        '--skip-lock-tables', '--hex-blob', '--routines', '--events', '--triggers',
        '--default-character-set=utf8mb4', '--databases', *[s['schema'] for s in SOURCES]]
    with sql_file.open('xb') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, timeout=240)
    if result.returncode or sql_file.stat().st_size < 1000:
        raise RuntimeError('Full original SQL backup failed; no migration performed')
    raw_digest = sha(sql_file)
    with sql_file.open('rb') as source, gzip.open(str(sql_file) + '.gz', 'xb') as target:
        shutil.copyfileobj(source, target)
    digest = hashlib.sha256()
    with gzip.open(str(sql_file) + '.gz', 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != raw_digest:
        raise RuntimeError('Compressed SQL backup failed readback verification')
    archive = BACKUP / 'durable-files.tar.gz'
    with tarfile.open(archive, 'x:gz', dereference=False) as target:
        items = [current_data(), WEB / 'manager.json', WEB / 'binding.json', GAME / 'config',
            GAME / 'automodpack', GAME / 'pixelmcmanager', GAME / 'server.properties',
            DBROOT / 'offsite/backup.py', DBROOT / 'offsite/config.json', DBROOT / 'secrets']
        for item in items:
            if item.exists():
                target.add(item, arcname=str(item).lstrip('/'), recursive=True)
        for item in (GAME / 'mods').glob('*.jar'):
            if any(prefix in item.name.lower() for prefix in ('pixelmcmanager', 'memorialtitles', 'dailyfortunes', 'framedblocks')):
                target.add(item, arcname=str(item).lstrip('/'))
    with tarfile.open(archive, 'r:gz') as target:
        if not target.getnames():
            raise RuntimeError('Durable file archive unexpectedly empty')
    with gzip.open(archive, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            pass  # Consume to EOF so gzip CRC/truncation errors cannot be missed.
    atomic_json(BACKUP / 'original-web-data.json', tree_hashes(current_data()))
    plan = {'format': 1, 'transport': TRANSPORT, 'sources': SOURCES, 'target': TARGET, 'reportPath': str(WS / 'migration-report.json')}
    migrator = Migrator(plan)
    original = migrator.inspect()
    atomic_json(BACKUP / 'original-inventory.json', original)
    atomic_json(WS / 'migration-plan.json', plan)
    atomic_json(BACKUP / 'complete.json', {'at': int(time.time()), 'files': {p.name: sha(p) for p in BACKUP.iterdir() if p.is_file()}})
    print('Stopped-state SQL and durable files backed up and read back; source databases retained.')


def migrate():
    stopped()
    complete = json.loads((BACKUP / 'complete.json').read_text())
    for name, expected in complete['files'].items():
        if sha(BACKUP / name) != expected:
            raise RuntimeError('Maintenance backup changed; migration refused')
    if NEW_DATA.exists() or (WS / 'manager-next.json').exists():
        raise RuntimeError('A prior migration attempt exists; inspect before retrying')
    old_config = json.loads((WEB / 'manager.json').read_text())
    original_data = current_data()
    expected_files = json.loads((BACKUP / 'original-web-data.json').read_text())
    if tree_hashes(original_data) != expected_files:
        raise RuntimeError('Original application files changed since stopped-state backup')
    sql = MariaClient(TRANSPORT)
    if sql.rows("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='pixelmc_platform';"):
        raise RuntimeError('Target schema exists; never overwrite an existing target')
    password = secrets.token_urlsafe(36)
    secret = DBROOT / 'secrets/platform-password'
    if secret.exists():
        raise RuntimeError('A platform secret already exists; inspect previous attempt')
    secret.write_text(password + '\n'); secret.chmod(0o600)
    sql.text("CREATE DATABASE pixelmc_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
        "CREATE USER 'pixelmc_platform'@'%' IDENTIFIED BY '" + password + "';\n"
        "GRANT ALL PRIVILEGES ON pixelmc_platform.* TO 'pixelmc_platform'@'%';")
    plan = json.loads((WS / 'migration-plan.json').read_text())
    migrator = Migrator(plan)
    migrator.copy('MIGRATE-EMPTY-UNIFIED')
    shutil.copytree(original_data, NEW_DATA)
    if tree_hashes(NEW_DATA) != expected_files:
        raise RuntimeError('Durable application file copy mismatch')
    config = copy.deepcopy(old_config)
    config['database'] = {'url': old_config['database']['url'].rsplit('/', 1)[0] + '/' + TARGET, 'user': 'pixelmc_platform', 'password': password}
    config.pop('titlesDatabase', None); config.pop('fortunesDatabase', None)
    config.update(storageMode='unified', dataDirectory=NEW_DATA.name, titlesEnabled=True, fortunesEnabled=True)
    retention = sql.rows("SELECT JSON_UNQUOTE(JSON_EXTRACT(settings,'$.retentionDays')) FROM pixelmc_platform.mt_state WHERE id=1;")
    if len(retention) != 1 or not retention[0][0].isdigit():
        raise RuntimeError('Cannot preserve existing backup retention')
    config['backupRetentionDays'] = int(retention[0][0])
    atomic_json(WS / 'manager-next.json', config)
    jar = WS / 'pixelmc-manager-service-0.6.0.jar'
    env = dict(os.environ, TZ='Asia/Shanghai')
    # Host Java executes only the copied target config, never the old live database.
    host_config = copy.deepcopy(config)
    host_config['database']['url'] = 'jdbc:mariadb://127.0.0.1:3306/pixelmc_platform'
    # Resolve the database container address; no database public port is required.
    address = inspect_container(DB_CONTAINER)['NetworkSettings']['Networks'][NETWORK]['IPAddress']
    host_config['database']['url'] = 'jdbc:mariadb://' + address + ':3306/pixelmc_platform'
    host_config['dataDirectory'] = str(NEW_DATA)
    host_file = WS / 'manager-host-init.json'
    atomic_json(host_file, host_config)
    result = subprocess.run(['java', '-Xmx512m', '-Duser.timezone=Asia/Shanghai', '-jar', str(jar), 'platform-init', str(host_file)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, env=env)
    (WS / 'platform-init.private.log').write_bytes(result.stdout)
    if result.returncode:
        raise RuntimeError('Platform init failed; original databases and config are intact')
    migrator.verify(after_platform_init=True)
    expected_roster = {row[0] for row in sql.rows("SELECT uuid FROM (SELECT uuid FROM pixelmc_manager.pm_players "
        "UNION SELECT uuid FROM memorialtitles.mt_players UNION SELECT uuid FROM dailyfortunes.df_players) roster "
        "WHERE uuid NOT IN (SELECT uuid FROM pixelmc_manager.pm_players WHERE "
        "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.fake')),'false') IN ('true','1')); ")}
    actual_roster = {row[0] for row in sql.rows('SELECT uuid FROM pixelmc_platform.pm_profiles '
        'WHERE uuid NOT IN (SELECT uuid FROM pixelmc_platform.pm_profile_exclusions);')}
    if expected_roster != actual_roster:
        raise RuntimeError('Global identity union does not match the original real-player stores')
    # Account hashes/bridge secrets/avatars must remain byte-identical after module bootstrap.
    for rel, digest in expected_files.items():
        if rel.startswith(('credentials/', 'titles/avatars/')) or rel == 'titles/web-accounts.json':
            if sha(original_data / rel) != digest or sha(NEW_DATA / rel) != digest:
                raise RuntimeError('Account, avatar or bridge asset was modified during bootstrap')
    old_accounts = NEW_DATA / 'titles/web-accounts.json'
    if old_accounts.exists() and sha(old_accounts) != sha(NEW_DATA / 'platform/web-accounts.json'):
        raise RuntimeError('Platform account hashes differ from original accounts')
    # The public config lives at WEB/manager.json, so its relative dataDirectory resolves correctly there.
    atomic_json(WS / 'migration-complete.json', {'phase': 'verified', 'at': int(time.time()), 'target': TARGET,
        'globalProfiles': len(actual_roster)})
    print('Empty-target copy and platform initialization verified; originals retained, no service started.')


def activate_changes():
    stopped()
    bundle = verified_payload(WS)
    if json.loads((WS / 'migration-complete.json').read_text())['phase'] != 'verified':
        raise RuntimeError('Migration is not complete')
    Migrator(json.loads((WS / 'migration-plan.json').read_text())).verify(after_platform_init=True)
    release = WEB / 'releases/0.6.0'
    retired = BACKUP / 'retired-mods'
    old_backup = DBROOT / 'offsite/backup.py'
    if release.exists() or retired.exists():
        raise RuntimeError('An activation attempt already exists; inspect before proceeding')
    if sha(old_backup) != bundle['oldOffsiteSha256']:
        raise RuntimeError('Offsite worker changed; inspect before replacing')
    existing = {p.name: sha(p) for p in (GAME / 'mods').glob('*.jar')
        if any(prefix in p.name.lower() for prefix in ('pixelmcmanager', 'memorialtitles', 'dailyfortunes', 'framedblocks'))}
    if existing != bundle['expectedActiveMods']:
        raise RuntimeError('Active mod versions changed since staging; inspect before activation')
    for name in bundle['mods']:
        if name not in bundle['expectedActiveMods'] and (GAME / 'mods' / name).exists():
            raise RuntimeError('A newly staged mod already exists; refusing to overwrite it')
    originals = json.loads((BACKUP / 'mcsm-configs.json').read_text())
    game = copy.deepcopy(originals[GI]); web = copy.deepcopy(originals[WI])
    args = shlex.split(game['startCommand'])
    if args[:2] != ['bash', 'pixelmcmanager/activate-manager.sh']:
        raise RuntimeError('Unexpected original game launch command')
    # MCSManager parses commands itself and preserves POSIX single quotes as data.
    # Keep the original argument text, replacing only the already-reviewed wrapper.
    old_prefix = 'bash pixelmcmanager/activate-manager.sh '
    if not game['startCommand'].startswith(old_prefix):
        raise RuntimeError('Unexpected original game command prefix')
    game['startCommand'] = 'bash pixelmcmanager/unified-20261027/start-game.sh ' + game['startCommand'][len(old_prefix):]
    argv = shlex.split(web['startCommand'])
    if argv[:2] != ['bash', 'deployment/manager-ui-0.5.2-20261026/launch-manager.sh']:
        raise RuntimeError('Unexpected original web launch command')
    argv = argv[2:]; index = argv.index('-jar')
    argv[index + 1] = '/data/releases/0.6.0/pixelmc-manager-service-0.6.0.jar'
    web['startCommand'] = shlex.join(argv)
    game['eventTask']['autoRestart'] = False
    web['eventTask']['autoRestart'] = False
    if game['docker'] != originals[GI]['docker'] or web['docker'] != originals[WI]['docker']:
        raise RuntimeError('Docker configuration changed')
    release.mkdir(mode=0o700)
    shutil.copy2(WS / 'pixelmc-manager-service-0.6.0.jar', release / 'pixelmc-manager-service-0.6.0.jar')
    retired.mkdir(mode=0o700)
    for file in (GAME / 'mods').glob('*.jar'):
        if any(prefix in file.name.lower() for prefix in ('pixelmcmanager', 'memorialtitles', 'dailyfortunes', 'framedblocks')):
            shutil.move(str(file), retired / file.name)
    for name in bundle['mods']:
        shutil.copy2(WS / name, GAME / 'mods' / name)
    config = json.loads((WS / 'manager-next.json').read_text())
    atomic_json(WEB / 'manager.json', config)
    title_config = json.loads((GAME / 'config/memorialtitles/server.json').read_text())
    title_config['mode'] = 'manager'
    atomic_json(GAME / 'config/memorialtitles/server.json', title_config)
    daily_file = GAME / 'config/dailyfortunes/server.json'
    if daily_file.exists():
        daily = json.loads(daily_file.read_text()); daily['mode'] = 'auto'
    else:
        daily = {'mode': 'auto'}
    atomic_json(daily_file, daily)
    auto_file = GAME / 'automodpack/automodpack-server.json'
    auto = json.loads(auto_file.read_text()); saved_security = {k: auto[k] for k in auto if k not in {'syncedFiles'}}
    names = bundle['clientMods']
    old = auto['syncedFiles']
    auto['syncedFiles'] = [p for p in old if not any(prefix in Path(p).name.lower() for prefix in ('memorialtitles', 'dailyfortunes', 'framedblocks', 'pixelmcmanager'))]
    auto['syncedFiles'].extend('/mods/' + n for n in names)
    if {k: auto[k] for k in saved_security} != saved_security:
        raise RuntimeError('AutoModpack security fields changed')
    atomic_json(auto_file, auto)
    (GS / 'active.sha256').write_text(''.join(sha(GAME / 'mods' / n) + '  mods/' + n + '\n' for n in bundle['mods']))
    shutil.copy2(WS / 'migration-complete.json', GS / 'migration-complete.json')
    shutil.copy2(WS / 'backup.unified.py', old_backup)
    old_backup.chmod(0o700)
    client = daemon()
    try:
        for instance, value in ((GI, game), (WI, web)):
            client.request('instance/update', {'instanceUuid': instance, 'config': value})
            saved = client.request('instance/detail', {'instanceUuid': instance})['data']['config']
            if saved != value:
                raise RuntimeError('MCSM config readback mismatch')
    finally:
        client.close()
    subprocess.run(['systemctl', 'disable', '--now', 'pixelmc-manager-ui-startup-0.5.2.path'], check=True, stdout=subprocess.DEVNULL)
    for directory in (GAME / 'pixelmcmanager').glob('deploy-*'):
        if directory.is_dir() and not (directory / 'superseded-by-unified-0.6.0.json').exists():
            atomic_json(directory / 'superseded-by-unified-0.6.0.json', {'at': int(time.time()), 'replacement': str(GS), 'oldAppliedMarkerPreserved': True})
    stopped()
    atomic_json(WS / 'activation-complete.json', {'at': int(time.time()), 'phase': 'ready-for-manual-start', 'database': TARGET,
        'webCpu': '7', 'jvmArgumentsPreserved': True, 'sourceSchemasRetained': True, 'clientUpdatedLocally': False})
    shutil.copy2(WS / 'activation-complete.json', GS / 'activation-complete.json')
    print('Activated for manual startup. Verify DB, manually start and verify WEB, then manually start GAME.')


def activate():
    stopped()
    verified_payload(WS)
    before = WS / 'activation-before'
    if before.exists():
        raise RuntimeError('A previous activation attempt exists; inspect its rollback report first')
    before.mkdir(mode=0o700)
    paths = [WEB / 'manager.json', GAME / 'config/memorialtitles/server.json', GAME / 'config/dailyfortunes/server.json',
        GAME / 'automodpack/automodpack-server.json', DBROOT / 'offsite/backup.py']
    saved = []
    for index, path in enumerate(paths):
        present = path.is_file()
        if present:
            shutil.copy2(path, before / str(index))
        saved.append({'path': str(path), 'file': str(index), 'present': present})
    atomic_json(before / 'files.json', saved)
    try:
        activate_changes()
    except BaseException as failure:
        problems = []
        try:
            stopped()
            bundle = verified_payload(WS)
            for item in saved:
                path = Path(item['path'])
                if item['present']:
                    shutil.copy2(before / item['file'], path)
                elif path.exists():
                    path.unlink()
            for name in bundle['mods']:
                path = GAME / 'mods' / name
                if name not in bundle['expectedActiveMods'] and path.exists():
                    if sha(path) != bundle['files'][name]:
                        raise RuntimeError('Installed mod changed unexpectedly; manual review required')
                    path.unlink()
            retired = BACKUP / 'retired-mods'
            for name, expected in bundle['expectedActiveMods'].items():
                path = GAME / 'mods' / name
                if not path.exists():
                    if sha(retired / name) != expected:
                        raise RuntimeError('Retired original mod checksum mismatch')
                    shutil.copy2(retired / name, path)
                if sha(path) != expected:
                    raise RuntimeError('Original mod rollback checksum mismatch')
            client = daemon()
            try:
                original = json.loads((BACKUP / 'mcsm-configs.json').read_text())
                for instance in (GI, WI):
                    client.request('instance/update', {'instanceUuid': instance, 'config': original[instance]})
                    if client.request('instance/detail', {'instanceUuid': instance})['data']['config'] != original[instance]:
                        raise RuntimeError('Original MCSM config rollback readback mismatch')
            finally:
                client.close()
            for marker in [GS / 'activation-complete.json', WS / 'activation-complete.json']:
                marker.unlink(missing_ok=True)
        except BaseException as rollback_error:
            problems.append(type(rollback_error).__name__)
        atomic_json(WS / 'activation-failure.json', {'failure': type(failure).__name__,
            'rollbackComplete': not problems, 'rollbackErrors': problems, 'servicesStarted': False,
            'targetDatabaseRetained': True, 'originalSchemasRetained': True})
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['stage', 'backup', 'migrate', 'activate'])
    parser.add_argument('--site', type=Path, required=True, help='Protected host-specific JSON; never commit or publish it')
    parser.add_argument('--payload', type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    os.umask(0o077)
    configure(json.loads(args.site.read_text()))
    {'stage': lambda: stage(args.payload), 'backup': backup, 'migrate': migrate, 'activate': activate}[args.phase]()


if __name__ == '__main__':
    main()
