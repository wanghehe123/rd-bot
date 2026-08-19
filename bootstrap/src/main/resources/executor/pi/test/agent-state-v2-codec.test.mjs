import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

import {
  canonicalizeStateV2,
  decodeAndVerifyStateV2,
  hashStateV2,
} from "../src/agent-state-v2-codec.mjs";

const fixturePath = resolve(
  import.meta.dirname,
  "../../../../../../../test-fixtures/protocol/rd-agent-state-v2-canonical-fixtures.json",
);
const fixtures = JSON.parse(await readFile(fixturePath, "utf8"));

test("matches Java shared canonical and hash fixtures", () => {
  for (const fixture of fixtures.valid) {
    assert.equal(canonicalizeStateV2(fixture.input), fixture.canonical, fixture.name);
    assert.equal(hashStateV2(fixture.input), fixture.hash, fixture.name);
    assert.deepEqual(decodeAndVerifyStateV2(fixture.canonical, fixture.hash), fixture.input, fixture.name);
  }
});

test("rejects shared unsafe, malformed, and hash mismatch fixtures", () => {
  for (const fixture of fixtures.invalid) {
    assert.throws(
      () => decodeAndVerifyStateV2(fixture.json, fixture.hash),
      (error) => String(error.message).toLowerCase().includes(fixture.error),
      fixture.name,
    );
  }
});
