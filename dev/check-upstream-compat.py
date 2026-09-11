#!/usr/bin/env python3
"""Cheap frozen-upstream identity gate; full comparisons only on version change/force."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import tomllib

REPO = Path(__file__).resolve().parent.parent
BASELINE = REPO / 'dev/upstream-compat-baseline.json'
CORPORA = ('Abstract_Completeness', 'AODV', 'Category3', 'FOL', 'ZF', 'Sequents', 'CTT')
REQUIRED_CONFIGS = ('configs/m3.toml',)
PRIVATE_INPUTS = ('query_base', 'dev', 'src', 'tests/fixtures', 'configs')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_tree(root):
    """Git tree identity from WORKING bytes/modes, without touching Git's index/objects."""
    def git_hash(kind, data):
        return hashlib.sha1(kind.encode() + b' ' + str(len(data)).encode() + b'\0' + data).digest()
    def tree(directory):
        entries = []
        for p in directory.iterdir():
            if p.name == '__pycache__' or p.suffix == '.pyc':
                continue
            if p.is_symlink():
                mode, sha = b'120000', git_hash('blob', os.readlink(p).encode())
            elif p.is_dir():
                mode, sha = b'40000', tree(p)
            else:
                mode = b'100755' if p.stat().st_mode & 0o111 else b'100644'
                sha = git_hash('blob', p.read_bytes())
            entries.append((p.name.encode() + (b'/' if mode == b'40000' else b''),
                            mode + b' ' + p.name.encode() + b'\0' + sha))
        return git_hash('tree', b''.join(row for _, row in sorted(entries)))
    return tree(root).hex()


def identity(repo):
    project = tomllib.loads((repo / 'pyproject.toml').read_text())['project']
    return {'version': project['version'],
            'python_tree_id': source_tree(repo / 'src/isabelle_query'),
            'pyproject_sha256': digest((repo / 'pyproject.toml').read_bytes())}


def content_id(root, predicate=lambda p: True):
    h = hashlib.sha256()
    for p in sorted(root.rglob('*')):
        if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc' and predicate(p):
            h.update(str(p.relative_to(root)).encode() + b'\0')
            h.update(p.read_bytes())
    return h.hexdigest()


def required_config_identity(repo):
    """Validate required matrix fixtures and identify their working bytes."""
    result = {}
    for name in REQUIRED_CONFIGS:
        path = repo / name
        if not path.is_file():
            raise ValueError(f'missing required compatibility config: {name}')
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise ValueError(f'unreadable required compatibility config: {name}') from exc
        result[name] = digest(data)
    return result


def validate_baseline_configs(baseline, current, force):
    recorded = baseline.get('required_configs')
    if recorded is None:
        if not force:
            raise ValueError('compatibility baseline lacks required config identity; use --force for a fresh complete comparison')
    elif recorded != current and not force:
        raise ValueError('required compatibility config changed since recorded evidence; use --force for a fresh complete comparison')


def copy_private_inputs(repo, work):
    for name in PRIVATE_INPUTS:
        shutil.copytree(repo / name, work / name,
                        ignore=shutil.ignore_patterns('__pycache__', '*.pyc', 'classes'))


def oracle_identity(executable):
    path = Path(shutil.which(executable) or executable).resolve()
    first = path.read_text().splitlines()[0]
    if not first.startswith('#!') or not Path(first[2:]).is_file():
        raise ValueError('QUERY_ORACLE must be a Python console script with an absolute interpreter shebang')
    code = '''import importlib.metadata,json,isabelle_query,isabelle_layout
from pathlib import Path
print(json.dumps({'query':str(Path(isabelle_query.__file__).resolve().parent),
'layout':str(Path(isabelle_layout.__file__).resolve().parent),
'layout_version':importlib.metadata.version('isabelle-layout')}))'''
    with tempfile.TemporaryFile(mode='w+') as output:
        run_owned([first[2:], '-c', code],
                  env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'),
                  cwd=REPO, stdout=output, timeout=30)
        output.seek(0)
        info = json.load(output)
    info['python_tree_id'] = source_tree(Path(info.pop('query')))
    info['layout_sha256'] = content_id(Path(info.pop('layout')))
    return info


def terminate_group(group, process=None):
    """Only called with a newly created session or an exact private server group."""
    if group == os.getpgrp():
        raise RuntimeError('refusing to signal the gate process group')
    try:
        os.killpg(group, signal.SIGTERM)
    except ProcessLookupError:
        pass
    if process is not None:
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            pass
    # The leader can exit before its children; always finish the owned group.
    try:
        os.killpg(group, signal.SIGKILL)
    except ProcessLookupError:
        pass
    if process is not None:
        process.wait(timeout=2)


def private_server_groups(home, name):
    """Linux: the thin client detaches its server with start_new_session=True.

    Match BOTH exact argv tokens and the unique scratch USER_HOME. Never use
    global pkill/name-prefix matching or inspect another user's registry.
    """
    groups = set()
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit():
            continue
        try:
            args = (directory / 'cmdline').read_bytes().split(b'\0')
            wanted = [b'server', b'-n', name.encode()]
            if not any(args[i:i + 3] == wanted for i in range(len(args) - 2)):
                continue
            environment = (directory / 'environ').read_bytes().split(b'\0')
            if b'USER_HOME=' + os.fsencode(home) not in environment:
                continue
            group = os.getpgid(int(directory.name))
            if group != os.getpgrp():
                groups.add(group)
        except (OSError, ProcessLookupError):
            continue
    return groups


def run_owned(args, *, env, cwd, timeout, stdout=None, stderr=None, private_server=False):
    process = subprocess.Popen(args, env=env, cwd=cwd, stdout=stdout, stderr=stderr,
                               start_new_session=True)
    try:
        rc = process.wait(timeout=timeout)
        if rc:
            raise subprocess.CalledProcessError(rc, args)
    finally:
        # Stop the harness first so it cannot spawn another detached server.
        terminate_group(process.pid, process)
        if private_server:
            for group in private_server_groups(env['USER_HOME'], f'difftest-{process.pid}'):
                terminate_group(group)


def full_compat(repo, before, required_configs):
    if required_config_identity(repo) != required_configs:
        raise ValueError('required compatibility config changed before private copy')
    if os.environ.get('QUERY_CORPORA'):
        raise ValueError('QUERY_CORPORA overrides are refused: all seven standard corpora are required')
    afp = Path(os.environ['QUERY_TEST_AFP'])
    distro = Path(os.environ['QUERY_TEST_DISTRO'])
    corpora = [(afp if i < 3 else distro) / name for i, name in enumerate(CORPORA)]
    for corpus in corpora:
        if not corpus.is_dir() or not any(corpus.rglob('*.thy')):
            raise ValueError(f'missing required corpus: {corpus.name}')
    guard_files = ('difftest.sh', 'owned-server.sh', 'difftest-pins', 'entrydiff.sh', 'dump_oracle.py')
    guard_bytes = {name: (repo / 'dev' / name).read_bytes() for name in guard_files}
    oracle = os.environ['QUERY_ORACLE']
    oracle = str(Path(shutil.which(oracle) or oracle).resolve())
    dependency = oracle_identity(oracle)
    if dependency['python_tree_id'] != before['python_tree_id']:
        raise ValueError('QUERY_ORACLE imported source differs from this checkout')
    corpus_ids = {p.name: content_id(p, lambda f: f.suffix == '.thy' or f.name in ('ROOT', 'ROOTS')) for p in corpora}
    scratch_parent = repo / '.dev/upstream-compat'
    scratch_parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='run-', dir=scratch_parent))
    # Existing harnesses hard-code their own repo/.dev home. Copy them and the
    # component so even their warm server and build artifacts are private.
    copy_private_inputs(repo, work)
    if required_config_identity(work) != required_configs:
        raise ValueError('private compatibility copy has wrong required config content')
    shutil.copy2(repo / 'pyproject.toml', work / 'pyproject.toml')
    for name, data in guard_bytes.items():
        (work / 'dev' / name).write_bytes(data)
    harness = work / 'dev/difftest.sh'
    text = harness.read_text()
    text, count = re.subn(r'^ORACLE_VERSION=.*$', 'ORACLE_VERSION=' + before['version'], text, flags=re.M)
    if count != 1 or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*(?:[a-zA-Z0-9.+-]*)', before['version']):
        raise ValueError('cannot bind existing matrix to checked upstream version')
    harness.write_text(text)
    env = dict(os.environ, USER_HOME=str(work / '.dev'), QUERY_ORACLE=oracle,
               QUERY_DIFFTEST_WARM='1',
               QUERY_DIFFTEST_CLIENT=str(work / 'query_base/lib/scripts/query_client.py'),
               ISABELLE_QUERY_NAMESPACE='committed',
               ISABELLE_QUERY_NO_CDS='1', PYTHONDONTWRITEBYTECODE='1', QUERY_COMPAT_MANAGED='1')
    for key in ('QUERY_PINS', 'QUERY_DIFF_DIR', 'QUERY_CORPORA', 'CLASSPATH',
                'ISABELLE_QUERY_NO_SERVER', 'ISABELLE_QUERY_NO_CLIENT'):
        env.pop(key, None)
    (work / '.dev').mkdir()
    print(f'Full compatibility artifacts: {work}', flush=True)
    run_owned(['isabelle', 'components', '-u', str(work / 'query_base')], env=env, cwd=work, timeout=60)
    run_owned(['isabelle', 'scala_build'], env=env, cwd=work, timeout=300)
    for script in ('entrydiff.sh', 'difftest.sh'):
        log = work / (script + '.log')
        with log.open('w') as output:
            run_owned(['bash', str(work / 'dev' / script), *map(str, corpora)],
                      cwd=work, env=env, stdout=output, stderr=subprocess.STDOUT,
                      timeout=14400, private_server=(script == 'difftest.sh'))
        result = log.read_text()
        if script == 'difftest.sh':
            match = re.search(r'^(\d+) cases: (\d+) clean, (\d+) pinned, 0 failing, 0 stale pins$', result, re.M)
            if not match or int(match[1]) != 2149 or int(match[2]) + int(match[3]) != 2149:
                raise ValueError('matrix did not report all 2149 successful comparisons')
            pinned = int(match[3])
        elif len(re.findall(r'^ok\s', result, re.M)) != 28:
            raise ValueError('entrydiff did not report all 28 projections')
    if any((repo / 'dev' / name).read_bytes() != data for name, data in guard_bytes.items()):
        raise ValueError('compatibility harness/pins changed during run; refusing stamp')
    if identity(repo) != before or oracle_identity(oracle) != dependency:
        raise ValueError('upstream/dependency changed during compatibility; refusing stamp')
    if required_config_identity(repo) != required_configs or required_config_identity(work) != required_configs:
        raise ValueError('required compatibility config changed during compatibility; refusing stamp')
    if corpus_ids != {p.name: content_id(p, lambda f: f.suffix == '.thy' or f.name in ('ROOT', 'ROOTS')) for p in corpora}:
        raise ValueError('corpus changed during compatibility; refusing stamp')
    return {'schema_version': 2, 'upstream': before,
            'evidence': {'kind': 'complete-run', 'checked_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                         'mode': 'warm', 'comparisons': 2149, 'pins': pinned, 'entry_projections': 28},
            'layout': dependency, 'corpora': corpus_ids,
            'matrix_sha256': digest(guard_bytes['difftest.sh']),
            'owned_server_sha256': digest(guard_bytes['owned-server.sh']),
            'pins_sha256': digest(guard_bytes['difftest-pins']),
            'executed_matrix_sha256': digest(harness.read_bytes()),
            'entrydiff_sha256': digest(guard_bytes['entrydiff.sh']),
            'required_configs': required_configs,
            'fixtures_sha256': content_id(work / 'tests/fixtures'),
            'scala_source_sha256': content_id(work / 'query_base', lambda p: p.suffix == '.scala')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--force', action='store_true', help='run full compatibility even for unchanged upstream')
    parser.add_argument('--check-only', action='store_true', help='refuse changed versions without running compatibility')
    args = parser.parse_args()
    baseline = json.loads(BASELINE.read_text())
    now = identity(REPO)
    config_identity = required_config_identity(REPO)
    validate_baseline_configs(baseline, config_identity, args.force)
    same_version = now['version'] == baseline['upstream']['version']
    if same_version and not args.force:
        for field, name in (('matrix_sha256', 'difftest.sh'),
                            ('owned_server_sha256', 'owned-server.sh'),
                            ('pins_sha256', 'difftest-pins')):
            if digest((REPO / 'dev' / name).read_bytes()) != baseline.get(field):
                raise ValueError(f'{name} changed since recorded evidence; use --force for full compatibility')
    if same_version and now != baseline['upstream'] and not args.force:
        raise ValueError('same-version upstream source/metadata drift; review the drift and use --force for a complete maintenance comparison')
    if same_version and not args.force:
        print(f"Upstream {now['version']} unchanged ({now['python_tree_id']}); full compatibility not run. "
              f"Evidence: {baseline['evidence']['kind']}.")
        return
    if args.check_only:
        raise ValueError('full upstream compatibility required (check-only mode)')
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    previous = signal.signal(signal.SIGTERM, interrupted)
    try:
        state = full_compat(REPO, now, config_identity)
    finally:
        signal.signal(signal.SIGTERM, previous)
    # Atomic replacement only after complete success and post-run identity checks.
    with tempfile.NamedTemporaryFile('w', dir=BASELINE.parent, prefix='.upstream-compat-', delete=False) as f:
        json.dump(state, f, indent=2)
        f.write('\n')
        temporary = Path(f.name)
    temporary.replace(BASELINE)
    print(f"Upstream {now['version']}: full compatibility passed; baseline recorded.")


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('upstream compatibility: interrupted; owned processes cleaned', file=sys.stderr)
        sys.exit(130)
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as exc:
        print(f'upstream compatibility: {exc}', file=sys.stderr)
        sys.exit(2)
