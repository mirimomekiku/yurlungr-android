// Launch plans are inspected without executing arbitrary framework code or downloading packages.
'use strict';
const {test,afterEach}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {buildLaunch,diagnostic,checkEngine,simpleScript,prepareCssCompatibility}=require('../app/src/main/assets/web-adapters.cjs');
const roots=[];
afterEach(()=>{for(const root of roots.splice(0))fs.rmSync(root,{recursive:true,force:true});});
function project(kind='Next',scripts={dev:'next dev'}) {
  const cwd=fs.mkdtempSync(path.join(os.tmpdir(),'yurlungr-adapter-'));roots.push(cwd);
  fs.writeFileSync(path.join(cwd,'package.json'),JSON.stringify({scripts}));
  return {cwd,kind,mode:'Script',profile:'Compatibility',script:'dev',node:process.env.YURLUNGR_TEST_NODE||process.execPath,manager:'npm',bash:'/bin/bash'};
}
function framework(config,name,version,bin,engines) {
  const dir=path.join(config.cwd,'node_modules',name);fs.mkdirSync(dir,{recursive:true});
  fs.writeFileSync(path.join(dir,'package.json'),JSON.stringify({name,version,bin,engines}));
}
test('simple scripts get routing flags while compound scripts preserve setup commands',async()=>{
  const c=project();const simple=await buildLaunch(c,12345);
  assert.deepEqual(simple.args,['run','dev','--','--hostname','127.0.0.1','--port','12345']);
  for(const script of ['prepare && next dev','next dev | tee log','PORT=3000 next dev','node start.js']) {
    fs.writeFileSync(path.join(c.cwd,'package.json'),JSON.stringify({scripts:{dev:script}}));
    const recipe=await buildLaunch(c,12345);assert.deepEqual(recipe.args,['run','dev']);
    assert.equal(recipe.env.PORT,'12345');assert.equal(recipe.env.HOST,'127.0.0.1');
  }
});
test('Next compatibility discovers installed CLI capabilities and uses Webpack',async()=>{
  const c={...project(),mode:'Framework'};framework(c,'next','16.4.5',{next:'dist/bin/next'});
  const recipe=await buildLaunch(c,12345,async(executable,args)=>{assert.equal(executable,c.node);assert.equal(args.at(-1),'--help');return '--hostname --port --webpack';});
  assert.deepEqual(recipe.args.slice(1),['dev','--webpack','--hostname','127.0.0.1','--port','12345']);
  await assert.rejects(buildLaunch(c,12345,async()=> '--hostname --port'),{code:'UNSUPPORTED_CLI'});
  const defaultBundler=await buildLaunch({...c,profile:'Default'},12345,async()=> '--hostname --port');
  assert.ok(!defaultBundler.args.includes('--webpack'));
});
test('CRA uses environment variables and never starts a server to probe help',async()=>{
  const c={...project('Cra'),mode:'Framework'};framework(c,'react-scripts','5.0.1',{'react-scripts':'bin/react-scripts.js'});
  const recipe=await buildLaunch(c,12345,()=>{throw Error('CRA --help starts a server');});
  assert.deepEqual(recipe.args.slice(1),['start']);assert.equal(recipe.env.PORT,'12345');assert.equal(recipe.env.BROWSER,'none');
});
test('framework-specific host options, strict ports and foreground modes',async()=>{
  for(const [kind,name,bin,command,extra] of [
    ['Vite','vite','vite',null,'--strictPort'],['Astro','astro','astro','dev','--no-background'],
    ['Nuxt','nuxt','nuxt','dev','--no-fork'],['Angular','@angular/cli','ng','serve',null],
    ['Parcel','parcel','parcel','serve',null],['Webpack','webpack-cli','webpack-cli','serve',null]]) {
    const c={...project(kind),mode:'Framework'};framework(c,name,'1.2.3',{[bin]:'cli.js'});
    const recipe=await buildLaunch(c,23456,async()=> '--host --port --strictPort --background --fork');
    assert.deepEqual(recipe.args.slice(command?2:1,command?6:5),['--host','127.0.0.1','--port','23456']);
    if(command)assert.equal(recipe.args[1],command);if(extra)assert.ok(recipe.args.includes(extra));
  }
});
test('missing dependencies and scripts report setup errors; custom commands remain unchanged',async()=>{
  const c=project();await assert.rejects(buildLaunch({...c,mode:'Framework'},12345),{code:'MISSING_DEPENDENCY'});
  await assert.rejects(buildLaunch({...c,script:'missing'},12345),{code:'MISSING_SCRIPT'});
  const command='prepare && node server.js --port "$PORT"';
  const recipe=await buildLaunch({...c,mode:'Custom',command},12345);assert.deepEqual(recipe.args,['-c',command]);
});
test('native compiler, missing dependency and environment errors have actionable categories',()=>{
  assert.equal(diagnostic('Failed to load SWC binary for android/arm64').state,'CompatibilityFailure');
  assert.equal(diagnostic('Turbopack is not supported by wasm bindings').recovery,'framework');
  assert.equal(diagnostic("Cannot find module '../lightningcss.android-arm64.node'").code,'CSS_BINDING');
  assert.equal(diagnostic("Cannot find module 'foo'").state,'NeedsSetup');
  assert.equal(diagnostic('DATABASE_URL environment variable is required').code,'MISSING_ENV');
  assert.equal(diagnostic('ordinary application error'),null);
});

test('Node engine ranges reject incompatible runtimes and build commands get no dev flags',()=>{
  const c=project();checkEngine('>=20 || ^18.19.0',c.cwd,c.node);
  assert.throws(()=>checkEngine('>=999.0.0',c.cwd,c.node),{code:'NODE_VERSION'});
  for(const command of ['vite build','vite preview','vite && setup'])assert.equal(simpleScript(command,'Vite'),false);
  for(const command of ['parcel build src/index.html','parcel watch index.html'])assert.equal(simpleScript(command,'Parcel'),false);
});

test('explicit Turbopack scripts explain the compatible launch without changing the manifest',async()=>{
  const c=project('Next',{dev:'next dev --turbopack'});
  const before=fs.readFileSync(path.join(c.cwd,'package.json'),'utf8');
  await assert.rejects(buildLaunch(c,12345),{code:'TURBOPACK_SCRIPT',recovery:'framework'});
  assert.equal(fs.readFileSync(path.join(c.cwd,'package.json'),'utf8'),before);
});
test('CSS fallback matches each missing binding version and leaves working native modules alone',async()=>{
  const c={...project(),compatibilityCache:path.join(roots[0],'cache')};
  framework(c,'lightningcss','1.30.1','index.js');
  const dir=path.join(c.cwd,'node_modules/lightningcss');
  fs.writeFileSync(path.join(dir,'package.json'),JSON.stringify({name:'lightningcss',version:'1.30.1',main:'index.js'}));
  fs.writeFileSync(path.join(dir,'index.js'),"throw Error(\"Cannot find module '../lightningcss.android-arm64.node'\")");
  let downloads=0;
  const install=async(cache,spec)=>{
    downloads++;assert.equal(spec,'lightningcss-wasm@1.30.1');
    const dest=path.join(cache,'node_modules/lightningcss-wasm');fs.mkdirSync(dest,{recursive:true});
    fs.writeFileSync(path.join(dest,'package.json'),JSON.stringify({name:'lightningcss-wasm',version:'1.30.1',main:'wasm-node.cjs'}));
    fs.writeFileSync(path.join(dest,'wasm-node.cjs'),'module.exports={transform:()=>({code:Buffer.from("compiled")})}');
  };
  const redirects=await prepareCssCompatibility(c,install,'android');
  const entry=path.join(dir,'index.js');assert.ok(redirects[entry].endsWith('wasm-node.cjs'));assert.equal(downloads,1);
  assert.deepEqual(await prepareCssCompatibility(c,install,'android'),redirects);assert.equal(downloads,1);
  assert.deepEqual(await prepareCssCompatibility({...c,profile:'Default'},install,'android'),{});
  assert.deepEqual(await prepareCssCompatibility(c,install,'linux'),{});
  const {spawnSync}=require('node:child_process');
  const preload=path.resolve(__dirname,'../app/src/main/assets/web-node-preload.cjs');
  const probe=spawnSync(c.node,['--require',preload,'-e',"console.log(require('lightningcss').transform({}).code.toString())"],
    {cwd:c.cwd,encoding:'utf8',env:{...process.env,YURLUNGR_CSS_REDIRECTS:JSON.stringify(redirects)}});
  assert.equal(probe.status,0,probe.stderr);assert.match(probe.stdout,/compiled/);
  fs.writeFileSync(path.join(dir,'index.js'),'module.exports={native:true}');
  assert.deepEqual(await prepareCssCompatibility(c,install,'android'),{});assert.equal(downloads,1);
});

test('Expo web keeps localhost and noninteractive launch options',async()=>{
  const c={...project('Expo'),mode:'Framework'};framework(c,'expo','54.0.0',{expo:'bin/cli'});
  const recipe=await buildLaunch(c,23456,async()=> '--web --localhost --port');
  assert.deepEqual(recipe.args.slice(1),['start','--web','--localhost','--port','23456']);
  assert.equal(recipe.env.CI,'1');assert.equal(recipe.env.HOST,'127.0.0.1');
});
