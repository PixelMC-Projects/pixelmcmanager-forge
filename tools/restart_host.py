#!/usr/bin/env python3
"""Local restart supervisor. No listening ports, third-party modules, or game-side API keys."""
import argparse
from collections import deque
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import threading
import time
import urllib.request
import uuid


def now_ms():
    return int(time.time() * 1000)


def read_json(path):
    if path.stat().st_size > 16384:
        raise ValueError('IPC file too large')
    return json.loads(path.read_text(encoding='utf-8'))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.host-tmp-' + uuid.uuid4().hex)
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o644)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as output:
            output.write(json.dumps(value, ensure_ascii=False) + '\n')
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


class Protocol:
    def __init__(self, directory, backend, state_file=None):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        if backend == 'mcsm' and os.name == 'posix' and self.directory.resolve() != self.directory.absolute():
            raise ValueError('MCSM IPC directory must not contain symlinks')
        self.directory_fd = None
        if os.open in os.supports_dir_fd:
            self.directory_fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        self.backend = backend
        self.bridge_id = str(uuid.uuid4())
        self.state_file = Path(state_file) if state_file else self.directory / 'host-seen.json'
        self.seen = deque(maxlen=256)
        if self.state_file.exists():
            self.seen.extend(read_json(self.state_file).get('seen', [])[-256:])

    def read_ipc(self, name):
        if self.directory_fd is None:
            path = self.directory / name
            if path.is_symlink(): raise ValueError('IPC symlink rejected')
            return read_json(path)
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=self.directory_fd)
        with os.fdopen(fd, 'r', encoding='utf-8') as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > 16384: raise ValueError('Invalid IPC file')
            value = source.read(16385)
            if len(value) > 16384: raise ValueError('IPC file too large')
            return json.loads(value)

    def write_ipc(self, name, value):
        if self.directory_fd is None:
            return write_json(self.directory / name, value)
        temporary = name + '.host-tmp-' + uuid.uuid4().hex
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644, dir_fd=self.directory_fd)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as target:
                target.write(json.dumps(value, ensure_ascii=False) + '\n')
            os.replace(temporary, name, src_dir_fd=self.directory_fd, dst_dir_fd=self.directory_fd)
        finally:
            try: os.unlink(temporary, dir_fd=self.directory_fd)
            except FileNotFoundError: pass

    def publish(self):
        self.write_ipc('bridge.json', {'protocol': 1, 'bridgeId': self.bridge_id,
            'backend': self.backend, 'expiresAt': now_ms() + 6000})

    def close(self):
        # Expire, rather than delete, so the game can explain why the adapter is unavailable.
        self.write_ipc('bridge.json', {'protocol': 1, 'bridgeId': self.bridge_id,
            'backend': self.backend, 'expiresAt': 0})
        if self.directory_fd is not None:
            os.close(self.directory_fd)
            self.directory_fd = None

    def respond(self, request, status, message=''):
        self.write_ipc('response.json', {'id': request.get('id', ''),
            'session': request.get('session', ''), 'status': status, 'message': message, 'at': now_ms()})

    def validate(self, request):
        current = now_ms()
        if request.get('protocol') != 1 or request.get('action') != 'restart' or request.get('bridgeId') != self.bridge_id:
            raise ValueError('重启协议或托管程序标识不匹配。')
        created, expires = request.get('createdAt', 0), request.get('expiresAt', 0)
        if not created <= current < expires or expires - created > 10000:
            raise ValueError('重启请求已经过期。')
        game = self.read_ipc('game.json')
        if game.get('session') != request.get('session') or not -1000 <= current - game.get('at', 0) <= 5000:
            raise ValueError('游戏会话心跳无效或已过期。')
        if game.get('players') != 0 or game.get('locked') is not True or game.get('emptyMillis', 0) < 15000:
            raise ValueError('服务器尚未连续空服15秒，或未锁定重启提交。')

    def take(self):
        try:
            request = self.read_ipc('request.json')
        except FileNotFoundError:
            return None
        request_id = request.get('id', '')
        if not re.fullmatch(r'[0-9a-fA-F-]{36}', request_id) or request_id in self.seen:
            return None
        # Consume before dispatch. Ambiguous daemon responses must never cause repeated restarts.
        self.seen.append(request_id)
        write_json(self.state_file, {'seen': list(self.seen)})
        try:
            self.validate(request)
        except (ValueError, OSError, TypeError):
            self.respond(request, 'error', '重启请求失效或空服条件不满足，未执行关服。')
            return None
        return request


class DaemonClient:
    def __init__(self, config_file):
        config = read_json(Path(config_file))
        base = 'http://127.0.0.1:' + str(config['port']) + config.get('prefix', '') + '/socket.io/?EIO=4&transport=polling'
        handshake = self.http(base)
        self.url = base + '&sid=' + json.loads(handshake[1:])['sid']
        self.http(self.url, b'40')
        self.http(self.url)
        if self.request('auth', config['key'])['data'] is not True:
            raise RuntimeError('MCSM daemon authentication failed')

    @staticmethod
    def http(url, data=None):
        request = urllib.request.Request(url, data=data, headers={'Content-Type': 'text/plain;charset=UTF-8'})
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read().decode()

    def request(self, event, data):
        request_id = uuid.uuid4().hex
        self.http(self.url, ('42' + json.dumps([event, {'uuid': request_id, 'data': data}])).encode())
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            for frame in self.http(self.url).split(chr(30)):
                if frame == '2':
                    self.http(self.url, b'3')
                elif frame.startswith('42'):
                    _, answer = json.loads(frame[2:])
                    if answer.get('uuid') == request_id:
                        if answer.get('status') != 200:
                            raise RuntimeError('MCSM rejected restart operation')
                        return answer
        raise TimeoutError('MCSM response timed out')

    def close(self):
        try:
            self.http(self.url, b'41')
        except Exception:
            pass


def mcsm_restart(protocol, request, game_dir, instance, daemon_config, client_factory=DaemonClient):
    client = client_factory(daemon_config)
    try:
        detail = client.request('instance/detail', {'instanceUuid': instance})['data']
        if Path(detail['config']['cwd']).resolve() != Path(game_dir).resolve() or detail['status'] != 3:
            raise ValueError('MCSM instance path or running state does not match')
        protocol.validate(request)
        client.request('instance/restart', {'instanceUuids': [instance]})
        protocol.respond(request, 'accepted', '已提交MCSM，由面板使用原实例启动配置重启。')
    finally:
        client.close()


def run_mcsm(args):
    protocol = Protocol(args.game_dir / 'pixelmcmanager/restart', 'mcsm', args.state_file)
    ending = threading.Event()
    for name in ('SIGINT', 'SIGTERM'):
        signal.signal(getattr(signal, name), lambda *_: ending.set())
    print('[PixelMC] MCSM restart bridge ready; only the configured instance is permitted.', flush=True)
    try:
        while not ending.is_set():
            protocol.publish()
            try:
                request = protocol.take()
                if request:
                    try:
                        mcsm_restart(protocol, request, args.game_dir, args.instance, args.daemon_config)
                        print('[PixelMC] Restart request accepted by MCSM.', flush=True)
                    except Exception:
                        protocol.respond(request, 'error', 'MCSM重启请求未确认；已暂停，不重复请求，请检查宿主机桥接日志。')
                        print('[PixelMC] Restart request failed or uncertain; no retry or force kill.', flush=True)
            except (OSError, ValueError, TypeError):
                print('[PixelMC] Invalid local IPC data; no restart performed.', flush=True)
            ending.wait(.5)
    finally:
        protocol.close()
    return 0


def run_standalone(args):
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        raise ValueError('Supply the original complete startup command after --')
    protocol = Protocol(args.game_dir / 'pixelmcmanager/restart', 'standalone')
    lock = threading.Lock()
    current = [None]
    manual_stop = threading.Event()
    restart_requested = False
    stop_started = None
    warned = False

    def send(line):
        with lock:
            process = current[0]
            if process is None or process.poll() is not None:
                raise RuntimeError('Game process is not running')
            process.stdin.write(line)
            process.stdin.flush()

    def terminate(*_):
        manual_stop.set()
        try:
            send('stop\n')
        except (OSError, RuntimeError):
            pass

    def console():
        for line in sys.stdin:
            if line.strip().lower() == 'stop':
                manual_stop.set()
            try:
                send(line)
            except (OSError, RuntimeError):
                pass

    for name in ('SIGINT', 'SIGTERM'):
        signal.signal(getattr(signal, name), terminate)
    # stdin forwarding remains alive across a requested restart. stdout/stderr are inherited.
    console_started = False
    try:
        while True:
            process = subprocess.Popen(command, cwd=args.game_dir, stdin=subprocess.PIPE, text=True,
                encoding='utf-8', errors='replace')
            with lock:
                current[0] = process
            if not console_started:
                threading.Thread(target=console, name='PixelMC-console', daemon=True).start()
                console_started = True
            print('[PixelMC] Game started with the original command and arguments.', flush=True)
            while process.poll() is None:
                protocol.publish()
                try:
                    request = protocol.take()
                    if request:
                        if manual_stop.is_set() or restart_requested:
                            protocol.respond(request, 'error', '已有停止或重启流程，不重复执行。')
                        else:
                            protocol.validate(request)
                            send('stop\n')
                            restart_requested = True
                            stop_started = time.monotonic()
                            protocol.respond(request, 'accepted', '正在正常关服，待旧进程退出后使用原启动命令重启。')
                except (OSError, ValueError, TypeError, RuntimeError):
                    if 'request' in locals() and request:
                        protocol.respond(request, 'error', '无法正常提交停止指令；没有强杀或启动第二个进程。')
                if stop_started is not None and time.monotonic() - stop_started > 120 and not warned:
                    warned = True
                    print('[PixelMC] Shutdown exceeds 120 seconds; waiting for the old process, no force kill.', flush=True)
                time.sleep(.25)
            code = process.wait()
            process.stdin.close()
            with lock:
                current[0] = None
            if not restart_requested or manual_stop.is_set():
                return code
            restart_requested = False
            stop_started = None
            warned = False
            print('[PixelMC] Old process exited; restarting with identical startup arguments.', flush=True)
    finally:
        protocol.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    mcsm = sub.add_parser('mcsm')
    mcsm.add_argument('--game-dir', type=Path, required=True)
    mcsm.add_argument('--instance', required=True)
    mcsm.add_argument('--daemon-config', type=Path, default=Path('/opt/mcsmanager/daemon/data/Config/global.json'))
    mcsm.add_argument('--state-file', type=Path, required=True)
    native = sub.add_parser('standalone')
    native.add_argument('--game-dir', type=Path, default=Path.cwd())
    native.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    args.game_dir = args.game_dir.resolve()
    return run_mcsm(args) if args.mode == 'mcsm' else run_standalone(args)


if __name__ == '__main__':
    sys.exit(main())
