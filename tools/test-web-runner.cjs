// Integration tests for the real supervisor, without Android or package downloads.
const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const net = require('node:net');
const {spawn} = require('node:child_process');
const fixtures=[];
const nodeBinary=process.env.YURLUNGR_TEST_NODE||process.execPath;
const runner=path.resolve(__dirname,'../app/src/main/assets/web-runner.cjs');
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function freePort() { const s=net.createServer(); await new Promise(resolve=>s.listen(0,'127.0.0.1',resolve)); const p=s.address().port; await new Promise(resolve=>s.close(resolve)); return p; }
async function request(port,url='/') { return new Promise((resolve,reject)=> {
  const r=http.get({host:'127.0.0.1',port,path:url},res=> {let body='';res.on('data',chunk=>body+=chunk);res.on('end',()=>resolve({code:res.statusCode,body}));});r.on('error',reject);
}); }
async function fixture(overrides={}) {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'yurlungr-web-')); const cwd=path.join(dir,"site with 'quotes'"); fs.mkdirSync(cwd);
  fs.writeFileSync(path.join(cwd,'index.html'),'<h1>Local preview</h1>');
  const config={id:path.basename(dir),cwd,installCwd:cwd,kind:'Static',manager:'npm',script:'dev',port:await freePort(),install:false,node:nodeBinary,bash:process.env.YURLUNGR_TEST_BASH||'/bin/bash',...overrides};
  fs.writeFileSync(path.join(dir,'config.json'),JSON.stringify(config));
  const child=spawn(nodeBinary,[runner,path.join(dir,'config.json')],{stdio:['ignore','pipe','pipe']});
  const result={dir,cwd,config,child,logs:'',exit:null};child.stdout.on('data',data=>result.logs+=data);child.stderr.on('data',data=>result.logs+=data);child.on('exit',code=>result.exit=code);
  fixtures.push(result);return result;
}
async function state(f,expected) { const deadline=Date.now()+10000;
  while(Date.now()<deadline) {try {const s=JSON.parse(fs.readFileSync(path.join(f.dir,'status.json'),'utf8'));if(s.state===expected)return s;}catch(_){}
    if(f.exit!==null) throw new Error(`Exited waiting for ${expected}: ${f.logs}`);await pause(40);}
  throw new Error(`Timed out waiting for ${expected}: ${f.logs}`);
}
async function stop(f) { fs.writeFileSync(path.join(f.dir,'stop'),f.config.id);const deadline=Date.now()+5000;while(f.exit===null&&Date.now()<deadline)await pause(40);assert.notEqual(f.exit,null,f.logs); }
afterEach(async()=> {for(const f of fixtures.splice(0)){if(f.exit===null)await stop(f);fs.rmSync(f.dir,{recursive:true,force:true});}});

test('static server owns its health endpoint and serves assets',async()=> {
 const f=await fixture();await state(f,'Running');
 assert.equal((await request(f.config.port)).body,'<h1>Local preview</h1>');
 const health=await request(f.config.port,'/.yurlungr/health/'+f.config.id);assert.equal(health.code,200);assert.equal(JSON.parse(health.body).id,f.config.id);
 assert.equal((await request(f.config.port,'/missing')).code,404);
 await stop(f);assert.equal(JSON.parse(fs.readFileSync(path.join(f.dir,'status.json'))).state,'Stopped');
 await assert.rejects(request(f.config.port));
});
test('static site blocks traversal, dotfiles and symlinks to private files',async()=> {
 const f=await fixture();fs.writeFileSync(path.join(f.cwd,'.env'),'DO_NOT_EXPOSE');
 fs.writeFileSync(path.join(f.dir,'outside.txt'),'PRIVATE_OUTSIDE');fs.symlinkSync(path.join(f.dir,'outside.txt'),path.join(f.cwd,'outside.txt'));
 fs.symlinkSync(path.join(f.cwd,'.env'),path.join(f.cwd,'alias.txt'));await state(f,'Running');
 for(const url of ['/.env','/%2e%2e/outside.txt','/outside.txt','/alias.txt']) {const response=await request(f.config.port,url);assert.ok(response.code>=400,url);assert.ok(!response.body.includes('PRIVATE')&&!response.body.includes('DO_NOT_EXPOSE'));}
});
test('occupied port fails without replacing the existing listener',async()=> {
 const existing=http.createServer((req,res)=>res.end('unrelated'));
 await new Promise(resolve=>existing.listen(0,'127.0.0.1',resolve));
 try {const f=await fixture({port:existing.address().port});const status=await state(f,'Failed');assert.match(status.message,/in use/);assert.equal((await request(f.config.port)).body,'unrelated');}
 finally {await new Promise(resolve=>existing.close(resolve));}
});
test('install failure never becomes ready and releases the port',async()=> {
 const f=await fixture({kind:'Vite',install:true,manager:process.env.YURLUNGR_TEST_FALSE||'/bin/false'});const status=await state(f,'Failed');assert.match(status.message,/installation ended/);await pause(100);await assert.rejects(request(f.config.port));
});
test('managed backend readiness, proxy, WebSocket upgrade and process-group cleanup',async()=> {
 // Write the backend first in a separate fixture directory; it also creates a descendant.
 const code=`const http=require('http'),{spawn}=require('child_process'),fs=require('fs');
 const server=http.createServer((req,res)=>res.end('backend '+req.url));
 server.on('upgrade',(req,socket)=>{socket.write('HTTP/1.1 101 Switching Protocols\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket\\r\\n\\r\\n');socket.pipe(socket);});
 server.listen(Number(process.env.PORT),'127.0.0.1');
 const child=spawn(${JSON.stringify(nodeBinary)},['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
 fs.writeFileSync('descendant.pid',String(child.pid));`;
 const command=nodeBinary+' -e '+"'"+code.replaceAll("'","'\\''")+"'";
 const f=await fixture({kind:'Vite',command});await state(f,'Running');
 assert.equal((await request(f.config.port,'/hello')).body,'backend /hello');
 await new Promise((resolve,reject)=> {
  const socket=net.connect(f.config.port,'127.0.0.1');socket.setTimeout(3000,()=>{socket.destroy();reject(new Error('Upgrade timed out'));});
  socket.on('connect',()=>socket.write('GET /hmr HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n'));
  socket.once('data',data=>{assert.match(data.toString(),/101 Switching Protocols/);socket.destroy();resolve();});socket.on('error',reject);
 });
 const pid=Number(fs.readFileSync(path.join(f.cwd,'descendant.pid')));await stop(f);
 let alive=false;try{process.kill(pid,0);alive=true;}catch(_){}
 if(alive&&process.platform==='linux'){try {const stat=fs.readFileSync('/proc/'+pid+'/stat','utf8');assert.match(stat,/\) Z /,'descendant must be gone or reaped zombie');} catch(error) {if(error.code!=='ENOENT')throw error;}}else assert.equal(alive,false);
});
test('backend exits are failures instead of idle-shell running states',async()=> {
 const f=await fixture({kind:'Vite',command:'exit 17'});const status=await state(f,'Failed');assert.match(status.message,/17/);
});

test('first-route compilation and HTTP errors do not prematurely report Running',async()=> {
 const code=`const http=require('http');let ready=false;setTimeout(()=>ready=true,2500);
 http.createServer((req,res)=>{if(req.url!=='/login'){res.writeHead(404);return res.end('not here');}res.writeHead(ready?200:500);res.end(ready?'compiled':'compiling');}).listen(Number(process.env.PORT),'127.0.0.1');`;
 const command=nodeBinary+' -e '+"'"+code.replaceAll("'","'\\''")+"'";
 const f=await fixture({kind:'Script',mode:'Custom',command,healthPath:'/login'});
 await state(f,'Compiling');assert.equal((await request(f.config.port)).code,503);
 await state(f,'Running');assert.equal((await request(f.config.port,'/login')).body,'compiled');
 assert.equal((await request(f.config.port)).code,404);
});
test('proxy preserves public origin headers for SSR and authentication redirects',async()=> {
 const code=`require('http').createServer((req,res)=>res.end(JSON.stringify(req.headers))).listen(Number(process.env.PORT),'127.0.0.1')`;
 const command=nodeBinary+' -e '+"'"+code.replaceAll("'","'\\''")+"'";
 const f=await fixture({kind:'Script',mode:'Custom',command});await state(f,'Running');
 const headers=JSON.parse((await request(f.config.port)).body);
 assert.equal(headers.host,'127.0.0.1:'+f.config.port);assert.equal(headers['x-forwarded-host'],headers.host);
 assert.equal(headers['x-forwarded-proto'],'http');assert.equal(headers['x-forwarded-port'],String(f.config.port));
});
test('compiler and environment failures have distinct recovery states',async()=> {
 const native=await fixture({kind:'Next',mode:'Custom',command:"echo 'Failed to load SWC binary for android/arm64' >&2; exit 1"});
 const failed=await state(native,'CompatibilityFailure');assert.equal(failed.recovery,'nextCompiler');
 const missing=await fixture({kind:'Script',mode:'Custom',command:"echo 'DATABASE_URL environment variable is required' >&2; exit 1"});
 assert.equal((await state(missing,'NeedsSetup')).code,'MISSING_ENV');
});
