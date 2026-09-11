"""Build orchestration only: never imports or executes the Python oracle."""
import fcntl
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys

repo = Path(sys.argv[1]).resolve()
suites = sys.argv[2:]
cache = Path(os.environ.get('SCALA_TEST_CACHE', str(repo / '.dev' / 'scala-tests'))).expanduser().resolve()
cache.mkdir(parents=True, exist_ok=True)
isabelle = Path(shutil.which('isabelle') or '').resolve()
if not isabelle.is_file():
    sys.exit('isabelle executable required on PATH')
# Identity includes all distribution files that can influence Scala compilation.
installation = isabelle.parent.parent
h = hashlib.sha256()
for directory in (installation / 'lib', installation / 'etc', installation / 'contrib'):
    for path in sorted(directory.rglob('*')):
        if path.is_file():
            stat = path.stat()
            h.update(str(path).encode())
            h.update(f'{stat.st_size}:{stat.st_mtime_ns}'.encode())
h.update(isabelle.read_bytes())
h.update(str(isabelle).encode())
# Snapshot bytes once, so an edit during compilation cannot poison its cache key.
sources = sorted((repo / 'query_base').rglob('*'))
sources = [p for p in sources if p.is_file() and 'classes' not in p.parts and '__pycache__' not in p.parts]
source_bytes = {p: p.read_bytes() for p in sources}
source_modes = {p: p.stat().st_mode & 0o777 for p in sources}
for path, data in source_bytes.items():
    h.update(str(path.relative_to(repo)).encode())
    h.update(data)
    h.update(str(source_modes[path]).encode())
h.update(Path(__file__).read_bytes())
engine_id = h.hexdigest()
engine = cache / ('engine-' + engine_id)
engine.mkdir(exist_ok=True)
env = dict(os.environ, USER_HOME=str(engine / 'home'), ISABELLE_QUERY_NO_CDS='1')
for name in ('CLASSPATH', 'ISABELLE_COMPONENTS', 'ISABELLE_QUERY_JAR', 'ISABELLE_QUERY_BASE_HOME'):
    env.pop(name, None)
def run(args, timeout=240, **kwargs):
    return subprocess.run([str(isabelle), *args], env=env, cwd=repo, check=True, timeout=timeout, **kwargs)
with (engine / 'lock').open('w') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    if not (engine / 'ready').exists():
        print(f'Scala regression: compiling private engine ({engine_id[:12]})', flush=True)
        component = engine / 'query_base'
        if component.exists():
            shutil.rmtree(component)
        for source, data in source_bytes.items():
            dest = component / source.relative_to(repo / 'query_base')
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            dest.chmod(source_modes[source])
        (engine / 'home').mkdir(exist_ok=True)
        run(['components', '-u', str(component)], timeout=30)
        run(['scala_build'])
        (engine / 'ready').write_text(engine_id + '\n')
tests = sorted((repo / 'tests/scala/support').glob('*.scala'))
for suite in suites:
    tests += sorted((repo / 'tests/scala' / suite).glob('*.scala'))
test_bytes = {p: p.read_bytes() for p in tests}
for path, data in test_bytes.items():
    h.update(str(path.relative_to(repo)).encode())
    h.update(data)
h.update(','.join(suites).encode())
identity = h.hexdigest()
work = cache / ('suite-' + identity)
work.mkdir(exist_ok=True)
with (work / 'lock').open('w') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    if not (work / 'ready').exists():
        print(f'Scala regression: compiling {",".join(suites)} ({identity[:12]})', flush=True)
        cp = run(['getenv', '-b', 'ISABELLE_SETUP_CLASSPATH'], capture_output=True, text=True).stdout.strip()
        cp += ':' + run(['getenv', '-b', 'ISABELLE_CLASSPATH'], capture_output=True, text=True).stdout.strip()
        classes = work / 'classes'
        if classes.exists():
            shutil.rmtree(classes)
        classes.mkdir()
        frozen = []
        for path, data in test_bytes.items():
            dest = work / 'tests' / path.relative_to(repo / 'tests/scala')
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            frozen.append(str(dest))
        run(['scalac', '-d', str(classes), '-classpath', cp, *frozen])
        (work / 'ready').write_text(identity + '\n')
    else:
        print(f'Scala regression: cache hit {identity[:12]} ({",".join(suites)})', flush=True)
env['CLASSPATH'] = str(work / 'classes')
try:
    run(['java', 'isabelle.query.regression.Runner', str(repo / 'tests/scala'), *suites], timeout=180)
except subprocess.CalledProcessError as exc:
    sys.exit(exc.returncode)
