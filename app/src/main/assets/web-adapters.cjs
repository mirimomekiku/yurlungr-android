'use strict';
const fs = require('node:fs'), path = require('node:path'), {createRequire} = require('node:module');
const definitions = {
  Vite: {pkg:'vite',bin:'vite',args:[],host:'--host',strict:true},
  Next: {pkg:'next',bin:'next',args:['dev'],host:'--hostname'},
  Astro: {pkg:'astro',bin:'astro',args:['dev'],host:'--host'},
  Nuxt: {pkg:'nuxt',bin:'nuxt',args:['dev'],host:'--host'},
  Angular: {pkg:'@angular/cli',bin:'ng',args:['serve'],host:'--host'},
  Cra: {pkg:'react-scripts',bin:'react-scripts',args:['start'],envOnly:true},
  Parcel: {pkg:'parcel',bin:'parcel',args:['serve'],host:'--host'},
  Expo: {pkg:'expo',bin:'expo',args:['start','--web'],expo:true},
  Webpack: {pkg:'webpack-cli',bin:'webpack-cli',args:['serve'],host:'--host'},
};
function problem(code,message,recovery='setup') { return Object.assign(new Error(message),{code,recovery}); }
function packageInfo(cwd,name) {
  const from=createRequire(path.join(cwd,'package.json'));
  let manifest;
  try {manifest=from.resolve(name+'/package.json');} catch (_) {
    // Some frameworks hide package.json behind package exports.
    let current=path.dirname(from.resolve(name));
    while(current!==path.dirname(current)) {
      const file=path.join(current,'package.json');
      if(fs.existsSync(file)&&JSON.parse(fs.readFileSync(file)).name===name){manifest=file;break;}
      current=path.dirname(current);
    }
  }
  if(!manifest)throw problem('MISSING_DEPENDENCY',`Install ${name} using this project's package manager.`);
  const pkg=JSON.parse(fs.readFileSync(manifest,'utf8'));return {pkg,directory:path.dirname(manifest)};
}
function checkEngine(range,cwd,node) {
  if(!range)return;
  const from=createRequire(path.join(cwd,'package.json'));
  const choices=['semver',path.resolve(path.dirname(node),'../lib/node_modules/npm/node_modules/semver')];
  let semver;
  for(const name of choices){try{semver=from(name);break;}catch(_){}}
  if(semver) {
    if(!semver.satisfies(process.versions.node,range,{includePrerelease:true}))throw problem('NODE_VERSION',`Node ${range} is required; this runtime has ${process.versions.node}. Update the runtime explicitly before retrying.`);
  } else console.log(`[Yurlungr] Node requirement: ${range}; installed: ${process.versions.node}`);
}
function simpleScript(command,kind) {
  if(!command||/[;&|`$<>\n\r]/.test(command))return false;
  const patterns={Vite:/^vite(?=\s|$)(?!\s+(?:build|preview)(?:\s|$))/,Next:/^next\s+dev(?:\s|$)/,
    Astro:/^astro\s+dev(?:\s|$)/,Nuxt:/^(?:nuxt|nuxi)\s+dev(?:\s|$)/,Angular:/^ng\s+serve(?:\s|$)/,
    Expo:/^expo\s+start(?:\s|$)/,Cra:/^react-scripts\s+start(?:\s|$)/,Parcel:/^parcel(?=\s|$)(?!\s+(?:build|watch)(?:\s|$))/,Webpack:/^webpack\s+serve(?:\s|$)/};
  return !!patterns[kind]?.test(command.trim());
}
async function buildLaunch(config,port,capture) {
  const definition=definitions[config.kind];
  const manifestPath=path.join(config.cwd,'package.json');
  const manifest=fs.existsSync(manifestPath)?JSON.parse(fs.readFileSync(manifestPath,'utf8')):{};
  checkEngine(manifest.engines?.node,config.cwd,config.node);
  const env={...(config.kind==='Expo'?{CI:'1'}:{}),PORT:String(port),HOST:'127.0.0.1',HOSTNAME:'127.0.0.1',BROWSER:'none',
    NUXT_HOST:'127.0.0.1',NUXT_PORT:String(port),NITRO_HOST:'127.0.0.1',NITRO_PORT:String(port),
    NEXT_TELEMETRY_DISABLED:'1',ASTRO_TELEMETRY_DISABLED:'1',NUXT_TELEMETRY_DISABLED:'1'};
  if(config.mode==='Custom'||config.command?.trim())return {executable:config.bash,args:['-c',config.command],env};
  if(config.mode==='Script') {
    const command=manifest.scripts?.[config.script];
    if(typeof command!=='string')throw problem('MISSING_SCRIPT',`Script '${config.script}' no longer exists.`);
    if(config.kind==='Next'&&simpleScript(command,'Next')&&/(?:^|\s)--turbo(?:pack)?(?:\s|$|=)/.test(command))
      throw problem('TURBOPACK_SCRIPT','This script explicitly enables Turbopack, which cannot use the Android SWC fallback. Choose Framework CLI → Compatibility (Webpack). Project files were not changed.','framework');
    // Only append flags to a recognized simple launch, never compound shell commands.
    let flags=[];
    if(definition&&simpleScript(command,config.kind)) {
      flags=definition.envOnly?[]:definition.expo?['--localhost','--port',String(port)]:[definition.host,'127.0.0.1','--port',String(port),...(definition.strict?['--strictPort']:[])];
    }
    return {executable:config.manager,args:['run',config.script,...(flags.length&&config.manager==='npm'?['--']:[]),...flags],env};
  }
  if(!definition)throw problem('CUSTOM_REQUIRED','Choose Project script or enter a custom command.');
  let info;
  try {info=packageInfo(config.cwd,definition.pkg);} catch(error) {throw problem('MISSING_DEPENDENCY',`${definition.pkg} is unavailable. Install dependencies and retry.`);}
  checkEngine(info.pkg.engines?.node,config.cwd,config.node);
  const bin=typeof info.pkg.bin==='string'?info.pkg.bin:info.pkg.bin?.[definition.bin];
  if(!bin)throw problem('MISSING_CLI',`${definition.pkg} doesn't declare the expected command.`);
  const cli=path.resolve(info.directory,bin);
  const help=definition.envOnly?'':await capture(config.node,[cli,...definition.args,'--help']);
  const args=[cli,...definition.args];
  if(config.kind==='Next'&&config.profile==='Compatibility') {
    if(help.includes('--webpack'))args.push('--webpack');
    else if(Number(info.pkg.version.split('.')[0])>=16)throw problem('UNSUPPORTED_CLI','This Next.js version does not expose a Webpack compatibility option. Try Default bundler or Project script.');
  }
  if(!definition.envOnly) {
    if(!help.includes(definition.expo?'--localhost':definition.host)||!help.includes('--port'))throw problem('UNSUPPORTED_CLI',`${info.pkg.name} ${info.pkg.version} has unfamiliar launch options. Use Project script or Custom command.`);
    if(definition.expo)args.push('--localhost','--port',String(port));
    else args.push(definition.host,'127.0.0.1','--port',String(port));
    if(definition.strict&&help.includes('--strictPort'))args.push('--strictPort');
  }
  // Avoid framework auto-detaching when launchers detect agent-like environments.
  if(config.kind==='Astro'&&help.includes('--background'))args.push('--no-background');
  if(config.kind==='Nuxt'&&help.includes('--fork'))args.push('--no-fork');
  console.log(`[Yurlungr] ${info.pkg.name} ${info.pkg.version} · ${config.profile||'Default'} · Framework CLI`);
  return {executable:config.node,args,env};
}
function diagnostic(text) {
  if(/(?:cannot find module|failed to load).{0,100}lightningcss[.\/-]|lightningcss.*(?:native binding|not found)/is.test(text))
    return {code:'CSS_BINDING',state:'CompatibilityFailure',message:'Lightning CSS is missing its Android binding. Compatibility mode can use the matching WASM CSS compiler.',recovery:'cssCompiler'};
  if(/turbopack.*(?:wasm|not supported|unsupported)|wasm.*turbopack/is.test(text))
    return {code:'TURBOPACK',state:'CompatibilityFailure',message:'Turbopack cannot use this runtime’s WASM compiler. Choose Framework CLI → Compatibility (Webpack).',recovery:'framework'};
  if(/(?:swc|turbopack|native bind|native module|lightningcss|rolldown).*(?:fail|unsupported|not supported|not available|not found|cannot)|(?:fail|cannot).*(?:swc|turbopack|native bind)|wasm bindings.*(?:support|turbopack)/is.test(text))
    return {code:'NATIVE_COMPILER',state:'CompatibilityFailure',message:'A native compiler or binding is unavailable for this runtime. Try the compatibility profile or inspect Logs.',recovery:'compatibility'};
  if(/cannot find (?:module|package)|ERR_MODULE_NOT_FOUND|command not found/i.test(text))
    return {code:'MISSING_DEPENDENCY',state:'NeedsSetup',message:'A required package or command is missing. Install dependencies and retry.',recovery:'setup'};
  if(/(?:missing|required|not set|undefined).{0,60}(?:environment variable|DATABASE_URL|API_KEY)|(?:environment variable|DATABASE_URL).{0,60}(?:missing|required|not set)/i.test(text))
    return {code:'MISSING_ENV',state:'NeedsSetup',message:'This application needs environment configuration. Check its README and Logs.',recovery:'setup'};
  return null;
}
async function installNextCompiler(config) {
  const info=packageInfo(config.cwd,'next');
  if(!/^\d+\.\d+\.\d+(?:-[\w.-]+)?$/.test(info.pkg.version))throw problem('UNSUPPORTED_VERSION','Cannot determine an exact Next.js compiler version.');
  const downloader=require(path.join(info.directory,'dist/lib/download-swc.js'));
  if(typeof downloader.downloadWasmSwc!=='function')throw problem('UNSUPPORTED_VERSION','This Next.js version has no compatible compiler downloader. Check Logs for manual setup.');
  console.log(`[Yurlungr] Downloading Next.js ${info.pkg.version}'s matching WASM compiler into its generated cache.`);
  await downloader.downloadWasmSwc(info.pkg.version,path.join(info.directory,'wasm'));
}
// Only known missing native CSS bindings get redirected; project packages stay untouched.
async function prepareCssCompatibility(config,installPackage,platform=process.platform) {
  if(platform!=='android'||config.profile!=='Compatibility')return {};
  const roots=new Set([config.cwd]);
  for(const name of ['@tailwindcss/postcss','@tailwindcss/node']) {
    for(const root of [...roots]) {try{roots.add(packageInfo(root,name).directory);}catch(_){}}
  }
  const redirects={};
  for(const root of roots) {
    let info,entry;
    try{info=packageInfo(root,'lightningcss');entry=createRequire(path.join(root,'package.json')).resolve('lightningcss');}catch(_){continue;}
    if(redirects[entry])continue;
    try{require(entry);continue;}catch(error){
      if(!/lightningcss[.\/-]|native binding/i.test(error.message))throw error;
    }
    const version=info.pkg.version;
    if(!/^\d+\.\d+\.\d+(?:-[\w.-]+)?$/.test(version))throw problem('UNSUPPORTED_VERSION','Cannot determine the matching Lightning CSS compiler version.');
    const cache=path.join(config.compatibilityCache,'lightningcss',version);
    const fromCache=createRequire(path.join(cache,'package.json'));
    let wasm;
    try{wasm=fromCache.resolve('lightningcss-wasm');}catch(_){
      console.log(`[Yurlungr] Lightning CSS ${version} is missing its Android binding. Installing the matching official WASM package in the app cache.`);
      await installPackage(cache,`lightningcss-wasm@${version}`);
      wasm=fromCache.resolve('lightningcss-wasm');
    }
    const wasmInfo=packageInfo(cache,'lightningcss-wasm');
    if(wasmInfo.pkg.version!==version)throw problem('CSS_VERSION','The cached Lightning CSS fallback has the wrong version. Retry compatibility setup.');
    if(!wasm.endsWith('.cjs'))throw problem('CSS_VERSION','This Lightning CSS version has no synchronous Node WASM entry. Check Logs for setup.');
    redirects[entry]=wasm;
    console.log(`[Yurlungr] Lightning CSS ${version} → matching WASM fallback`);
  }
  return redirects;
}
module.exports={problem,definitions,buildLaunch,simpleScript,diagnostic,installNextCompiler,checkEngine,prepareCssCompatibility};
