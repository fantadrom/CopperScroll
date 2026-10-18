"""Tiny specification model: demonstrates missing fences, not production behavior."""
from itertools import permutations
import json

def race_model():
    traces = []
    for order in permutations(('check', 'revoke', 'switch')):
        if order.index('check') > order.index('switch'):
            continue
        epoch_valid, authorized, active = True, False, 'source'
        for event in order:
            if event == 'check':
                authorized = epoch_valid
            elif event == 'revoke':
                epoch_valid = False
            elif authorized:
                active = 'candidate'
        if order.index('revoke') < order.index('switch') and active == 'candidate':
            traces.append(list(order))
    return traces

def snapshot_model():
    source = {'main': 0, 'decoy': 0}
    backup = source.copy()
    barrier = False  # permitted if its lifetime is only the backup copy
    assert not barrier
    source['main'] = 1
    candidate = backup.copy()
    active = candidate
    return {'backup': backup, 'source_after_save': source,
            'candidate_activated': active, 'lost_committed_main_revision': 1}

def fenced_model():
    # Revocation and check+switch share one coordinator serialization point.
    for order in permutations(('atomic_check_switch', 'revoke')):
        epoch_valid, active, revoked_before_switch = True, 'source', False
        for event in order:
            if event == 'revoke':
                epoch_valid = False
            else:
                revoked_before_switch = not epoch_valid
                if epoch_valid:
                    active = 'candidate'
        assert not (revoked_before_switch and active == 'candidate')
    return 'both linearization orders satisfy no-switch-after-revocation'

report = {'unfenced_epoch_counterexamples': race_model(),
          'copy_only_barrier_counterexample': snapshot_model(),
          'serialized_fence': fenced_model(),
          'scope': 'abstract documentation scenarios; no storage implementation tested'}
assert ['check', 'revoke', 'switch'] in report['unfenced_epoch_counterexamples']
print(json.dumps(report, ensure_ascii=False, indent=2))
