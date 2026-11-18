// A run owns its loopback listener and child process group. No external Node dependencies.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const net = require('node:net');
const {spawn} = require('node:child_process');
const adapters = require('./web-adapters.cjs');
const configPath = process.argv[2];
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const runDir = path.dirname(configPath);
const statusPath = path.join(runDir, 'status.json');
const controlPath = path.join(runDir, 'stop');
// The app writes this when the project's files changed and the server doesn't reload itself.
const restartPath = path.join(runDir, 'restart');
config.compatibilityCache ||= path.join(runDir,'compatibility');
let phase = 'Starting', child = null, ending = false, backendPort = null;
// recipe: how the dev command was launched, reused on restart. previous: a dev command being restarted.
let recipe = null, previous = null, generation = 0;
const sockets = new Set();
let diagnosticText = '', lastHttpStatus = null;
const preload = path.join(__dirname,'web-node-preload.cjs');
const bins=[...new Set([config.cwd,config.installCwd].map(dir=>path.join(dir,'node_modules','.bin')))].join(path.delimiter);
const runtimeEnv = {...process.env, PATH:bins+path.delimiter+(process.env.PATH||''), YURLUNGR_NODE_BINARY:config.node,
  NEXT_TELEMETRY_DISABLED:'1',ASTRO_TELEMETRY_DISABLED:'1',NUXT_TELEMETRY_DISABLED:'1'};
if (fs.existsSync(preload)) runtimeEnv.NODE_OPTIONS = `${process.env.NODE_OPTIONS || ''} --require ${JSON.stringify(preload)}`.trim();
function status(state, message = '', code = '', recovery = '') {
  phase = state;
  fs.writeFileSync(statusPath + '.tmp', JSON.stringify({id: config.id, state, message, code, recovery}), {mode: 0o600});
  fs.renameSync(statusPath + '.tmp', statusPath);
  console.log(`[Yurlungr] ${state}${message ? ': ' + message : ''}`);
}
function killGroup(signal) {
  for (const task of [child, previous]) if (task?.pid) { try { process.kill(-task.pid, signal); } catch (_) {} }
}
async function finish(state, message = '', code = '', recovery = '') {
  if (ending) return;
  ending = true;
  killGroup('SIGTERM');
  for (const socket of sockets) socket.destroy();
  server.close();
  await new Promise(resolve => setTimeout(resolve, child || previous ? 1200 : 0));
  killGroup('SIGKILL');
  status(state, message, code, recovery);
  process.exit(state === 'Stopped' ? 0 : 1);
}
function launch(executable, args, cwd, extraEnv = {}) {
  const task=spawn(executable,args,{cwd,env:{...runtimeEnv,...extraEnv,YURLUNGR_WEB_RUN_ID:config.id},
    detached:true,stdio:['pipe','pipe','pipe']});
  for(const [stream,output] of [[task.stdout,process.stdout],[task.stderr,process.stderr]]) stream.on('data',chunk=> {
    output.write(chunk);
    diagnosticText=(diagnosticText+chunk.toString()).slice(-32768);
  });
  return task;
}
process.stdin.on('data',chunk=> {if(child?.stdin?.writable)child.stdin.write(chunk);});
async function capture(executable,args) {
  return new Promise((resolve,reject)=> {
    let text='';
    const task=spawn(executable,args,{cwd:config.cwd,env:runtimeEnv,detached:true,stdio:['ignore','pipe','pipe']});
    child=task;
    const timer=setTimeout(()=>{try{process.kill(-task.pid,'SIGTERM');}catch(_){}reject(adapters.problem('CLI_TIMEOUT','The framework CLI did not return its supported options. Try Project script.'));},15000);
    task.stdout.on('data',data=>{text=(text+data).slice(-65536);});task.stderr.on('data',data=>{text=(text+data).slice(-65536);});
    task.once('error',error=>{clearTimeout(timer);reject(error);});
    task.once('exit',code=>{clearTimeout(timer);if(child===task)child=null;
      if(code===0)resolve(text);else {diagnosticText=(diagnosticText+text).slice(-32768);process.stderr.write(text);reject(adapters.problem('CLI_SETUP','Framework setup failed while checking launch options. Inspect Logs or try Project script.'));}});
  });
}
function failure(message,error) {
  fs.writeFileSync(path.join(runDir,'diagnostics.log'),diagnosticText,{mode:0o600});
  const diagnosed=adapters.diagnostic(diagnosticText);
  if(diagnosed) {
    const recovery=config.kind==='Next'&&diagnosed.code==='NATIVE_COMPILER'?'nextCompiler':diagnosed.recovery;
    return finish(diagnosed.state,diagnosed.message,diagnosed.code,recovery);
  }
  const code=error?.code || 'SERVER_EXIT';
  if(code==='TURBOPACK_SCRIPT')return finish('CompatibilityFailure',message,code,'framework');
  const needsSetup=['CSS_VERSION','NODE_VERSION','MISSING_DEPENDENCY','MISSING_SCRIPT','MISSING_CLI','CLI_SETUP','CLI_TIMEOUT','CUSTOM_REQUIRED','UNSUPPORTED_CLI','UNSUPPORTED_VERSION'].includes(code);
  return finish(needsSetup?'NeedsSetup':'Failed',message,code,error?.recovery || (needsSetup?'setup':''));
}

async function install() {
  status('Installing', 'Installing project dependencies. Output follows.');
  const args = config.manager === 'npm'
    ? [(fs.existsSync(path.join(config.installCwd, 'package-lock.json')) || fs.existsSync(path.join(config.installCwd, 'npm-shrinkwrap.json'))) ? 'ci' : 'install']
    : ['install', ...(config.manager === 'pnpm' && fs.existsSync(path.join(config.installCwd, 'pnpm-lock.yaml')) ? ['--frozen-lockfile'] : [])];
  await new Promise((resolve, reject) => {
    child = launch(config.manager, args, config.installCwd);
    child.once('error', reject);
    child.once('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`Dependency installation ended (${signal || code}). See terminal output.`)));
  });
  killGroup('SIGTERM');
  child = null;
}
const mime = {'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8',
  '.mjs':'text/javascript; charset=utf-8','.json':'application/json','.svg':'image/svg+xml','.png':'image/png',
  '.jpg':'image/jpeg','.jpeg':'image/jpeg','.webp':'image/webp','.ico':'image/x-icon','.wasm':'application/wasm',
  '.woff':'font/woff','.woff2':'font/woff2','.txt':'text/plain; charset=utf-8'};
const root = fs.realpathSync(config.cwd);
function staticRequest(req, res) {
  if (!['GET','HEAD'].includes(req.method)) { res.writeHead(405); res.end(); return; }
  try {
    const pathname = decodeURIComponent(req.url.split('?')[0]);
    if (pathname.split('/').some(segment => segment.startsWith('.') && segment !== '.')) { res.writeHead(403); res.end('Forbidden'); return; }
    let file = path.resolve(root, '.' + pathname);
    if (fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
    file = fs.realpathSync(file);
    if (!file.startsWith(root + path.sep) || path.relative(root,file).split(path.sep).some(segment => segment.startsWith('.')) || !fs.statSync(file).isFile()) { res.writeHead(403); res.end('Forbidden'); return; }
    res.writeHead(200, {'Content-Type': mime[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Cache-Control':'no-store'});
    if (req.method === 'HEAD') res.end(); else fs.createReadStream(file).on('error', () => res.destroy()).pipe(res);
  } catch (_) { res.writeHead(404); res.end('Not found'); }
}
const server = http.createServer((req, res) => {
  if (req.url === '/.yurlungr/health/' + config.id) {
    res.writeHead(phase === 'Running' ? 200 : 503, {'Content-Type':'application/json'});
    res.end(JSON.stringify({id:config.id, state:phase})); return;
  }
  if (phase !== 'Running') { res.writeHead(503, {'Retry-After':'1'}); res.end('The app is starting. Check its Yurlungr terminal.'); return; }
  if (config.kind === 'Static') { staticRequest(req,res); return; }
  const request = http.request({host:'127.0.0.1',port:backendPort,path:req.url,method:req.method,
    headers:{...req.headers,'x-forwarded-host':req.headers.host,'x-forwarded-proto':'http','x-forwarded-port':String(config.port)}}, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
  request.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end('Dev server unavailable.'); });
  req.on('aborted', () => request.destroy()); req.pipe(request);
});
server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
// Forward framework HMR WebSocket handshakes and byte stream through the owned listener.
server.on('upgrade', (req, socket, head) => {
  // Node drops the server's error listener on upgraded sockets: without this, a browser that hangs up
  // mid-handshake raises an uncaught ECONNRESET and takes the whole run down.
  socket.on('error', () => socket.destroy());
  if (phase !== 'Running' || !backendPort) { socket.destroy(); return; }
  const request = http.request({host:'127.0.0.1',port:backendPort,path:req.url,headers:{...req.headers,'x-forwarded-host':req.headers.host,'x-forwarded-proto':'http','x-forwarded-port':String(config.port)}});
  request.on('upgrade', (response, upstream, upstreamHead) => {
    socket.write(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n` + Object.entries(response.headers).map(([k,v]) => `${k}: ${v}\r\n`).join('') + '\r\n');
    if (upstreamHead.length) socket.write(upstreamHead);
    if (head.length) upstream.write(head);
    socket.on('error', () => upstream.destroy()); upstream.on('error', () => socket.destroy());
    socket.on('close', () => upstream.destroy()); upstream.on('close', () => socket.destroy());
    socket.pipe(upstream).pipe(socket);
  });
  request.on('error', () => socket.destroy()); request.on('response', () => socket.destroy()); request.end();
});
server.on('error', error => finish('Failed', error.code === 'EADDRINUSE' ? `Port ${config.port} is in use. Choose another port.` : error.message));
async function unusedPort() {
  return new Promise((resolve,reject) => { const reservation=net.createServer(); reservation.on('error',reject);
    reservation.listen(0,'127.0.0.1',() => {const port=reservation.address().port; reservation.close(() => resolve(port));}); });
}
function backendReady(timeout) {
  return new Promise(resolve => {
    const req=http.get({host:'127.0.0.1',port:backendPort,path:config.healthPath || '/',headers:{host:`127.0.0.1:${config.port}`},timeout},res => {
      lastHttpStatus=res.statusCode;
      res.on('data',chunk=> {if(res.statusCode>=500)diagnosticText=(diagnosticText+chunk.toString()).slice(-32768);});
      res.on('end',()=>resolve(res.statusCode>=200&&res.statusCode<400));
    });
    req.on('socket',socket=>{const connected=()=>{if(phase==='Starting')status('Compiling','Compiling the initial route…');};
      if(socket.connecting)socket.once('connect',connected);else connected();});
    req.on('timeout',()=>req.destroy()); req.on('error',()=>resolve(false));
  });
}

// Starts the dev command and waits for it to answer. A newer generation (a restart) takes over the wait.
async function serveBackend(gen, restarted) {
  diagnosticText='';
  status('Starting',restarted?'Restarting after changes…':'Waiting for the development server…');
  const task=child=launch(recipe.executable,recipe.args,config.cwd,recipe.env);
  task.once('error',error=>{ if(child===task)failure(error.message,error); });
  task.once('exit',(code,signal)=> { if(!ending&&child===task)failure(`Development command exited (${signal || code}). Check Logs and the selected launch mode.`); });
  const deadline=Date.now()+Math.max(30000,Math.min(900000,(config.startupTimeoutSeconds || 180)*1000));
  while (!ending && gen===generation && Date.now()<deadline) {
    if (await backendReady(Math.max(1000,Math.min(30000,deadline-Date.now())))) {
      if (!ending && gen===generation) {
        status('Running',`http://127.0.0.1:${config.port}`);
        if (restarted) console.log('[Yurlungr] Restarted after changes');
      }
      return;
    }
    await new Promise(resolve=>setTimeout(resolve,400));
  }
  if(!ending&&gen===generation)failure(`The initial route did not become ready${lastHttpStatus?' (HTTP '+lastHttpStatus+')':''}. Check Logs, readiness path, or increase the startup timeout.`);
}
// Stops the dev command's whole process group, then starts it again with the same recipe and backend port.
async function restartBackend() {
  if (ending || previous || !recipe || !child) return;
  const gen=++generation, old=child;
  previous=old; child=null;
  console.log('\n[Yurlungr] Files changed: restarting the server…');
  await new Promise(resolve => {
    if (old.exitCode !== null || old.signalCode !== null) { resolve(); return; }
    const timer=setTimeout(resolve,4000);
    old.once('exit',() => { clearTimeout(timer); resolve(); });
    try { process.kill(-old.pid,'SIGTERM'); } catch (_) { clearTimeout(timer); resolve(); }
  });
  // Whatever is left of the group goes too, so the backend port is free again.
  try { process.kill(-old.pid,'SIGKILL'); } catch (_) {}
  await new Promise(resolve=>setTimeout(resolve,200));
  previous=null;
  if (ending) return;
  serveBackend(gen,true).catch(error=>failure(error.message,error));
}

setInterval(() => {
  if (fs.existsSync(controlPath) || process.ppid === 1) finish('Stopped');
  else if (fs.existsSync(restartPath)) { try { fs.unlinkSync(restartPath); } catch (_) {} restartBackend(); }
},200).unref();
process.on('SIGINT',()=>finish('Stopped')); process.on('SIGTERM',()=>finish('Stopped')); process.on('SIGHUP',()=>finish('Stopped'));
process.on('uncaughtException',error=>finish('Failed',error.message));
status('Starting');
server.listen(config.port,'127.0.0.1',async () => {
  try {
    if (config.install) await install();
    if (ending) return;
    if (config.kind === 'Static') { status('Running',`http://127.0.0.1:${config.port}`); return; }
    if(config.repairNextCompiler) {
      status('Installing','Installing the matching Next.js WASM compiler cache…');
      await adapters.installNextCompiler(config);
    }
    if(ending)return;
    const redirects=await adapters.prepareCssCompatibility(config,async(cache,spec)=> {
      status('Installing','Installing the matching Lightning CSS WASM fallback in app cache…');
      fs.mkdirSync(cache,{recursive:true});
      const npm=path.join(path.dirname(config.node),'npm');
      await new Promise((resolve,reject)=> {
        child=launch(npm,['install','--no-save','--package-lock=false','--ignore-scripts','--no-audit','--no-fund',spec],cache);
        child.once('error',reject);
        child.once('exit',(code,signal)=>code===0?resolve():reject(adapters.problem('CSS_VERSION',`Lightning CSS fallback installation ended (${signal||code}). Check Logs and retry compatibility setup.`)));
      });
      killGroup('SIGTERM');child=null;
    });
    if(ending)return;
    if(Object.keys(redirects).length)runtimeEnv.YURLUNGR_CSS_REDIRECTS=JSON.stringify(redirects);
    status('Starting','Checking framework and runtime requirements…');
    backendPort=await unusedPort();
    // Old saved runs retain their previous script behavior.
    if(!config.mode)config.mode=config.command?.trim()?'Custom':'Script';
    recipe=await adapters.buildLaunch(config,backendPort,capture);
    if(ending)return;
    await serveBackend(generation,false);
  } catch(error) { failure(error.message,error); }
});
