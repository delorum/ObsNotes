// Execute the actual vault view only against synthetic data; never read a personal note.
// OBSNOTES_CHECKLIST_CP=... node tools/check-checklist.cjs /path/to/vault
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {execFileSync} = require('node:child_process');
const source = fs.readFileSync(path.join(process.argv[2], 'Скрипты/Dataview/dairyCheckList.js'), 'utf8');
const cases = [];
for (let n=1; n<=4; n++) {
  const heading = `## Питание\n### Перекус ${n}\n`;
  cases.push([heading+'яблоко\n', true]);
  cases.push([heading+'\n### Ужин\nужин\n', false]);
  cases.push([heading+'\n### Перекус 5\nдругое\n', false]);
  cases.push([heading+'#### Подраздел\n', false]);
  cases.push([heading+'#### Подраздел\nяблоко\n', true]);
  cases.push([heading+'- [ ] #task/diary fact\n```dataviewjs\nawait dv.view("Скрипты/Dataview/crossPeople");\n```\n', false]);
}
cases.push(['## Питание\n### Перекусы\nяблоко\n',true]);
cases.push(['## Заметки\nсобытие #crossPeople/Перекусы\n',true]);
cases.push(['## Питание\n### Перекус 5\nяблоко\n',false]);
(async () => {
  for (let i=0; i<cases.length; i++) {
    const [note, expected] = cases[i];
    let table;
    const page={file:{path:'fixture.md',name:'2000-01-01-сб',day:{toISODate:()=> '2000-01-01'},tasks:[],mtime:{ts:0}}};
    const dv={current:()=>page,page:p=>p==='Дневник/Служебная_информация' ? {ДатаПоследнегоЗапускаTOperations:{ts:1}} : null,table:(headers,rows)=>{table=rows;}};
    const utils={color:{green:t=>'green:'+t,red:t=>'red:'+t,coloredCount:String},io:{loadLines:async()=>note.split('\n')},crossPeople:{hasTagWithUs:(line,id)=>new RegExp('#(?:crossPeople|crossOneLine|person|personOneLine)/[^\\s]*'+id).test(line)}};
    await new vm.Script('(async()=>{'+source+'\n})()').runInNewContext({dv,utils,moment:()=>({isoWeekday:()=>6})},{timeout:1000});
    const js=table.find(row=>row[0]==='[[#Питание]]')[1].includes('green:Перекусы');
    assert.equal(js,expected,`JS case ${i}`);
    const native=execFileSync('java',['-cp',process.env.OBSNOTES_CHECKLIST_CP,'CheckSnacks',Buffer.from(note).toString('base64')],{encoding:'utf8'}).trim()==='true';
    assert.equal(native,expected,`Native case ${i}`);
  }
  console.log(`Checklist parity passed: ${cases.length} synthetic cases.`);
})().catch(error=>{console.error(error);process.exitCode=1;});
