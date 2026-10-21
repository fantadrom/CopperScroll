#!/usr/bin/env python3
"""Documentation consistency checks only; no claim of engine correctness."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[3]
ARCH = ROOT / 'docs/architecture'
DOCS = ['container-design.md', 'container-format-v1.md', 'container-test-plan.md',
        'container-prototype-plan.md', 'compaction-requirements-addendum.md',
        'container-review-resolution.md', 'reconciliation-materials/README.md', 'container-p4-extension.md']


def slugs(text):
    seen, result = Counter(), set()
    for line in text.splitlines():
        if not line.startswith('#'):
            continue
        title = re.sub(r'^#+\s*', '', line).strip().lower()
        title = re.sub(r'[^\w\s-]', '', title).replace(' ', '-')
        suffix = f'-{seen[title]}' if seen[title] else ''
        result.add(title + suffix)
        seen[title] += 1
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--baseline', help='Also assert docs-only diff and historical files unchanged')
    args = ap.parse_args()
    links, tables = 0, 0
    for name in DOCS:
        path = ARCH / name
        text = path.read_text()
        assert text.count('```') % 2 == 0, name
        row_widths = []
        for line in text.splitlines() + ['']:
            if line.startswith('|'):
                assert line.endswith('|'), (name, line)
                row_widths.append(line.count('|'))
            elif row_widths:
                assert len(set(row_widths)) == 1, (name, row_widths)
                row_widths = []
                tables += 1
        for target in re.findall(r'\]\(([^)]+)\)', text):
            if target.startswith(('https:', 'http:', 'mailto:')):
                continue
            filename, _, fragment = target.partition('#')
            dest = path.parent / unquote(filename) if filename else path
            assert dest.exists(), (name, target)
            if fragment:
                assert unquote(fragment) in slugs(dest.read_text()), (name, target)
            links += 1
        assert not re.search(r'docs\.google\.com|drive\.google\.com|sediment://', text), name
    tests = (ARCH / 'container-test-plan.md').read_text()
    ids = re.findall(r'^\| ([A-Z]+-\d+) P[01] \|', tests, re.M)
    duplicates = [k for k, count in Counter(ids).items() if count != 1]
    assert not duplicates, duplicates
    resolution = (ARCH / 'container-review-resolution.md').read_text()
    for key in [f'R-{i:02}' for i in range(1, 8)] + [f'C-{i:02}' for i in range(1, 7)]:
        assert f'| {key} |' in resolution, key
    normative = '\n'.join((ARCH / x).read_text() for x in DOCS[:5])
    for forbidden in ['containerMinor=0', 'Команда compact доступна обеим зонам',
                      'Команда доступна в корзине decoy', 'Note/media records и ZDK прежние;',
                      'временное получение второго ключа допускается только при проверке/смене']:
        assert forbidden not in normative, forbidden
    assert '| 10 | 2 | containerMinor=1 |' in normative
    assert 'Folder payload = null' in normative
    if args.baseline:
        changed = subprocess.check_output(['git', 'diff', '--name-only', args.baseline],
                                          cwd=ROOT, text=True).splitlines()
        assert all(p.startswith('docs/architecture/') for p in changed), changed
        historical = subprocess.check_output([
            'git', 'diff', args.baseline, '--', 'docs/architecture/container-critical-review.md',
            'docs/architecture/review-materials'], cwd=ROOT, text=True)
        assert not historical, 'Historical review modified'
    print(json.dumps({'local_links_and_anchors': links, 'tables': tables,
                      'unique_future_test_ids': len(ids), 'resolution_rows': 13,
                      'baseline_checked': args.baseline,
                      'scope': 'static docs checks, not semantic proof or engine tests'}, indent=2))


if __name__ == '__main__':
    main()
