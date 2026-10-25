import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
import restart_host as host


class RestartHostTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pixelmc-host-test-')
        self.root = Path(self.temporary.name)
        self.protocol = host.Protocol(self.root / 'ipc', 'mcsm')
        self.protocol.publish()

    def tearDown(self):
        self.temporary.cleanup()

    def request(self, **changes):
        now = host.now_ms()
        request = {'protocol': 1, 'bridgeId': self.protocol.bridge_id, 'session': str(uuid.uuid4()),
            'id': str(uuid.uuid4()), 'action': 'restart', 'createdAt': now, 'expiresAt': now + 10000}
        request.update(changes)
        host.write_json(self.protocol.directory / 'game.json', {'session': request['session'],
            'at': now, 'players': 0, 'locked': True, 'emptyMillis': 15000})
        host.write_json(self.protocol.directory / 'request.json', request)
        return request

    def test_replay_prevention(self):
        self.request()
        self.assertIsNotNone(self.protocol.take())
        self.assertIsNone(self.protocol.take())
        replacement = host.Protocol(self.protocol.directory, 'mcsm')
        self.assertIsNone(replacement.take())

    def test_expired_request(self):
        self.request(createdAt=host.now_ms()-20000, expiresAt=host.now_ms()-10000)
        self.assertIsNone(self.protocol.take())
        self.assertEqual(host.read_json(self.protocol.directory/'response.json')['status'], 'error')

    def test_online_player_rejected(self):
        request = self.request()
        host.write_json(self.protocol.directory/'game.json', {'session': request['session'],
            'at': host.now_ms(), 'players': 1, 'locked': True, 'emptyMillis': 15000})
        self.assertIsNone(self.protocol.take())

    def test_stale_session_rejected(self):
        self.request()
        game = host.read_json(self.protocol.directory/'game.json')
        game['session'] = 'another-process'
        host.write_json(self.protocol.directory/'game.json', game)
        self.assertIsNone(self.protocol.take())

    def test_not_empty_long_enough(self):
        self.request()
        game = host.read_json(self.protocol.directory/'game.json')
        game['emptyMillis'] = 14999
        host.write_json(self.protocol.directory/'game.json', game)
        self.assertIsNone(self.protocol.take())

    def test_mcsm_only_bound_instance_and_original_config(self):
        request = self.request()
        calls = []
        original_start = 'java -Xmx8G -XX:+UseG1GC @unix_args.txt nogui'
        root = self.root
        class FakeDaemon:
            def __init__(self, *_): pass
            def request(self, event, payload):
                calls.append((event, payload))
                return {'data': {'status': 3, 'config': {'cwd': str(root), 'startCommand': original_start}}}
            def close(self): pass
        host.mcsm_restart(self.protocol, request, self.root, 'bound-instance', 'unused', FakeDaemon)
        self.assertEqual(calls, [('instance/detail', {'instanceUuid': 'bound-instance'}),
                                 ('instance/restart', {'instanceUuids': ['bound-instance']})])
        self.assertEqual(host.read_json(self.protocol.directory/'response.json')['status'], 'accepted')

    def test_wrong_instance_directory_rejected(self):
        request = self.request()
        class FakeDaemon:
            def __init__(self, *_): pass
            def request(self, event, payload):
                if event != 'instance/detail': raise AssertionError('Unexpected mutation')
                return {'data': {'status': 3, 'config': {'cwd': '/wrong/instance'}}}
            def close(self): pass
        with self.assertRaises(ValueError):
            host.mcsm_restart(self.protocol, request, self.root, 'bound', 'unused', FakeDaemon)

    def test_standalone_real_process_restart_and_identical_arguments(self):
        script = self.root/'fake_server.py'
        starts = self.root/'starts.jsonl'
        script.write_text('import sys,json\nfrom pathlib import Path\n'
            'with Path(sys.argv[1]).open("a") as f: f.write(json.dumps(sys.argv[2:])+"\\n")\n'
            'for line in sys.stdin:\n if line.strip()=="stop": break\n', encoding='utf-8')
        log = (self.root/'console.log').open('w', encoding='utf-8')
        process = subprocess.Popen([sys.executable, str(Path(host.__file__).resolve()), 'standalone',
            '--game-dir', str(self.root), '--', sys.executable, str(script), str(starts),
            '-Xmx8G', '-XX:+UseG1GC', 'argument with spaces'], stdin=subprocess.PIPE,
            stdout=log, stderr=subprocess.STDOUT, text=True)
        try:
            ipc = self.root/'pixelmcmanager/restart'
            deadline = time.monotonic()+10
            while (not (ipc/'bridge.json').exists() or not starts.exists()) and time.monotonic()<deadline: time.sleep(.05)
            marker = host.read_json(ipc/'bridge.json')
            now = host.now_ms(); session = str(uuid.uuid4())
            host.write_json(ipc/'game.json', {'session': session, 'at': now, 'players': 0, 'locked': True, 'emptyMillis': 15000})
            host.write_json(ipc/'request.json', {'protocol':1,'bridgeId':marker['bridgeId'],'session':session,
                'id':str(uuid.uuid4()),'action':'restart','createdAt':now,'expiresAt':now+10000})
            deadline = time.monotonic()+10
            while len(starts.read_text().splitlines())<2 and time.monotonic()<deadline: time.sleep(.05)
            entries = [json.loads(x) for x in starts.read_text().splitlines()]
            self.assertEqual(len(entries), 2)
            self.assertEqual(entries[0], ['-Xmx8G','-XX:+UseG1GC','argument with spaces'])
            self.assertEqual(entries[0], entries[1])
            process.stdin.write('stop\n');process.stdin.flush()
            self.assertEqual(process.wait(timeout=10), 0)
            self.assertEqual(len(starts.read_text().splitlines()), 2)
        finally:
            if process.poll() is None:
                process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=10)
            process.stdin.close();log.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
