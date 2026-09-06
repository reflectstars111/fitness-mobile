"""Maintainer-only snapshot export; NOT a mobile/build/test dependency.

Usage: python scripts/export-reference.py <Good-Badminton checkout>
Executes the two reviewed source files. Use only the trusted original checkout.
"""
import hashlib
import json
from pathlib import Path
import random
import runpy
import shutil
import subprocess
import sys

source = Path(sys.argv[1]).resolve()
root = Path(__file__).resolve().parents[1]
relative = Path('badmintondataprocess/src/badminton_data_process/motion')
geometry = runpy.run_path(str(source / relative / 'geometry.py'))
exercises = runpy.run_path(str(source / relative / 'exercises.py'))
fixtures = root / 'core/src/test/fixtures'
fixtures.mkdir(parents=True, exist_ok=True)
rng = random.Random(20260906)
rows = []
for i in range(128):
    points = [[rng.uniform(0, 639), rng.uniform(0, 479), 1.0] for _ in range(17)]
    if i % 4 == 0:
        points[i % 17][2] = 0.1
    if i % 7 == 0:
        points[(i + 1) % 17][0] = -1
    side = 'left' if i % 2 == 0 else 'right'
    metrics = geometry['frame_metrics'](points, side=side, confidence=.5, width=640, height=480)
    values = [side] + [str(x) for p in points for x in p]
    values += ['null' if v is None else repr(v) for v in metrics.values()]
    rows.append('\t'.join(values))
(fixtures / 'geometry.tsv').write_text('\n'.join(rows) + '\n', encoding='utf-8')
protocols = []
for key, value in exercises['EXERCISES'].items():
    protocols.append({'id': key, 'label': value.label, 'metric': value.metric,
                      'phases': value.phases, 'instructions': value.instructions})
(root / 'protocols').mkdir(exist_ok=True)
(root / 'protocols/exercises.v1.json').write_text(json.dumps({
    'schema_version': 1, 'rule_version': 'research-protocol-1',
    'validation_status': 'unvalidated_mobile_research_reference',
    'phase_semantics': 'offline_names_only_not_live_labels',
    'exercises': protocols}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
paths = [Path('LICENSE'), relative / 'geometry.py', relative / 'exercises.py']
manifest = {
    'schema_version': 1,
    'source_repository': 'https://github.com/reflectstars111/BBA-Badminton-Biomechanics-Analytics',
    'source_head': subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip(),
    'snapshot_note': 'Working-tree snapshot; untracked source files are NOT present in source_head.',
    'files': [{'path': p.as_posix(), 'sha256': hashlib.sha256((source / p).read_bytes()).hexdigest(),
               'git_status': subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--', p.as_posix()], text=True).strip() or 'clean'} for p in paths],
    'generated_artifacts': ['protocols/exercises.v1.json', 'core/src/test/fixtures/geometry.tsv'],
    'fixture_seed': 20260906,
    'fixture_source': 'Synthetic random pixel coordinates; no videos or personal data',
    'modifications': ['Ported geometry and exercise definitions/gates to Java',
                      'Named joints; stricter dimension, side and confidence validation',
                      'Added main signal/view gate; retained missing values as null',
                      'Excluded offline phase backfill, pipeline, model weights and UI']}
(root / 'provenance').mkdir(exist_ok=True)
(root / 'provenance/source-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(root / 'licenses').mkdir(exist_ok=True)
shutil.copyfile(source / 'LICENSE', root / 'licenses/Good-Badminton-Apache-2.0.txt')
