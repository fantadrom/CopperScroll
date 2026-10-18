#!/usr/bin/env python3
"""S1 portability witness; not an Android test or a Unicode16 implementation.

Unicode16 expected bytes come from the published conformance vector:
https://www.unicode.org/Public/16.0.0/ucd/NormalizationTest.txt
11383;11383;11382 113C9;11383;11382 113C9;
UAX15 requires NFC(column3) == column2 for this vector.
"""
import json
import unicodedata

source = "\U00011382\U000113C9"
expected_unicode16 = "\U00011383"
actual = unicodedata.normalize("NFC", source)
version = tuple(map(int, unicodedata.unidata_version.split(".")))
if version < (16, 0, 0):
    assert actual == source
else:
    assert actual == expected_unicode16
assert source.encode().hex() == "f0918e82f0918f89"
assert expected_unicode16.encode().hex() == "f0918e83"
print(json.dumps({
    "runtime_unicode_version": unicodedata.unidata_version,
    "input_utf8": source.encode().hex(),
    "actual_runtime_nfc_utf8": actual.encode().hex(),
    "unicode16_conformance_expected_utf8": expected_unicode16.encode().hex(),
    "different_from_unicode16_vector": actual != expected_unicode16,
    "scope": "runtime NFC versus published expected value; no container tested",
}, indent=2))
