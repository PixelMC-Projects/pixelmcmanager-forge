#!/usr/bin/env python3
"""Rehearse a real 0.5.2-web.1 -> 0.6.0 upgrade using only isolated loopback fixtures.

Old application code creates all source tables and business records. The reviewed
migrator copies into a new schema, then the candidate application initializes its
platform. Every legacy column is compared before/after. No production credentials
or external Python packages are used; failure leaves only this run's random test
schemas for inspection. Successful runs remove only schemas they created.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time
import uuid
import zipfile
import zlib

import migrate_storage as migration


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OLD = Path(r'C:\Users\Administrator\Documents\ChatGPT\服务器综合\outputs\releases\20261026\pixelmcmanager-forge-web-patch\pixelmc-manager-service-0.5.2-web.1.zip')
DEFAULT_CLIENT = Path(r'E:\minecraftserver\fabricmod\MemorialTitles\tools\mariadb-test\mariadb-11.4.9-winx64\bin\mariadb.exe')
DEFAULT_JAVA = Path(r'C:\Program Files\Java\jdk-17\bin\java.exe')


SEED_JAVA = r'''
import static cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.TitleService;
import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import com.pixelmc.manager.service.ServiceConfig;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class UpgradeSeed {
  static JsonObject fixture;
  static UUID id(String key) { return UUID.fromString(fixture.getAsJsonObject("players").get(key).getAsString()); }
  static <T> T waitFor(CompletableFuture<T> future) throws Exception { return future.get(30,TimeUnit.SECONDS); }
  static JsonObject rpc(FortuneBackend backend,String op,UUID player,Object... args) {
    JsonObject body=new JsonObject();JsonArray list=new JsonArray();list.add(player.toString());
    for(Object value:args)list.add(JSON.toJsonTree(value));body.add("args",list);
    return backend.rpc(op,body,player.toString(),Set.of(player));
  }
  static Title title(String key,String text) {
    return new Title(key,RichText.plain(text),"Synthetic historical description","Fixture pack","Fixture grant","Fixture",0,0,true,true,0);
  }
  public static void main(String[] args)throws Exception {
    Path configFile=Path.of(args[0]);ServiceConfig cfg=ServiceConfig.load(configFile);
    fixture=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
    var console=TitleService.Actor.consoleActor();
    try(TitleService titles=new TitleService(new Database(cfg.titlesDatabase()))) {
      waitFor(titles.reconnect());
      waitFor(titles.login(id("shared"),"TitleShared"));
      waitFor(titles.login(id("titleOnly"),"TitleOnly"));
      waitFor(titles.login(id("fake"),"alex"));
      waitFor(titles.saveTitle(console,title("fixture_memorial","云归旧岫"),0));
      waitFor(titles.saveTitle(console,title("fixture_reward","初会山川"),0));
      waitFor(titles.grant(console,List.of(id("shared"),id("titleOnly")),"fixture_memorial","Synthetic historical grant"));
      waitFor(titles.grant(console,List.of(id("fake")),"fixture_memorial","Synthetic preserved fake-player history"));
      waitFor(titles.privacy(id("shared"),"fixture_memorial",true,false));
      waitFor(titles.equip(id("shared"),"fixture_memorial"));
      waitFor(titles.toggleDisplayPosition(id("titleOnly"),DisplayPosition.TAB));
      waitFor(titles.setUnequippedReminder(id("titleOnly"),false));
      Settings x=titles.snapshot().settings();
      waitFor(titles.settings(console,new Settings("fixture_reward",x.head(),x.chat(),x.tab(),x.headScale(),x.headOffset(),x.chatPrefix(),x.retentionDays(),"Synthetic pack","2026-10-01")));
      waitFor(titles.login(id("rewardRecipient"),"RewardRecipient"));
    }
    try(FortuneBackend backend=new FortuneBackend(cfg.fortunesDatabase().url(),cfg.fortunesDatabase().user(),cfg.fortunesDatabase().password(),cfg.data(configFile).resolve("dailyfortunes"))) {
      rpc(backend,"login",id("shared"),"FortuneShared");
      JsonObject record=rpc(backend,"draw",id("shared")).getAsJsonObject("record");
      rpc(backend,"resolve",id("shared"),record.get("date").getAsString(),record.get("token").getAsString());
      rpc(backend,"archive",id("shared"),record.get("date").getAsString(),record.get("token").getAsString());
      rpc(backend,"login",id("fortuneOnly"),"FortuneOnly");
      JsonObject loose=rpc(backend,"draw",id("fortuneOnly")).getAsJsonObject("record");
      rpc(backend,"recover",id("fortuneOnly"),loose.get("date").getAsString(),"synthetic-recovery-request");
    }
    System.out.println("REHEARSAL_SEED_OK");
  }
}
'''


PROBE_JAVA = r'''
import com.pixelmc.manager.service.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class UpgradeProbe {
  static final List<String> failures=new ArrayList<>();
  static void check(boolean condition,String message) {
    System.out.println((condition?"PROBE PASS ":"PROBE FAIL ")+message);
    if(!condition)failures.add(message);
  }
  public static void main(String[] args)throws Exception {
    Path file=Path.of(args[0]);ServiceConfig cfg=ServiceConfig.load(file);
    JsonObject fixture=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
    Set<String> expected=new HashSet<>();fixture.getAsJsonObject("players").entrySet().stream().filter(e->!e.getKey().equals("fake")).forEach(e->expected.add(e.getValue().getAsString()));
    try(ManagerController controller=new ManagerController(cfg,cfg.data(file))) {
      controller.initializePlatform();
      Set<String> profiles=new HashSet<>();for(JsonElement p:controller.platform.profiles().getAsJsonArray("players"))profiles.add(p.getAsJsonObject().get("id").getAsString());
      check(profiles.equals(expected),"Global profile union matches old real-player stores");
      check(controller.titles.snapshot().players().keySet().stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet()).equals(expected),"Titles roster matches the global union");
      UUID fake=UUID.fromString(fixture.getAsJsonObject("players").get("fake").getAsString());
      check(!controller.titles.snapshot().players().containsKey(fake)
          && controller.titles.snapshot().ownership(fake,"fixture_memorial")!=null,
          "Excluded legacy fake holder remains in durable business history without reappearing in the real-player directory");
      JsonObject fortunes=controller.fortunes.request("GET","/players",new JsonObject(),Map.of(),"rehearsal",Set.of());
      check(fortunes.get("total").getAsInt()==expected.size(),"Fortune roster matches the global union");
      UUID pmOnly=UUID.fromString(fixture.getAsJsonObject("players").get("pmOnly").getAsString());
      check(controller.titles.snapshot().collection(pmOnly,pmOnly,true).isEmpty(),"Directory import awards no title to a zero-participation player");
      JsonObject p=controller.platform.profile(pmOnly.toString()).getAsJsonObject("profile");
      check(p.get("firstJoinedAt").getAsLong()==0,"Unknown historical join time remains zero");
      Path data=cfg.data(file);PlatformAccounts accounts=new PlatformAccounts(data.resolve("platform"));
      String password=Files.readString(Path.of(args[2])).stripTrailing();
      check(accounts.verify("fixture_owner",password)!=null,"Migrated original account hash authenticates successfully");
      JsonObject bridge=JsonParser.parseString(Files.readString(data.resolve("credentials/fixture-bridge.json"))).getAsJsonObject();
      check(controller.store.authenticate("fixture",bridge.get("token").getAsString()),"Original bridge token and database hash authenticate successfully");
      check(controller.platform.modules().size()==2,"Both native module manifests are registered");
      if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
      System.out.println("REHEARSAL_PROBE_OK "+expected.size());
    }
  }
}
'''


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sql_text(value):
    return 'CONVERT(0x' + str(value).encode('utf-8').hex() + ' USING utf8mb4)'


def json_file(path, value):
    migration.atomic_json(path, value)


def file_tree(root):
    if not root.is_dir():
        return {}
    return {str(path.relative_to(root)).replace('\\', '/'): sha(path)
            for path in sorted(root.rglob('*')) if path.is_file()}


def png(width=8, height=8):
    def chunk(name, value):
        return struct.pack('>I', len(value)) + name + value + struct.pack('>I', zlib.crc32(name + value) & 0xffffffff)
    scanlines = b''.join(b'\0' + bytes((128, 84, 45, 255)) * width for _ in range(height))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(scanlines)) + chunk(b'IEND', b'')


class Rehearsal:
    def __init__(self, args):
        self.args = args
        # This is an intentionally fixed, non-production fixture boundary.
        if args.port != 43317:
            raise ValueError('Only the isolated loopback MariaDB on port 43317 is permitted')
        self.key = 'upgrade_' + uuid.uuid4().hex[:12]
        self.work = ROOT / 'build' / 'unified-upgrade-rehearsals' / self.key
        self.work.mkdir(parents=True, exist_ok=False)
        self.sources = [self.key + suffix for suffix in ('_pm', '_mt', '_df')]
        self.target = self.key + '_unified'
        self.schemas = self.sources + [self.target]
        self.created = []
        self.transport = dict(kind='client', executable=str(args.client.resolve()), host='127.0.0.1', port=args.port, user='root')
        self.sql = migration.MariaClient(self.transport)
        self.players = {key: str(uuid.uuid4()) for key in ('shared', 'pmOnly', 'titleOnly', 'fortuneOnly', 'rewardRecipient', 'fake')}
        self.fixture = dict(players=self.players, archive=str(uuid.uuid4()), world=str(uuid.uuid4()), run=str(uuid.uuid4()))
        self.old_jar = self.work / 'old-service.jar'
        self.candidate_source = args.candidate.resolve()
        # Parallel builds may replace the candidate. All stages use one immutable copy.
        self.candidate = self.work / 'candidate-service.jar'
        self.password_file = self.work / 'synthetic-password.txt'
        self.password_file.write_text('Synthetic-only-' + uuid.uuid4().hex, encoding='utf-8')
        self.old_config = self.work / 'old-manager.json'
        self.new_config = self.work / 'new-manager.json'
        self.fixture_file = self.work / 'fixture.json'
        json_file(self.fixture_file, self.fixture)
        self.report = dict(format=1, key=self.key, phase='created', startedAt=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                           work=str(self.work), schemas=self.schemas, checks=[])

    def check(self, condition, label):
        if not condition:
            raise AssertionError(label)
        self.report['checks'].append(label)
        print('PASS ' + label, flush=True)

    def command(self, command, label):
        result = subprocess.run([str(value) for value in command], cwd=self.work,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        # These are synthetic credentials, but keep logs local and do not echo commands/data.
        (self.work / (label + '.log')).write_bytes(result.stdout)
        if result.returncode:
            raise RuntimeError(label + ' failed; inspect the local rehearsal log')
        return result.stdout.decode('utf-8', errors='replace')

    def cli(self, jar, action, config, *extra, label=None):
        return self.command([self.args.java, '-jar', jar, action, config, *extra], label or action)

    def compile_run(self, name, source, jar, *arguments):
        source_file = self.work / (name + '.java')
        source_file.write_text(source, encoding='utf-8')
        self.command([self.args.java.with_name('javac.exe' if os.name == 'nt' else 'javac'), '-encoding', 'UTF-8', '-cp', jar,
                      '-d', self.work, source_file], name + '-compile')
        return self.command([self.args.java, '-cp', str(self.work) + os.pathsep + str(jar), name, *arguments], name + '-run')

    def database(self, schema):
        return dict(url='jdbc:mariadb://127.0.0.1:43317/' + schema, user='root', password='')

    def prepare(self):
        shutil.copyfile(self.candidate_source, self.candidate)
        self.report['candidateSha256'] = sha(self.candidate)
        self.report['candidateSource'] = str(self.candidate_source)
        self.report['oldArchiveSha256'] = sha(self.args.old_zip)
        with zipfile.ZipFile(self.args.old_zip) as archive:
            self.old_jar.write_bytes(archive.read('service.jar'))
        self.report['oldServiceSha256'] = sha(self.old_jar)
        for schema in self.schemas:
            # No IF NOT EXISTS: a collision is never treated as ownership of an existing schema.
            self.sql.text('CREATE DATABASE ' + migration.identifier(schema) + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
            self.created.append(schema)
        cfg = dict(database=self.database(self.sources[0]), titlesDatabase=self.database(self.sources[1]), titlesEnabled=True,
                   fortunesDatabase=self.database(self.sources[2]), fortunesEnabled=True, bind='127.0.0.1', port=18997,
                   publicUrl='http://127.0.0.1:18997', owners=[], dataDirectory='old-data')
        json_file(self.old_config, cfg)
        self.cli(self.old_jar, 'init-admin', self.old_config, 'fixture_owner', self.players['shared'], self.password_file, label='legacy-init-admin')
        self.cli(self.old_jar, 'instance', self.old_config, 'fixture', label='legacy-instance')
        self.cli(self.old_jar, 'backup', self.old_config, label='legacy-initialize-backup')
        self.check('REHEARSAL_SEED_OK' in self.compile_run('UpgradeSeed', SEED_JAVA, self.old_jar, self.old_config, self.fixture_file),
                   'legacy service business APIs create real ownership, receipts, preferences, notifications and fortune history')
        self.seed_manager()
        old_data = self.work / 'old-data'
        avatars = old_data / 'titles' / 'avatars'
        for folder in ('mojang', 'client'):
            path = avatars / folder / (self.players['shared'] + '.png')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(png())
        (avatars / 'client' / (self.players['shared'] + '.active')).write_text('active', encoding='ascii')
        self.cli(self.old_jar, 'backup', self.old_config, label='legacy-seeded-backup')
        self.check(bool(list((old_data / 'backups' / 'manager').glob('*.zip'))), 'legacy full backup was created before migration')
        self.original_accounts = (old_data / 'titles' / 'web-accounts.json').read_bytes()
        self.original_avatar_hashes = file_tree(avatars)
        self.original_credentials = file_tree(old_data / 'credentials')
        self.check(bool(self.original_accounts) and bool(self.original_avatar_hashes) and bool(self.original_credentials),
                   'fixture includes original salted account hashes, cached avatars and bridge credentials')
        shutil.copytree(old_data, self.work / 'new-data')
        new_cfg = json.loads(self.old_config.read_text(encoding='utf-8'))
        new_cfg.update(database=self.database(self.target), storageMode='unified', dataDirectory='new-data', backupRetentionDays=30)
        new_cfg.pop('titlesDatabase', None)
        new_cfg.pop('fortunesDatabase', None)
        json_file(self.new_config, new_cfg)
        self.plan = dict(format=1, target=self.target,
                         sources=[dict(schema=schema, prefix=prefix) for schema, prefix in zip(self.sources, ('pm_', 'mt_', 'df_'))],
                         transport=self.transport, reportPath=str(self.work / 'migration-report.json'))
        json_file(self.work / 'migration-plan.json', self.plan)

    def seed_manager(self):
        schema = migration.identifier(self.sources[0])
        f = self.fixture
        now = int(time.time() * 1000) - 86400000
        q = sql_text
        statements = [
            f'INSERT INTO {schema}.pm_archives(id,instance_id,world_id,name,version,start_date,status,finalized,created_at,summary) '
            f'VALUES({q(f["archive"])},\'fixture\',{q(f["world"])},{q("Synthetic archived pack")},\'1.0\',\'2026-10-01\',\'active\',TRUE,{now},\'{{}}\');',
            f'INSERT INTO {schema}.pm_runs(instance_id,run_id,started_at,retired,cleaned) VALUES(\'fixture\',{q(f["run"])},{now},FALSE,FALSE);',
            f'UPDATE {schema}.pm_instances SET active_archive={q(f["archive"])},world_id={q(f["world"])},run_id={q(f["run"])},started_at={now},last_seen={now},last_seq=2,stopped=TRUE WHERE id=\'fixture\';',
        ]
        for key, name, first, last, fake in [('shared', 'CoreShared', now - 5000, now, False), ('pmOnly', 'CoreOnly', 0, 0, False), ('fake', 'alex', now, now, True)]:
            payload = json.dumps(dict(id=self.players[key], name=name, fake=fake, totalOnlineMillis=120000, joinCount=2), separators=(',', ':'))
            statements.append(f'INSERT INTO {schema}.pm_players(archive_id,uuid,name,name_sort,join_count,first_join,last_join,total_ms,online,counter_warning,payload) VALUES('
                              f'{q(f["archive"])},{q(self.players[key])},{q(name)},\'00\',2,{first},{last},120000,FALSE,FALSE,{q(payload)});')
        for i in range(2):
            statements.append(f'INSERT INTO {schema}.pm_logins(event_id,archive_id,uuid,at_ms,session_id,name) VALUES('
                              f'{q("fixture-login-" + str(i))},{q(f["archive"])},{q(self.players["shared"])},{now+i},{q("fixture-session-" + str(i))},\'CoreShared\');')
        statements.append(f'INSERT INTO {schema}.pm_audit(at_ms,actor,action,detail) VALUES({now},\'console\',\'FIXTURE_HISTORY\',{q(json.dumps({"note": "旧史\\n引号\"与🌿", "nullable": None},ensure_ascii=False))});')
        self.sql.text('\n'.join(statements))

    def legacy_counts(self, schema):
        def count(table):
            return int(self.sql.rows('SELECT COUNT(*) FROM ' + migration.identifier(schema) + '.' + migration.identifier(table) + ';')[0][0])
        return {table: count(table) for table in ('pm_logins', 'mt_owned', 'mt_receipts', 'mt_notifications', 'mt_changes', 'df_records', 'df_requests', 'df_audit')}

    def run(self):
        success = False
        try:
            self.prepare()
            migrator = migration.Migrator(self.plan)
            inspected = migrator.inspect()
            self.report['legacyTables'] = len(inspected['tables'])
            self.report['legacyRows'] = sum(table['fingerprint']['rows'] for table in inspected['tables'])
            migrator.copy('MIGRATE-EMPTY-UNIFIED')
            self.check(True, 'reviewed migrator copied every legacy table/column and verified exact hashes and foreign keys')
            self.before_counts = self.legacy_counts(self.target)
            self.cli(self.candidate, 'platform-init', self.new_config, label='candidate-platform-init')
            migrator.verify(after_platform_init=True)
            self.check(True, 'candidate platform-init preserves all legacy business-column hashes and source schemas')
            self.cli(self.candidate, 'platform-init', self.new_config, label='candidate-platform-init-replay')
            migrator.verify(after_platform_init=True)
            self.check(self.legacy_counts(self.target) == self.before_counts, 'repeated initialization creates no extra rewards, receipts, notifications, logins or fortune tokens')
            self.check(self.sql.rows('SELECT COUNT(*) FROM ' + migration.identifier(self.target) + '.pm_admissions;') == [['0']],
                       'directory backfill creates no synthetic admitted game sessions')
            new_data = self.work / 'new-data'
            self.check((new_data / 'platform/web-accounts.json').read_bytes() == self.original_accounts,
                       'salted account hash file is copied byte-for-byte into the platform authority')
            self.check(file_tree(new_data / 'platform/avatars') == self.original_avatar_hashes,
                       'Mojang/client avatar bytes and fallback marker are copied byte-for-byte')
            self.check(file_tree(new_data / 'credentials') == self.original_credentials,
                       'bridge credential files are preserved byte-for-byte')
            output = self.compile_run('UpgradeProbe', PROBE_JAVA, self.candidate, self.new_config, self.fixture_file, self.password_file)
            self.check('REHEARSAL_PROBE_OK 5' in output,
                       'live candidate adapters share all five real identities, exclude fake alex, preserve zero history and authenticate original credentials')
            backups = new_data / 'backups' / 'manager'
            old_backups = set(backups.glob('*.zip'))
            self.cli(self.candidate, 'backup', self.new_config, label='candidate-unified-backup')
            created_backups = set(backups.glob('*.zip')) - old_backups
            self.check(len(created_backups) == 1, 'upgraded service creates a single complete unified backup')
            backup = created_backups.pop()
            self.check(backup.with_name(backup.name + '.sha256').read_text().split()[0] == sha(backup),
                       'unified backup checksum matches its actual ZIP bytes')
            with zipfile.ZipFile(backup) as archive:
                manifest = json.loads(archive.read('manifest.json'))
                expected_tables = {table['table'] for table in inspected['tables']} - {'pm_metrics', 'pm_long_ticks', 'pm_tick_groups'}
                self.check(manifest['format'] == 3 and set(manifest['tables']) >= expected_tables
                           and {'pm_profiles', 'pm_modules', 'pm_values'} <= set(manifest['tables']),
                           'post-upgrade backup includes all legacy durable modules and the new platform directory')
                self.check(archive.read('files/platform/web-accounts.json') == self.original_accounts
                           and archive.read('files/credentials/fixture-bridge.json') == (new_data / 'credentials/fixture-bridge.json').read_bytes(),
                           'post-upgrade backup includes the original account hashes and bridge credentials')
                for path, digest in self.original_avatar_hashes.items():
                    if hashlib.sha256(archive.read('files/platform/avatars/' + path)).hexdigest() != digest:
                        raise AssertionError('Unified backup changed cached avatar bytes')
                self.check(True, 'post-upgrade backup includes every cached avatar and fallback marker unchanged')
            migrator.verify(after_platform_init=True)
            self.check(True, 'candidate adapter reads and replayed initialization leave all legacy rows unchanged')
            self.check(sha(self.candidate) == self.report['candidateSha256'], 'every rehearsal stage used one immutable candidate binary')
            self.report['candidateSourceChanged'] = sha(self.candidate_source) != self.report['candidateSha256']
            self.report.update(phase='passed', legacyCounts=self.before_counts,
                               finishedAt=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()))
            success = True
        except BaseException as error:
            self.report.update(phase='failed', error=type(error).__name__, message=str(error))
            raise
        finally:
            self.report['createdSchemas'] = self.created
            if success and not self.args.keep_databases:
                for schema in reversed(self.created):
                    if schema not in self.schemas or not schema.startswith(self.key + '_'):
                        raise AssertionError('Cleanup escaped the generated fixture boundary')
                    self.sql.text('DROP DATABASE ' + migration.identifier(schema) + ';')
                self.report['schemasRemoved'] = True
            else:
                self.report['schemasRemoved'] = False
            json_file(self.work / 'rehearsal-result.json', self.report)
            print(json.dumps(dict(phase=self.report['phase'], checks=len(self.report['checks']), report=str(self.work / 'rehearsal-result.json'),
                                  schemasRemoved=self.report['schemasRemoved']), ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--old-zip', type=Path, default=DEFAULT_OLD)
    parser.add_argument('--candidate', type=Path, default=ROOT / 'build/manager-service/pixelmc-manager-service-0.6.0.jar')
    parser.add_argument('--java', type=Path, default=DEFAULT_JAVA)
    parser.add_argument('--client', type=Path, default=DEFAULT_CLIENT)
    parser.add_argument('--port', type=int, default=43317)
    parser.add_argument('--keep-databases', action='store_true')
    args = parser.parse_args()
    for path in (args.old_zip, args.candidate, args.java, args.client):
        if not path.is_absolute() or not path.is_file():
            raise ValueError('Required local rehearsal input is missing')
    os.umask(0o077)
    Rehearsal(args).run()


if __name__ == '__main__':
    main()
