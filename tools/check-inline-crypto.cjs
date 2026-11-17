#!/usr/bin/env node
// Exercise the installed plugin itself, without reading plugin settings or diary contents.
const fs=require('fs'),vm=require('vm'),cp=require('child_process'),path=require('path'),os=require('os');
const root=path.resolve(__dirname,'..');
const code=fs.readFileSync(process.argv[2],'utf8');
const start=code.indexOf('var CryptoFactory = class'),end=code.indexOf('// src/UiHelper.ts',start);
if(start<0||end<0)throw Error('CryptoFactory not found in the supplied plugin');
const context={crypto:require('crypto').webcrypto,TextEncoder,TextDecoder,btoa,atob};
vm.createContext(context);vm.runInContext(code.slice(start,end)+';globalThis.factory=new CryptoFactory()',context);
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'obsnotes-crypto-'));
const classes=path.join(root,'core/build/classes/java/main');
const depRoot=path.join(os.homedir(),'.gradle/caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on/1.86');
const bc=fs.readdirSync(depRoot).flatMap(hash=>fs.readdirSync(path.join(depRoot,hash)).filter(name=>name.endsWith('.jar')).map(name=>path.join(depRoot,hash,name)))[0];
if(!bc)throw Error('Build core before running the interoperability check');
const b64=s=>Buffer.from(s).toString('base64');
function java(mode,clear,password){
 return Buffer.from(cp.execFileSync('java',['-cp',temp+path.delimiter+classes+path.delimiter+bc,'CheckInlineCrypto',mode],
   {input:b64(password)+'\n'+b64(clear)+'\n',encoding:'utf8'}).trim(),'base64').toString();
}
(async()=>{
 try{
  cp.execFileSync('javac',['-encoding','UTF-8','-cp',classes,'-d',temp,path.join(__dirname,'CheckInlineCrypto.java')]);
  let count=0;
  for(const [password,clear] of [['SyntheticPassword!','Phone fixture'],['Пароль🔑e\u0301','Текст\n\nНовый абзац 🦉'],['AnotherPassword','']]){
   const fromPlugin=await context.factory.encryptToBase64(clear,password);
   if(java('decrypt',fromPlugin,password)!==clear)throw Error('Plugin → Java mismatch');
   const fromJava=java('encrypt',clear,password);
   if(await context.factory.decryptFromBase64(fromJava,password)!==clear)throw Error('Java → plugin mismatch');
   if(await context.factory.decryptFromBase64(fromJava,password+'x')!==null)throw Error('Wrong password accepted');
   count+=3;
  }
  console.log(`Inline encryption: ${count} real-plugin interoperability checks passed (synthetic data only)`);
 }finally{fs.rmSync(temp,{recursive:true,force:true});}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
