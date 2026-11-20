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
async function send(port,url='/',headers={}) { return new Promise((resolve,reject)=> {
  const r=http.get({host:'127.0.0.1',port,path:url,headers},res=> {let body='';res.on('data',chunk=>body+=chunk);res.on('end',()=>resolve({code:res.statusCode,headers:res.headers,body}));});r.on('error',reject);
}); }
function echoBackend() {
  const code=`require('http').createServer((req,res)=>res.end(JSON.stringify({url:req.url,headers:req.headers,allowed:process.env.__VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS}))).listen(Number(process.env.PORT),'127.0.0.1')`;
  return nodeBinary+' -e '+"'"+code.replaceAll("'","'\\''")+"'";
}
test('public link requests reach the dev server with a loopback Host and keep the forwarded protocol',async()=> {
 const f=await fixture({kind:'Script',mode:'Custom',command:echoBackend()});await state(f,'Running');
 const seen=JSON.parse((await send(f.config.port,'/a?b=1',{host:'quiet-river.trycloudflare.com','x-forwarded-proto':'https','cf-ray':'1'})).body);
 assert.equal(seen.headers.host,'127.0.0.1:'+f.config.port);
 assert.equal(seen.headers['x-forwarded-host'],'quiet-river.trycloudflare.com');
 assert.equal(seen.headers['x-forwarded-proto'],'https');
 assert.equal(seen.headers['x-forwarded-port'],'443');
 assert.equal(seen.url,'/a?b=1');
 assert.equal(seen.allowed,'.trycloudflare.com');
 // Local requests keep their own Host, and an odd protocol value falls back to http.
 const local=JSON.parse((await send(f.config.port,'/',{host:'localhost:'+f.config.port,'x-forwarded-proto':'gopher'})).body);
 assert.equal(local.headers.host,'localhost:'+f.config.port);assert.equal(local.headers['x-forwarded-proto'],'http');
 assert.equal(local.headers['x-forwarded-port'],String(f.config.port));
});
test('an access key gates public requests only, through a one-time query that becomes a cookie',async()=> {
 const f=await fixture({kind:'Script',mode:'Custom',command:echoBackend()});await state(f,'Running');
 const key='k3y-for-tests_0123456789abcdef';fs.writeFileSync(path.join(f.dir,'access'),key);await pause(400);
 const pub={host:'quiet-river.trycloudflare.com','x-forwarded-proto':'https'};
 assert.equal((await send(f.config.port,'/',pub)).code,401);
 assert.equal((await send(f.config.port,'/?key=wrong',pub)).code,401);
 // Cloudflare's headers count as public even with a loopback Host.
 assert.equal((await send(f.config.port,'/',{host:'127.0.0.1','cf-connecting-ip':'203.0.113.9'})).code,401);
 // The phone itself (preview, health checks) never needs the key.
 assert.equal((await send(f.config.port,'/')).code,200);
 const enter=await send(f.config.port,'/shop?key='+key+'&page=2',pub);
 assert.equal(enter.code,302);assert.equal(enter.headers.location,'/shop?page=2');
 const cookie=enter.headers['set-cookie'][0];
 assert.match(cookie,/^yurlungr_access=k3y-for-tests_0123456789abcdef; Path=\/; HttpOnly; SameSite=Lax/);assert.match(cookie,/; Secure/);
 // A crafted path can't turn the redirect into one to another site.
 assert.equal((await send(f.config.port,'//evil.example/x?key='+key,pub)).headers.location,'/x');
 const inside=await send(f.config.port,'/shop?page=2',{...pub,cookie:'theme=dark; yurlungr_access='+key});
 assert.equal(inside.code,200);
 assert.equal(JSON.parse(inside.body).headers.cookie,'theme=dark','the app never sees the access cookie');
 assert.equal((await send(f.config.port,'/',{...pub,cookie:'yurlungr_access=nope'})).code,401);
 // WebSocket upgrades need it too.
 const upgrade=await new Promise((resolve,reject)=> {
  const socket=net.connect(f.config.port,'127.0.0.1');socket.setTimeout(3000,()=>{socket.destroy();reject(new Error('timed out'));});
  socket.on('connect',()=>socket.write('GET /hmr HTTP/1.1\r\nHost: quiet-river.trycloudflare.com\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n'));
  socket.once('data',data=>{socket.destroy();resolve(data.toString());});socket.on('error',reject);
 });
 assert.match(upgrade,/^HTTP\/1.1 401/);
 // Once the app removes the key (the link stopped), the gate is gone with it.
 fs.unlinkSync(path.join(f.dir,'access'));await pause(400);
 assert.equal((await send(f.config.port,'/',pub)).code,200);
});
test('static sites are gated by the access key too',async()=> {
 const f=await fixture();await state(f,'Running');
 fs.writeFileSync(path.join(f.dir,'access'),'static-key-0123456789');await pause(400);
 const pub={host:'calm-bird.trycloudflare.com'};
 const denied=await send(f.config.port,'/',pub);assert.equal(denied.code,401);assert.ok(!denied.body.includes('Local preview'));
 assert.equal((await send(f.config.port,'/',{...pub,cookie:'yurlungr_access=static-key-0123456789'})).body,'<h1>Local preview</h1>');
});
