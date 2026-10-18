#!/usr/bin/env python3
"""Abstract contract witnesses. No file IO, AEAD, keys or engine implementation."""
from itertools import permutations
import json


def switch_orders():
    traces = []
    for order in permutations(('accept', 'revoke', 'persist')):
        if order.index('persist') < order.index('accept'):
            continue
        epoch, accepted, logical_binding, durable_binding = True, False, 'source', 'source'
        ui_open, outcome = True, 'pending'
        for event in order:
            if event == 'revoke':
                epoch, ui_open = False, False
            elif event == 'accept':
                accepted = epoch
                if accepted:
                    logical_binding = 'candidate'
                else:
                    outcome = 'aborted-before-switch'
            elif accepted:
                durable_binding = 'candidate'
                outcome = 'committed' if epoch else 'unreported'
            assert not (not epoch and ui_open)
        if order.index('revoke') < order.index('accept'):
            assert logical_binding == durable_binding == 'source'
        else:
            assert logical_binding == durable_binding == 'candidate'
        traces.append({'order': order, 'accepted': accepted,
                       'durable_binding': durable_binding, 'outcome': outcome})
    return traces


def frozen_snapshot():
    source = {'main': 1, 'decoy': 2}
    snapshot = source.copy()
    lease = True

    def mutate():
        if lease:
            return 'BlockedByMaintenance'
        source['main'] += 1

    assert mutate() == 'BlockedByMaintenance'
    backup, candidate = snapshot.copy(), snapshot.copy()
    assert source == snapshot == backup == candidate
    return 'PASS: source frozen across backup/build/verify'


def rekey_graph():
    # Records and key labels are symbols, not actual ciphertext/key material.
    edges = {'old-root': ['old-index', 'old-settings'],
             'old-index': ['old-object'], 'old-object': ['old-chunk'],
             'old-settings': [], 'old-chunk': []}
    mapping = {r: r.replace('old-', 'new-') for r in edges}
    new_edges = {mapping[r]: [mapping[x] for x in refs] for r, refs in edges.items()}
    assert set(new_edges).isdisjoint(edges)
    assert all(x in new_edges for refs in new_edges.values() for x in refs)
    old_model = {'objectId': 'stable-object', 'date': 7, 'objectRevision': 9, 'zoneSeq': 4}
    new_model = {**old_model, 'zoneSeq': 5}
    assert all(new_model[k] == old_model[k] for k in ('objectId', 'date', 'objectRevision'))
    unselected_raw = (b'opaque-A', b'opaque-obsolete-B')
    assert tuple(unselected_raw) == unselected_raw
    # Replacing only the root key/reference leaves old dependencies reachable.
    wrong_graph = {'new-root': edges['old-root']}
    assert any(r in edges for refs in wrong_graph.values() for r in refs)
    return {'new_target_records': len(new_edges), 'old_ids_retained': 0,
            'rewrap_or_root_only_rewrite_counterexample': wrong_graph,
            'cryptographic_old_key_rejection': 'NOT TESTED; REKEY-02 engine gate'}


def namespace_witness():
    file_bytes_durable, name_durable = True, False
    assert not (file_bytes_durable and name_durable)
    return 'file force can pass while durable-create predicate fails'


if __name__ == '__main__':
    print(json.dumps({'serialized_switch_orders': switch_orders(),
                      'snapshot': frozen_snapshot(), 'rekey_graph': rekey_graph(),
                      'namespace_counterexample': namespace_witness(),
                      'scope': 'abstract requirements only, not durable IO/AEAD evidence'}, indent=2))
