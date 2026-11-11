// A run owns its loopback listener and child process group. No external Node dependencies.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const net = require('node:net');
const {spawn} = require('node:child_process');
const configPath = process.argv[2];
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const runDir = path.dirname(configPath);
const statusPath = path.join(runDir, 'status.json');
const controlPath = path.join(runDir, 'stop');
let phase = 'Starting', child = null, ending = false, backendPort = null;
const sockets = new Set();
function status(state, message = '') {
  phase = state;
  fs.writeFileSync(statusPath + '.tmp', JSON.stringify({id: config.id, state, message}), {mode: 0o600});
  fs.renameSync(statusPath + '.tmp', statusPath);
  console.log(`[Yurlungr] ${state}${message ? ': ' + message : ''}`);
}
function killGroup(signal) {
  if (child?.pid) { try { process.kill(-child.pid, signal); } catch (_) {} }
}
async function finish(state, message = '') {
  if (ending) return;
  ending = true;
  killGroup('SIGTERM');
  for (const socket of sockets) socket.destroy();
  server.close();
  await new Promise(resolve => setTimeout(resolve, child ? 1200 : 0));
  killGroup('SIGKILL');
  status(state, message);
  process.exit(state === 'Failed' ? 1 : 0);
}
function launch(executable, args, cwd, extraEnv = {}) {
  return spawn(executable, args, {cwd, env: {...process.env, ...extraEnv, YURLUNGR_WEB_RUN_ID: config.id},
    detached: true, stdio: ['ignore', 'inherit', 'inherit']});
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
    headers:{...req.headers, host:`127.0.0.1:${backendPort}`}}, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
  request.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end('Dev server unavailable.'); });
  req.on('aborted', () => request.destroy()); req.pipe(request);
});
server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
// Forward Vite's HMR WebSocket handshake and byte stream through the owned listener.
server.on('upgrade', (req, socket, head) => {
  if (phase !== 'Running' || !backendPort) { socket.destroy(); return; }
  const request = http.request({host:'127.0.0.1',port:backendPort,path:req.url,headers:{...req.headers,host:`127.0.0.1:${backendPort}`}});
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
function backendReady() {
  return new Promise(resolve => {
    const req=http.get({host:'127.0.0.1',port:backendPort,path:'/',timeout:1000},res => {res.resume(); resolve(res.statusCode < 500);});
    req.on('timeout',()=>req.destroy()); req.on('error',()=>resolve(false));
  });
}
setInterval(() => { if (fs.existsSync(controlPath) || process.ppid === 1) finish('Stopped'); },200).unref();
process.on('SIGINT',()=>finish('Stopped')); process.on('SIGTERM',()=>finish('Stopped')); process.on('SIGHUP',()=>finish('Stopped'));
process.on('uncaughtException',error=>finish('Failed',error.message));
status('Starting');
server.listen(config.port,'127.0.0.1',async () => {
  try {
    if (config.install) await install();
    if (ending) return;
    if (config.kind === 'Static') { status('Running',`http://127.0.0.1:${config.port}`); return; }
    status('Starting','Waiting for the development server…');
    backendPort=await unusedPort();
    const flags=['--host','127.0.0.1','--port',String(backendPort),'--strictPort'];
    // A custom command, else the framework's own (vite, next dev...), else the project's vite script.
    const command=(config.command||'').trim()||(config.launch||'').trim();
    // Project binaries first, like `npm run` does.
    const bins=[...new Set([config.cwd,config.installCwd].map(dir=>path.join(dir,'node_modules','.bin')))].join(path.delimiter);
    child=command
      ? launch(config.bash,['-c',command],config.cwd,{PORT:String(backendPort),HOST:'127.0.0.1',BROWSER:'none',PATH:bins+path.delimiter+(process.env.PATH||'')})
      : launch(config.manager,['run',config.script,...(config.manager==='npm'?['--']:[]),...flags],config.cwd,{BROWSER:'none'});
    child.once('error',error=>finish('Failed',error.message));
    child.once('exit',(code,signal)=> { if (!ending) finish('Failed',`Development server exited (${signal || code}). See terminal output.`); });
    // First compiles (Next.js on WebAssembly, Angular) can be slow on a phone.
    const deadline=Date.now()+240000;
    while (!ending && Date.now()<deadline) {
      if (await backendReady()) { if (!ending) status('Running',`http://127.0.0.1:${config.port}`); return; }
      await new Promise(resolve=>setTimeout(resolve,400));
    }
    if (!ending) finish('Failed','Server did not become ready within 4 minutes. Check terminal output.');
  } catch(error) { finish('Failed',error.message); }
});
