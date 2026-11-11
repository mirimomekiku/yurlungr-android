// Termux's dynamic linker can appear as execPath in forked Node workers.
// Repair only that known case, using the app's explicit Node executable.
if (process.platform === 'android' && /\/(?:linker|linker64)$/.test(process.execPath) && process.env.YURLUNGR_NODE_BINARY) {
  process.execPath = process.env.YURLUNGR_NODE_BINARY;
}

// Redirect only missing bindings discovered by this run's compatibility preflight.
if (process.env.YURLUNGR_CSS_REDIRECTS) {
  const Module = require('node:module');
  const {pathToFileURL,fileURLToPath} = require('node:url');
  const redirects = JSON.parse(process.env.YURLUNGR_CSS_REDIRECTS);
  if (typeof Module.registerHooks === 'function') {
    Module.registerHooks({resolve(specifier,context,nextResolve) {
      const result=nextResolve(specifier,context);
      if(result.url.startsWith('file:')) {
        const replacement=redirects[fileURLToPath(result.url)];
        if(replacement)return {url:pathToFileURL(replacement).href,format:'commonjs',shortCircuit:true};
      }
      return result;
    }});
  } else {
    const load=Module._load;
    Module._load=function(request,parent,isMain,...rest) {
      if(request==='lightningcss'||request.includes('/lightningcss/')) {
        const resolved=Module._resolveFilename(request,parent,isMain);
        if(redirects[resolved])return load.call(this,redirects[resolved],parent,isMain,...rest);
      }
      return load.call(this,request,parent,isMain,...rest);
    };
  }
}
