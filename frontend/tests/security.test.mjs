import test from 'node:test';
import assert from 'node:assert/strict';
import { safeExternalUrl } from '../src/utils/safeUrl.ts';

test('stored links cannot execute scripts or switch to an unexpected scheme', () => {
  for (const value of ['javascript:alert(1)', 'data:text/html,test', '//evil.invalid',
    'https://user:password@example.com', 'https://example.com\\@evil.invalid',
    'java\nscript:alert(1)', '/admin', 'file:///etc/passwd']) {
    assert.equal(safeExternalUrl(value), undefined, value);
  }
});
test('ordinary HTTPS archive links remain usable', () => {
  assert.equal(safeExternalUrl('https://example.com/archive?q=one#page'),
    'https://example.com/archive?q=one#page');
});
