#!/usr/bin/env python3
"""Prepare the existing protected offsite worker for the unified platform.

Only patches known source fragments. Does not run backups or change timers,
retention, bandwidth, encryption, credentials or service state.
"""
from pathlib import Path
import argparse
import ast
import hashlib
import os


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError('Backup worker source changed; inspect before applying')
    return source.replace(old, new, 1)


def patch(source):
    start = source.index('def discover_databases(defaults):')
    end = source.index('\n\ndef running_manager_service_jar():', start)
    discovery = '''def discover_databases(defaults):
    allowed = {"memorialtitles", "dailyfortunes", "pixelmc_manager", "pixelmc_platform"}
    query = ("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA "
             "WHERE SCHEMA_NAME IN ('memorialtitles','dailyfortunes','pixelmc_manager','pixelmc_platform') ORDER BY SCHEMA_NAME")
    result = subprocess.run(
        ["docker", "exec", "-i", "--user", "0", CONF["database_container"],
         "nice", "-n", "19", "mariadb", "--defaults-extra-file=/dev/stdin",
         "--batch", "--skip-column-names", "--execute", query],
        input=defaults, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if result.returncode:
        (BASE / "last-dump-error.log").write_text(redact(result.stderr.decode(errors="replace")))
        raise RuntimeError("Database discovery failed; no incomplete offsite snapshot uploaded")
    try:
        names = result.stdout.decode("utf-8", errors="strict").splitlines()
    except UnicodeDecodeError:
        raise RuntimeError("Unexpected database discovery response") from None
    if len(names) != len(set(names)) or "memorialtitles" not in names or set(names) - allowed:
        raise RuntimeError("Unexpected database discovery response")
    current = json.loads((ROOT / "pixelmc-manager/manager.json").read_text())
    if current.get("storageMode") == "unified":
        if "pixelmc_platform" not in names or not current["database"]["url"].endswith("/pixelmc_platform"):
            raise RuntimeError("Unified application database is missing; no incomplete backup uploaded")
    return names
'''
    source = source[:start] + discovery + source[end:]
    source = replace_once(source,
        '        "/data/deployment/manager-ui-0.5.2-20261026/pixelmc-manager-service-0.5.2-web.1.jar",',
        '        "/data/deployment/manager-ui-0.5.2-20261026/pixelmc-manager-service-0.5.2-web.1.jar",\n'
        '        "/data/releases/0.6.0/pixelmc-manager-service-0.6.0.jar",')
    source = replace_once(source,
        '                   "--databases", *databases]',
        '                   *["--ignore-table=" + schema + "." + table\n'
        '                     for schema in databases if schema in {"pixelmc_manager", "pixelmc_platform"}\n'
        '                     for table in ("pm_metrics", "pm_long_ticks", "pm_tick_groups")],\n'
        '                   "--databases", *databases]')
    source = replace_once(source,
        '            archives = sorted((manager_root / "data/backups/manager").glob("manager-*.zip"), key=lambda p: p.stat().st_mtime)',
        '            manager_config = json.loads((manager_root / "manager.json").read_text())\n'
        '            configured_data = Path(manager_config["dataDirectory"])\n'
        '            if configured_data.is_absolute() or ".." in configured_data.parts:\n'
        '                raise RuntimeError("Unexpected Manager data directory")\n'
        '            data_root = manager_root / configured_data\n'
        '            if (not data_root.resolve().is_relative_to(manager_root.resolve())\n'
        '                    or any(manager_root.joinpath(*configured_data.parts[:i]).is_symlink()\n'
        '                           for i in range(1, len(configured_data.parts) + 1))\n'
        '                    or not data_root.is_dir()):\n'
        '                raise RuntimeError("Manager durable data directory is missing or unsafe")\n'
        '            archives = sorted((data_root / "backups/manager").glob("manager-*.zip"), key=lambda p: p.stat().st_mtime)')
    source = replace_once(source,
        'no game world or live database data directory included.',
        'no game world, expiring telemetry, or live database data directory included.')
    ast.parse(source)
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('worker', type=Path)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw = args.worker.read_bytes()
    if hashlib.sha256(raw).hexdigest() != args.expected_sha256:
        raise ValueError('Offsite worker changed; inspect current version')
    if args.output.exists() or args.output.is_symlink() or args.output.resolve() == args.worker.resolve():
        raise ValueError('Write a separate new candidate; original worker stays intact')
    os.umask(0o077)
    with args.output.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(patch(raw.decode('utf-8')))
    args.output.chmod(0o600)
    print('Prepared candidate SHA-256:', hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
