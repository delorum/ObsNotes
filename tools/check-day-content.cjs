// Read-only parity check against diaryCleanUtils, plus ObsNotes-only empty prompt values.
// Usage: OBSNOTES_CONTENT_CHECK_CP=... node tools/check-day-content.cjs /path/to/vault
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
const {execFileSync} = require('node:child_process');
const modulePath = path.join(process.argv[2], 'Скрипты/Modules/Дневник/diaryCleanUtils.js');
const script = fs.readFileSync(modulePath, 'utf8');
const js = vm.runInNewContext(script, {});
const java = (...args) => execFileSync('java', ['-cp', process.env.OBSNOTES_CONTENT_CHECK_CP,
  'CheckDayContentRules', modulePath, ...args], {encoding: 'utf8'});
const rows = java().trim().split('\n').map(line => line.split('\t'));
const lists = type => rows.filter(row => row[0] === type).map(row => Buffer.from(row[1], 'base64').toString()).sort();
assert.deepEqual(lists('line'), [...js.excludeLines].sort());
assert.deepEqual(lists('contains'), [...js.excludeContains].sort());
const prompts = ['Погода:: Заполни тестовое значение!', 'ShortDescr:: Заполни тестовое описание!',
  'ShortDescrWeek:: Заполни тестовое описание!', 'ShortDescrMonth:: Заполни тестовое описание!'];
const cases = [...js.excludeLines, ...js.excludeContains.map(s => 'prefix ' + s + ' suffix'),
  '- [ ] #task/dairy/fact action', '- [x] #task/dairy/fact action',
  '- [x] #task/dairy/car Не ездил на машине ✅ 2026-11-16',
  '- [x] #task/dairy/diet не соблюдал диету ✅ 2026-11-16',
  '- [x] #task/dairy/car Ездил на машине ✅ 2026-11-16',
  'Вес::', 'Вес:: 90', 'ФотоПогоды::', 'ShortDescr::', 'ShortDescrWeek::',
  'ShortDescrMonth::', 'ShortDescrYear::', ...prompts, 'ordinary note'];
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'obsnotes-content-parity-'));
try {
  const input = path.join(temp, 'cases.txt');
  fs.writeFileSync(input, cases.map(s => Buffer.from(s).toString('base64')).join('\n'));
  const native = java(input).trim().split('\n').map(line => line.split('\t'));
  for (let i = 0; i < cases.length; i++) {
    const expectedHidden = js.excludeLines.includes(cases[i]) || js.excludeContains.some(s => cases[i].includes(s));
    assert.equal(native[i][0], String(expectedHidden), `hide case ${i}`);
    assert.equal(native[i][1], String(prompts.includes(cases[i]) ? false : !js.shouldCleanLine(cases[i])), `count case ${i}`);
  }
  console.log(`Parity passed: ${js.excludeLines.length} excluded lines, ${js.excludeContains.length} substring rules, ${cases.length} counter cases (including ${prompts.length} ObsNotes prompt overrides).`);
} finally { fs.rmSync(temp, {recursive: true}); }
