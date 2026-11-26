const e=`
fn erf_f32(v: f32) -> f32 {
  let a = abs(v);
  let x = 1.0 / (1.0 + 0.3275911 * a);
  let y = 1.0 - ((((1.061405429 * x - 1.453152027) * x + 1.421413741) * x - 0.284496736) * x + 0.254829592) * x * exp(-a * a);
  return select(-y, y, v >= 0.0);
}
fn gelu(v: f32) -> f32 { return 0.5 * v * (1.0 + erf_f32(v * 0.7071067811865476)); }
`,t=`
struct P { frames: u32, bins: u32 }
@group(0) @binding(0) var<storage, read> mel: array<f32>;      // [T, bins]
@group(0) @binding(1) var<storage, read> scale: array<f32>;
@group(0) @binding(2) var<storage, read> shift: array<f32>;
@group(0) @binding(3) var<storage, read_write> out: array<f32>; // [bins, T]
@group(0) @binding(4) var<uniform> p: P;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3u) {
  let i = id.x;
  if (i >= p.frames * p.bins) { return; }
  let f = i / p.frames;
  let t = i % p.frames;
  out[i] = mel[t * p.bins + f] * scale[f] + shift[f];
}
`,n=`
${e}
struct P { fout: u32, frames: u32, cin: u32, cout: u32, kf: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
@group(0) @binding(1) var<storage, read> w: array<f32>;   // [kf, 3, cin, cout] = [K, cout]
@group(0) @binding(2) var<storage, read> b: array<f32>;
@group(0) @binding(3) var<storage, read_write> y: array<f32>;
@group(0) @binding(4) var<uniform> p: P;
var<workgroup> as_: array<f32, 1024>; // [64 rows][16 k]
var<workgroup> bs_: array<f32, 1024>; // [16 k][64 cols]

fn gather(row: u32, k: u32) -> f32 {
  let ci = k % p.cin;
  let tap = k / p.cin;
  let dt = tap % 3u;
  let df = tap / 3u;
  let t = row % p.frames;
  let fo = row / p.frames;
  let ti = i32(t) + i32(dt) - 1;
  if (ti < 0 || ti >= i32(p.frames)) { return 0.0; }
  return x[((fo * p.kf + df) * p.frames + u32(ti)) * p.cin + ci];
}

@compute @workgroup_size(16, 16)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(local_invocation_id) l: vec3u, @builtin(local_invocation_index) li: u32) {
  let m = p.fout * p.frames;
  let kTotal = p.kf * 3u * p.cin;
  let row0 = wg.y * 64u;
  let col0 = wg.x * 64u;
  var acc: array<array<f32, 4>, 4>;
  for (var k0 = 0u; k0 < kTotal; k0 += 16u) {
    for (var i = 0u; i < 4u; i++) {
      let idx = li + i * 256u;
      let ar = row0 + idx / 16u;
      let ak = k0 + idx % 16u;
      var av = 0.0;
      if (ar < m && ak < kTotal) { av = gather(ar, ak); }
      as_[idx] = av;
      let bk = k0 + idx / 64u;
      let bc = col0 + idx % 64u;
      var bv = 0.0;
      if (bk < kTotal && bc < p.cout) { bv = w[bk * p.cout + bc]; }
      bs_[idx] = bv;
    }
    workgroupBarrier();
    for (var kk = 0u; kk < 16u; kk++) {
      var a: array<f32, 4>;
      var bb: array<f32, 4>;
      for (var i = 0u; i < 4u; i++) { a[i] = as_[(l.y * 4u + i) * 16u + kk]; }
      for (var j = 0u; j < 4u; j++) { bb[j] = bs_[kk * 64u + l.x * 4u + j]; }
      for (var i = 0u; i < 4u; i++) {
        for (var j = 0u; j < 4u; j++) { acc[i][j] += a[i] * bb[j]; }
      }
    }
    workgroupBarrier();
  }
  for (var i = 0u; i < 4u; i++) {
    let r = row0 + l.y * 4u + i;
    if (r >= m) { continue; }
    for (var j = 0u; j < 4u; j++) {
      let c = col0 + l.x * 4u + j;
      if (c >= p.cout) { continue; }
      y[r * p.cout + c] = gelu(acc[i][j] + b[c]);
    }
  }
}
`,r=(t,n=`f32`)=>{let r=n!==`f32`;return`
${r?`enable f16;`:``}
${e}
struct P { m: u32, n: u32, k: u32, flags: u32, posDiv: u32, posMod: u32, dmodel: u32, wStride: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
${t}
@group(0) @binding(2) var<storage, read> bias: array<f32>;
@group(0) @binding(3) var<storage, read_write> y: array<f32>;
@group(0) @binding(4) var<storage, read> table: array<f32>; // [pos][16][cos, sin]
@group(0) @binding(5) var<uniform> p: P;
${r?`var<workgroup> as_: array<vec4h, 256>; // [16 k][16 row quads]
var<workgroup> bs_: array<vec4h, 256>; // [16 k][16 col quads]
var<workgroup> sums_: array<vec4f, 256>; // per-loader row sums of squares
const A_SCALE = 64.0;`:`var<workgroup> as_: array<vec4f, 256>; // [16 k][16 row quads]; afterwards the per-loader row sums of squares
var<workgroup> bs_: array<vec4f, 256>; // [16 k][16 col quads]`}
@compute @workgroup_size(16, 16)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(local_invocation_id) l: vec3u, @builtin(local_invocation_index) li: u32) {
  let row0 = wg.y * 64u;
  let col0 = wg.x * 64u;
  var acc: array<vec4f, 4>; // acc[j] = rows 4*l.y..+3 of column 4*l.x + j
  // Loader roles: A - invocation li reads k = li % 16 of row quad li / 16; B - k = li / 16, col quad li % 16.
  let aK = li % 16u;
  let aQuad = li / 16u;
  let bK = li / 16u;
  let bQuad = li % 16u;
  var squares = vec4f(0.0);
  for (var k0 = 0u; k0 < p.k; k0 += 16u) {
    var av = vec4f(0.0);
    let ak = k0 + aK;
    if (ak < p.k) {
      for (var r = 0u; r < 4u; r++) {
        let row = row0 + aQuad * 4u + r;
        if (row < p.m) { av[r] = x[row * p.k + ak]; }
      }
    }
    squares = fma(av, av, squares);
    as_[aK * 16u + aQuad] = ${r?`vec4h(select(av, av / A_SCALE, (p.flags & 4u) != 0u))`:`av`};
    var bv = ${r?`vec4h`:`vec4f`}(0.0);
    let bk = k0 + bK;
    let bc = col0 + bQuad * 4u;
    if (bk < p.k && bc < p.n) { bv = weightQuad((bk * p.wStride + bc) / 4u); }
    bs_[bK * 16u + bQuad] = bv;
    workgroupBarrier();
${r?`${n===`math`?`    var part: array<vec4h, 4>;
`:``}    for (var kk = 0u; kk < 16u; kk++) {
      let a = as_[kk * 16u + l.y];
      let b = bs_[kk * 16u + l.x];
${n===`math`?`      part[0] = fma(a, vec4h(b.x), part[0]);
      part[1] = fma(a, vec4h(b.y), part[1]);
      part[2] = fma(a, vec4h(b.z), part[2]);
      part[3] = fma(a, vec4h(b.w), part[3]);
    }
    for (var j = 0u; j < 4u; j++) { acc[j] += vec4f(part[j]); }`:`      let af = vec4f(a);
      let bf = vec4f(b);
      acc[0] = fma(af, vec4f(bf.x), acc[0]);
      acc[1] = fma(af, vec4f(bf.y), acc[1]);
      acc[2] = fma(af, vec4f(bf.z), acc[2]);
      acc[3] = fma(af, vec4f(bf.w), acc[3]);
    }`}`:`    for (var kk = 0u; kk < 16u; kk++) {
      let a = as_[kk * 16u + l.y];
      let b = bs_[kk * 16u + l.x];
      acc[0] = fma(a, vec4f(b.x), acc[0]);
      acc[1] = fma(a, vec4f(b.y), acc[1]);
      acc[2] = fma(a, vec4f(b.z), acc[2]);
      acc[3] = fma(a, vec4f(b.w), acc[3]);
    }`}
    workgroupBarrier();
  }
  // The 16 loaders of row quad q are invocations 16q..16q+15; their partial sums meet in ${r?`sums_`:`as_`}.
  ${r?`sums_`:`as_`}[li] = squares;
  workgroupBarrier();
  var rowScale = vec4f(1.0);
  if ((p.flags & 4u) != 0u) {
    var total = vec4f(0.0);
    for (var t = 0u; t < 16u; t++) { total += ${r?`sums_`:`as_`}[l.y * 16u + t]; }
    rowScale = ${r?`A_SCALE`:`1.0`} / max(sqrt(total), vec4f(1e-12));
  }
  for (var i = 0u; i < 4u; i++) {
    let r = row0 + l.y * 4u + i;
    if (r >= p.m) { continue; }
    var v: array<f32, 4>;
    for (var j = 0u; j < 4u; j++) {
      let c = min(col0 + l.x * 4u + j, p.n - 1u);
      v[j] = acc[j][i] * rowScale[i] + bias[c];
    }
    let c0 = col0 + l.x * 4u;
    if ((p.flags & 8u) != 0u && c0 < 2u * p.dmodel) {
      let pos = (r / p.posDiv) % p.posMod;
      for (var pair = 0u; pair < 2u; pair++) {
        let t = (pos * 16u + ((c0 + 2u * pair) % 32u) / 2u) * 2u;
        let cs = table[t];
        let sn = table[t + 1u];
        let x0 = v[2u * pair];
        let x1 = v[2u * pair + 1u];
        v[2u * pair] = x0 * cs - x1 * sn;
        v[2u * pair + 1u] = x1 * cs + x0 * sn;
      }
    }
    for (var j = 0u; j < 4u; j++) {
      let c = c0 + j;
      if (c >= p.n) { continue; }
      var res = v[j];
      let o = r * p.n + c;
      if ((p.flags & 1u) != 0u) { res = gelu(res); }
      if ((p.flags & 2u) != 0u) { res += y[o]; }
      y[o] = res;
    }
  }
}
`},i=r(`
@group(0) @binding(1) var<storage, read> w: array<vec4f>;
fn weightQuad(i: u32) -> vec4f { return w[i]; }`),a=r(`
@group(0) @binding(1) var<storage, read> w: array<vec2u>; // four fp16 columns per element
fn weightQuad(i: u32) -> vec4f { let q = w[i]; return vec4f(unpack2x16float(q.x), unpack2x16float(q.y)); }`),o=`
@group(0) @binding(1) var<storage, read> w: array<vec4h>;
fn weightQuad(i: u32) -> vec4h { return w[i]; }`,s=r(o,`storage`),c=r(o,`math`),l=e=>`
${e?`enable f16;`:``}
struct P { n: u32, heads: u32, width: u32, dmodel: u32, seqStride: u32, batchStride: u32 }
@group(0) @binding(0) var<storage, read> qkv: array<f32>;
@group(0) @binding(1) var<storage, read_write> o: array<f32>; // [rows, dmodel]
@group(0) @binding(2) var<uniform> p: P;
const TILE = 32u;
var<workgroup> ks: array<${e?.k?`vec4h`:`vec4f`}, 256>; // [TILE keys][8 x vec4]
var<workgroup> vs: array<${e?`vec4h`:`vec4f`}, 256>;
@compute @workgroup_size(64)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(local_invocation_id) l: vec3u) {
  let b = wg.z;
  let h = wg.y;
  let qi = wg.x * 64u + l.x;
  let valid = qi < p.n;
  let qrow = b * p.batchStride + min(qi, p.n - 1u) * p.seqStride;
  var q: array<vec4f, 8>;
  var acc: array<vec4f, 8>;
  let qBase = qrow * p.width + h * 32u;
  for (var d = 0u; d < 8u; d++) {
    q[d] = vec4f(qkv[qBase + 4u * d], qkv[qBase + 4u * d + 1u], qkv[qBase + 4u * d + 2u], qkv[qBase + 4u * d + 3u]) * 0.17677669529663687;
    acc[d] = vec4f(0.0);
  }
  var m = -3.0e38;
  var sum = 0.0;
  for (var k0 = 0u; k0 < p.n; k0 += TILE) {
    // 32 keys x 8 vec4 = 256 vec4 each for K and V, four per invocation.
    for (var i = 0u; i < 4u; i++) {
      let idx = l.x + i * 64u;
      let kj = k0 + idx / 8u;
      var kv = vec4f(0.0);
      var vv = vec4f(0.0);
      if (kj < p.n) {
        let base = (b * p.batchStride + kj * p.seqStride) * p.width + h * 32u + (idx % 8u) * 4u;
        let kb = base + p.dmodel;
        let vb = base + 2u * p.dmodel;
        kv = vec4f(qkv[kb], qkv[kb + 1u], qkv[kb + 2u], qkv[kb + 3u]);
        vv = vec4f(qkv[vb], qkv[vb + 1u], qkv[vb + 2u], qkv[vb + 3u]);
      }
      ks[idx] = ${e?.k?`vec4h(kv)`:`kv`};
      vs[idx] = ${e?`vec4h(vv)`:`vv`};
    }
    workgroupBarrier();
    // Constant trip counts so the compiler unrolls and keeps s[] in registers; keys past the end
    // of the sequence get a score of -3e38, whose exp is exactly 0.
    var s: array<f32, 32>;
    var tileMax = -3.0e38;
    for (var j = 0u; j < TILE; j++) {
      var dotv = vec4f(0.0);
      for (var d = 0u; d < 8u; d++) { dotv = fma(q[d], ${e?.k?`vec4f(ks[j * 8u + d])`:`ks[j * 8u + d]`}, dotv); }
      s[j] = select(-3.0e38, dotv.x + dotv.y + dotv.z + dotv.w, k0 + j < p.n);
      tileMax = max(tileMax, s[j]);
    }
    let mNew = max(m, tileMax);
    let r = exp(m - mNew);
    sum *= r;
    for (var d = 0u; d < 8u; d++) { acc[d] *= r; }
    m = mNew;
${e?.pv?`    var part: array<vec4h, 8>;
    for (var j = 0u; j < TILE; j++) {
      let e = exp(s[j] - m);
      sum += e;
      let eh = vec4h(f16(e));
      for (var d = 0u; d < 8u; d++) { part[d] = fma(eh, vs[j * 8u + d], part[d]); }
    }
    for (var d = 0u; d < 8u; d++) { acc[d] += vec4f(part[d]); }`:`    for (var j = 0u; j < TILE; j++) {
      let e = exp(s[j] - m);
      sum += e;
      for (var d = 0u; d < 8u; d++) { acc[d] = fma(vec4f(e), ${e?`vec4f(vs[j * 8u + d])`:`vs[j * 8u + d]`}, acc[d]); }
    }`}
    workgroupBarrier();
  }
  if (!valid) { return; }
  let gate = 1.0 / (1.0 + exp(-qkv[qrow * p.width + 3u * p.dmodel + h]));
  let scale = gate / sum;
  let oBase = qrow * p.dmodel + h * 32u;
  for (var d = 0u; d < 8u; d++) {
    let v = acc[d] * scale;
    o[oBase + 4u * d] = v.x;
    o[oBase + 4u * d + 1u] = v.y;
    o[oBase + 4u * d + 2u] = v.z;
    o[oBase + 4u * d + 3u] = v.w;
  }
}
`,u=l(null),d=l({k:!0,pv:!1}),f=l({k:!0,pv:!0}),p=`
struct P { freqs: u32, frames: u32, channels: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
@group(0) @binding(1) var<storage, read_write> y: array<f32>;
@group(0) @binding(2) var<uniform> p: P;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3u) {
  let i = id.x;
  if (i >= p.freqs * p.frames * p.channels) { return; }
  let c = i % p.channels;
  let t = (i / p.channels) % p.frames;
  let f = i / (p.channels * p.frames);
  y[(t * p.freqs + f) * p.channels + c] = x[i];
}
`,m=`
struct P { frames: u32 }
@group(0) @binding(0) var<storage, read> l: array<f32>;
@group(0) @binding(1) var<storage, read_write> out: array<f32>;
@group(0) @binding(2) var<uniform> p: P;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3u) {
  let t = id.x;
  if (t >= p.frames) { return; }
  out[t] = l[t * 2u] + l[t * 2u + 1u];
  out[p.frames + t] = l[t * 2u + 1u];
}
`,h=1500,g=e=>e.reduce((e,t)=>e*t,1),_=/* @__PURE__ */ new Map([[t,`stem_bn`],[n,`conv`],[i,`matmul`],[a,`matmul`],[s,`matmul`],[c,`matmul`],[u,`attention`],[d,`attention`],[f,`attention`],[p,`flatten`],[m,`sum_head`]]);var v=class e{constructor(e,t,n,r,i){this.device=e,this.build=t,this.melBuffer=n,this.outBuffer=r,this.owned=i,this.fineStages=/* @__PURE__ */ new Set,this.autoRuns=0,this.graphs=/* @__PURE__ */ new Map}get bytes(){let e=this.owned.reduce((e,t)=>e+t.size,0);for(let t of this.graphs.values())e+=t.owned.reduce((e,t)=>e+t.size,0);return e}graph(e){let t=this.graphs.get(e);if(!t){for(let[e,t]of this.graphs)e!==1500&&(t.owned.forEach(e=>e.destroy()),this.graphs.delete(e));t=this.build(e),this.graphs.set(e,t)}return t.steps}static async create(r,o,l,v={}){if(v.f16&&!r.features.has(`shader-f16`))throw Error(`beat_this f16: device lacks shader-f16`);let y=h,b=[],x=(e,t=0)=>{let n=r.createBuffer({size:Math.max(16,e*4),usage:GPUBufferUsage.STORAGE|GPUBufferUsage.COPY_SRC|GPUBufferUsage.COPY_DST|t});return b.push(n),n},S=x(Math.ceil(o.byteLength/4));r.queue.writeBuffer(S,0,o.buffer,o.byteOffset,o.byteLength);let C=e=>{let t=l.tensors[e];if(!t)throw Error(`beat_this weights: missing tensor ${e}`);return t},w=e=>{let{byte_offset:t,shape:n,dtype:r,stride:i}=C(e),a=i?n[0]*i:g(n);return{buffer:S,offset:t,size:a*(r===`f16`?2:4)}},T=e=>l.tensors[e].shape,E=l.hyper_parameters.transformer_dim,D=l.hyper_parameters.stem_dim,O=l.hyper_parameters.ff_mult,k=32*y,A=e=>3*e+e/32,ee=x(y*128),te=x(y*128),j=[x(k*D),x(k*D)],ne=x(Math.max(k*A(D),y*A(E))),re=x(k*D),ie=x(Math.max(k*D*O,y*E*O)),ae=x(y*4*D*8),oe=x(y*2),se=x(y*2),ce=x(4096),M=o.byteOffset+C(`rope.freqs`).byte_offset,N=new Float32Array(o.buffer.slice(M,M+64)),le=new Float32Array(y*16*2);for(let e=0;e<y;e+=1)for(let t=0;t<16;t+=1){let n=Math.fround(Math.fround(e)*N[t]);le[(e*16+t)*2]=Math.cos(n),le[(e*16+t)*2+1]=Math.sin(n)}let ue=x(le.length);r.queue.writeBuffer(ue,0,le);let de=/* @__PURE__ */ new Map,fe=e=>{let t=de.get(e);return t||(t=r.createComputePipeline({layout:`auto`,compute:{module:r.createShaderModule({code:e}),entryPoint:`main`}}),de.set(e,t)),t},pe=new e(r,e=>{let o=[],h=e=>{let t=new Uint32Array(Math.ceil(e.length/4)*4);t.set(e);let n=r.createBuffer({size:t.byteLength,usage:GPUBufferUsage.UNIFORM|GPUBufferUsage.COPY_DST});return r.queue.writeBuffer(n,0,t),o.push(n),n},g=[],y=`stem`,b=(e,t,n,i,a=``)=>{let o=fe(e),s=[...t,{buffer:h(n)}].map((e,t)=>({binding:t,resource:e})),c=`${y} ${_.get(e)}${a?` ${a}`:``}`;g.push({kind:`dispatch`,label:c,pipeline:o,group:r.createBindGroup({layout:o.getBindGroupLayout(0),entries:s}),count:i})},x=e=>[Math.ceil(e/64),1,1],S=(e,t,n)=>g.push({kind:`stage`,name:e,buffer:t,floats:n}),O=(e,t,n,r,o,l,u={posDiv:1,posMod:1,dmodel:0})=>{let[d,f]=T(t),{dtype:p,stride:m}=C(t);if(!m)throw Error(`beat_this weights: ${t} has no row stride (re-export the weights)`);let h=v.f16===`math`?c:s;b(p===`f16`?v.f16?h:a:i,[{buffer:e},w(t),n?w(n):{buffer:ce},{buffer:r},{buffer:ue}],[o,f,d,l,u.posDiv,u.posMod,u.dmodel,m],[Math.ceil(f/64),Math.ceil(o/64),1],t.split(`.`).slice(-2).join(`.`))},k=(e,t,n,r,i)=>{let a=r/32,o=3*r+a;y=t,O(e,`${t}.attn.qkvg.w`,`${t}.attn.qkvg.b`,ne,n,12,{posDiv:i.posDiv,posMod:i.n,dmodel:r}),b(v.f16?v.f16===`math`?f:d:u,[{buffer:ne},{buffer:re}],[i.n,a,o,r,i.seqStride,i.batchStride],[Math.ceil(i.n/64),a,i.batches]),O(re,`${t}.attn.out.w`,null,e,n,2),O(e,`${t}.ff.w1`,`${t}.ff.b1`,ie,n,5),O(ie,`${t}.ff.w2`,`${t}.ff.b2`,e,n,2)};b(t,[{buffer:ee},w(`stem.bn.scale`),w(`stem.bn.shift`),{buffer:te}],[e,128],x(e*128));let A=32,M=D;b(n,[{buffer:te},w(`stem.conv.w`),w(`stem.conv.b`),{buffer:j[0]}],[A,e,1,M,4],[Math.ceil(M/64),Math.ceil(A*e/64),1]);let N=0;S(`stem`,j[N],A*e*M);for(let t=0;t<3;t+=1){let r=A*e;k(j[N],`block${t}.freq`,r,M,{n:A,seqStride:e,batchStride:1,batches:e,posDiv:e}),S(`block${t}_freq`,j[N],r*M),k(j[N],`block${t}.time`,r,M,{n:e,seqStride:1,batchStride:e,batches:A,posDiv:1}),S(`block${t}_time`,j[N],r*M);let i=1-N;y=`block${t}`,b(n,[{buffer:j[N]},w(`block${t}.conv.w`),w(`block${t}.conv.b`),{buffer:j[i]}],[A/2,e,M,M*2,2],[Math.ceil(M*2/64),Math.ceil(A/2*e/64),1]),N=i,A/=2,M*=2,S(`block${t}_conv`,j[N],A*e*M)}y=`frontend`,b(p,[{buffer:j[N]},{buffer:ae}],[A,e,M],x(A*e*M)),N=1-N,O(ae,`frontend.linear.w`,`frontend.linear.b`,j[N],e,0),S(`frontend`,j[N],e*E);for(let t=0;t<l.hyper_parameters.n_layers;t+=1)k(j[N],`layer${t}`,e,E,{n:e,seqStride:1,batchStride:e,batches:1,posDiv:1}),S(`layer${t}`,j[N],e*E);return y=`head`,O(j[N],`head.w`,`head.b`,oe,e,4),b(m,[{buffer:oe},{buffer:se}],[e],x(e)),S(`logits`,se,e*2),{steps:g,owned:o}},ee,se,b);return pe.graph(y),await r.queue.onSubmittedWorkDone(),pe}async run(e,t={}){let n=e.length/128;if(!Number.isInteger(n)||n<1||n>1500)throw Error(`beat_this chunk must be 1..${h} frames x 128, got ${e.length} values`);let r=this.graph(n),{device:i}=this;i.queue.writeBuffer(this.melBuffer,0,e.buffer,e.byteOffset,e.byteLength);let a=i.createCommandEncoder(),o=[],s=t.profile&&i.features.has(`timestamp-query`)?r.filter(e=>e.kind===`dispatch`):[],c=s.length?i.createQuerySet({type:`timestamp`,count:s.length*2}):null,l=0,u=null,d=0,f=0,p=t.slice??`none`,m=p===`auto`?1:Math.max(1,t.inFlight??1),g=[],_=async(e=!1)=>{u?.end(),u=null;let t=performance.now();for(i.queue.submit([a.finish()]),g.push(i.queue.onSubmittedWorkDone());g.length>(e?0:m-1);)await g.shift();let n=performance.now()-t;return d+=n,f+=1,a=i.createCommandEncoder(),n},v=t.budgetMs??8;p===`auto`&&++this.autoRuns%50==0&&this.fineStages.clear();let y=0,b=performance.now();for(let e of r){if((p===`dispatch`||p===`auto`&&this.fineStages.has(y))&&u&&(await _(),await t.between?.()),e.kind===`dispatch`){c&&(u?.end(),u=a.beginComputePass({timestampWrites:{querySet:c,beginningOfPassWriteIndex:l,endOfPassWriteIndex:l+1}}),l+=2),u??=a.beginComputePass(),u.setPipeline(e.pipeline),u.setBindGroup(0,e.group),u.dispatchWorkgroups(...e.count);continue}if((p===`stage`||p===`auto`&&!this.fineStages.has(y))&&e.name!==`logits`){let e=await _();p===`auto`&&e>v&&this.fineStages.add(y),await t.between?.()}if(y+=1,!t.stages&&e.name!==`logits`)continue;u?.end(),u=null;let n=i.createBuffer({size:e.floats*4,usage:GPUBufferUsage.MAP_READ|GPUBufferUsage.COPY_DST});a.copyBufferToBuffer(e.buffer,0,n,0,e.floats*4),o.push({name:e.name,buffer:n})}u?.end();let x=null;if(c){let e=i.createBuffer({size:l*8,usage:GPUBufferUsage.QUERY_RESOLVE|GPUBufferUsage.COPY_SRC});x=i.createBuffer({size:l*8,usage:GPUBufferUsage.MAP_READ|GPUBufferUsage.COPY_DST}),a.resolveQuerySet(c,0,l,e,0),a.copyBufferToBuffer(e,0,x,0,l*8)}await _(!0);let S=performance.now()-b,C={};for(let{name:e,buffer:t}of o)await t.mapAsync(GPUMapMode.READ),C[e]=new Float32Array(t.getMappedRange().slice(0)),t.destroy();let w;if(x){await x.mapAsync(GPUMapMode.READ);let e=new BigInt64Array(x.getMappedRange());w=s.map((t,n)=>({label:t.kind===`dispatch`?t.label:``,ms:Number(e[n*2+1]-e[n*2])/1e6})),x.destroy(),c?.destroy()}let T=C.logits;return{beat:T.subarray(0,n),downbeat:T.subarray(n,2*n),gpuMs:d,wallMs:S,slices:f,...t.stages?{stages:C}:{},...w?{profile:w}:{}}}destroy(){this.owned.forEach(e=>e.destroy()),this.graphs.forEach(e=>e.owned.forEach(e=>e.destroy()))}};const y=1024,b=/* @__PURE__ */ new WeakMap,x=async(e,t,n)=>{if(n<1||t.length<n*128)throw Error(`percussive: ${t.length} floats for ${n} frames`);let r=b.get(e);r||(r=await e.createComputePipelineAsync({layout:`auto`,compute:{module:e.createShaderModule({code:`
struct Params { frames: u32, first: u32, count: u32 }
@group(0) @binding(0) var<storage, read> mel: array<f32>;
@group(0) @binding(1) var<storage, read_write> energy: array<f32>;
@group(0) @binding(2) var<uniform> p: Params;

const BANDS: i32 = 128;
const HALF: i32 = 8;
const TAPS: i32 = 17;

fn magnitude(frame: i32, band: i32) -> f32 {
  if (frame < 0 || frame >= i32(p.frames) || band < 0 || band >= BANDS) { return 0.0; }
  let v = mel[u32(frame * BANDS + band)];
  // expm1 without its cancellation near 0.
  if (v < 1e-3) { return max(0.0, v + 0.5 * v * v); }
  return exp(v) - 1.0;
}

fn median(values: ptr<function, array<f32, 17>>) -> f32 {
  for (var i = 1; i < TAPS; i++) {
    let value = (*values)[i];
    var j = i - 1;
    while (j >= 0 && (*values)[j] > value) {
      (*values)[j + 1] = (*values)[j];
      j--;
    }
    (*values)[j + 1] = value;
  }
  return (*values)[HALF];
}

@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
  if (id.x >= p.count) { return; }
  let at = p.first + id.x;
  let frame = i32(at);
  var percussive = 0.0;
  var total = 0.0;
  var taps: array<f32, 17>;
  for (var band = 0; band < BANDS; band++) {
    for (var k = 0; k < TAPS; k++) { taps[k] = magnitude(frame + k - HALF, band); }
    let harmonic = median(&taps);
    for (var k = 0; k < TAPS; k++) { taps[k] = magnitude(frame, band + k - HALF); }
    let hit = median(&taps);
    let value = magnitude(frame, band);
    let e = value * value;
    let hh = harmonic * harmonic;
    let pp = hit * hit;
    if (hh + pp > 0.0) { percussive += e * pp / (hh + pp); }
    total += e;
  }
  energy[2u * at] = percussive;
  energy[2u * at + 1u] = total;
}
`}),entryPoint:`main`}}),b.set(e,r));let i=e.createBuffer({size:n*128*4,usage:GPUBufferUsage.STORAGE|GPUBufferUsage.COPY_DST}),a=e.createBuffer({size:n*2*4,usage:GPUBufferUsage.STORAGE|GPUBufferUsage.COPY_SRC}),o=Math.ceil(n/y),s=e.createBuffer({size:o*256,usage:GPUBufferUsage.UNIFORM|GPUBufferUsage.COPY_DST}),c=e.createBuffer({size:n*2*4,usage:GPUBufferUsage.MAP_READ|GPUBufferUsage.COPY_DST});try{e.queue.writeBuffer(i,0,t.buffer,t.byteOffset,n*128*4);for(let t=0;t<o;t+=1){let l=t*y,u=Math.min(y,n-l);e.queue.writeBuffer(s,t*256,new Uint32Array([n,l,u,0]));let d=e.createBindGroup({layout:r.getBindGroupLayout(0),entries:[{binding:0,resource:{buffer:i}},{binding:1,resource:{buffer:a}},{binding:2,resource:{buffer:s,offset:t*256,size:16}}]}),f=e.createCommandEncoder(),p=f.beginComputePass();p.setPipeline(r),p.setBindGroup(0,d),p.dispatchWorkgroups(Math.ceil(u/64)),p.end(),t===o-1&&f.copyBufferToBuffer(a,0,c,0,n*2*4),e.queue.submit([f.finish()]),await e.queue.onSubmittedWorkDone()}await c.mapAsync(GPUMapMode.READ);let l=new Float32Array(c.getMappedRange().slice(0));c.unmap();let u=new Float32Array(n),d=new Float32Array(n);for(let e=0;e<n;e+=1)u[e]=l[2*e],d[e]=l[2*e+1];return{percussive:u,total:d}}finally{i.destroy(),a.destroy(),s.destroy(),c.destroy()}},S=`
fn erf_f32(v: f32) -> f32 {
  let a = abs(v);
  let x = 1.0 / (1.0 + 0.3275911 * a);
  let y = 1.0 - ((((1.061405429 * x - 1.453152027) * x + 1.421413741) * x - 0.284496736) * x + 0.254829592) * x * exp(-a * a);
  return select(-y, y, v >= 0.0);
}
fn gelu(v: f32) -> f32 { return 0.5 * v * (1.0 + erf_f32(v * 0.7071067811865476)); }
`,C=e=>e?`
@group(0) @binding(1) var<storage, read> w: array<u32>;
fn wload(e: u32) -> f32 {
  let pair = unpack2x16float(w[e >> 1u]);
  return select(pair.x, pair.y, (e & 1u) == 1u);
}`:`
@group(0) @binding(1) var<storage, read> w: array<f32>;
fn wload(e: u32) -> f32 { return w[e]; }`,w=e=>`
var<workgroup> as_: array<${e===`f16`?`vec4h`:`vec4f`}, 256>;
var<workgroup> bs_: array<${e===`f16`?`vec4h`:`vec4f`}, 256>;`,T=(e,t)=>`${e===`f16`?`vec4h`:`vec4f`}(${t})`,E=e=>e===`f16`?`
    var part: array<vec4h, 4>;
    for (var kk = 0u; kk < 16u; kk++) {
      let a = as_[kk * 16u + l.y];
      let bb = bs_[kk * 16u + l.x];
      for (var i = 0u; i < 4u; i++) { part[i] = fma(vec4h(a[i]), bb, part[i]); }
    }
    for (var i = 0u; i < 4u; i++) { acc[i] += vec4f(part[i]); }`:`
    for (var kk = 0u; kk < 16u; kk++) {
      let a = as_[kk * 16u + l.y];
      let bb = bs_[kk * 16u + l.x];
      for (var i = 0u; i < 4u; i++) { acc[i] = fma(vec4f(a[i]), bb, acc[i]); }
    }`,D=e=>e===`f16`?`enable f16;`:``,O=(e,t)=>`
${w(t)}
@compute @workgroup_size(16, 16)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(local_invocation_id) l: vec3u, @builtin(local_invocation_index) li: u32) {
  let n = ${e};
  let row0 = wg.y * 64u;
  let col0 = wg.x * 64u;
  var acc: array<vec4f, 4>;
  for (var k0 = 0u; k0 < p.k; k0 += 16u) {
    // Invocation li: rows 4 (li / 16).. at k li % 16 of A, columns 4 (li % 16).. at k li / 16 of B.
    let ak = k0 + li % 16u;
    var av = vec4f(0.0);
    let bk = k0 + li / 16u;
    var bv = vec4f(0.0);
    for (var c = 0u; c < 4u; c++) {
      let ar = row0 + (li / 16u) * 4u + c;
      if (ar < p.m && ak < p.k) { av[c] = wload(ar * p.k + ak); }
      let bc = col0 + (li % 16u) * 4u + c;
      if (bk < p.k && bc < n) { bv[c] = gather(bk, bc); }
    }
    as_[(li % 16u) * 16u + li / 16u] = ${T(t,`av`)};
    bs_[li] = ${T(t,`bv`)};
    workgroupBarrier();${E(t)}
    workgroupBarrier();
  }
  for (var i = 0u; i < 4u; i++) {
    let r = row0 + l.y * 4u + i;
    if (r >= p.m) { continue; }
    for (var j = 0u; j < 4u; j++) {
      let c = col0 + l.x * 4u + j;
      if (c >= n) { continue; }
      store(r, c, acc[i][j]);
    }
  }
}
`,k=(e,t=`f32`)=>`
${D(t)}
${S}
struct P { m: u32, k: u32, cin: u32, kh: u32, kw: u32, sh: u32, sw: u32, ph: u32, pw: u32, dh: u32, dw: u32, hin: u32, win: u32, hout: u32, wout: u32, flags: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
${C(e)}
@group(0) @binding(2) var<storage, read> b: array<f32>;
@group(0) @binding(3) var<storage, read> side: array<f32>;
@group(0) @binding(4) var<storage, read_write> y: array<f32>;
@group(0) @binding(5) var<uniform> p: P;

fn gather(k: u32, n: u32) -> f32 {
  let taps = p.kh * p.kw;
  let ci = k / taps;
  let r = k - ci * taps;
  let a = r / p.kw;
  let c = r - a * p.kw;
  let ho = n / p.wout;
  let wo = n - ho * p.wout;
  let hi = i32(ho * p.sh + a * p.dh) - i32(p.ph);
  let wi = i32(wo * p.sw + c * p.dw) - i32(p.pw);
  if (hi < 0 || hi >= i32(p.hin) || wi < 0 || wi >= i32(p.win)) { return 0.0; }
  return x[(ci * p.hin + u32(hi)) * p.win + u32(wi)];
}

fn store(r: u32, c: u32, sum: f32) {
  var v = sum + b[r];
  if ((p.flags & 1u) != 0u) { v = gelu(v); }
  let o = r * p.hout * p.wout + c;
  if ((p.flags & 2u) != 0u) { y[o] = y[o] + side[r] * v; } else { y[o] = v; }
}
${O(`p.hout * p.wout`,t)}
`,A=(e,t=`f32`)=>`
${D(t)}
${S}
struct P { m: u32, k: u32, cin: u32, hin: u32, win: u32, axis: u32, flags: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
${C(e)}
@group(0) @binding(2) var<storage, read> b: array<f32>;
@group(0) @binding(3) var<storage, read_write> y: array<f32>;
@group(0) @binding(4) var<uniform> p: P;

fn gather(k: u32, n: u32) -> f32 {
  let ci = k / 2u;
  let t = k - ci * 2u;
  if (p.axis == 0u) {
    let q = n / p.win;
    let w = n - q * p.win;
    if (q < t || q - t >= p.hin) { return 0.0; }
    return x[(ci * p.hin + q - t) * p.win + w];
  }
  let h = n / (p.win + 1u);
  let q = n - h * (p.win + 1u);
  if (q < t || q - t >= p.win) { return 0.0; }
  return x[(ci * p.hin + h) * p.win + q - t];
}

fn store(r: u32, c: u32, sum: f32) {
  let co = r / 4u;
  let ph = r - co * 4u;
  var v = sum + b[co];
  if ((p.flags & 1u) != 0u) { v = gelu(v); }
  if (p.axis == 0u) {
    let q = c / p.win;
    let w = c - q * p.win;
    let o = 4u * q + ph;
    if (o < 2u || o - 2u >= 4u * p.hin) { return; }
    y[(co * 4u * p.hin + o - 2u) * p.win + w] = v;
  } else {
    let h = c / (p.win + 1u);
    let q = c - h * (p.win + 1u);
    let o = 4u * q + ph;
    if (o < 2u || o - 2u >= 4u * p.win) { return; }
    y[(co * p.hin + h) * 4u * p.win + o - 2u] = v;
  }
}
${O(`select(p.hin * (p.win + 1u), (p.hin + 1u) * p.win, p.axis == 0u)`,t)}
`,ee=`
struct P { parts: u32, c: u32, inner: u32, cs: u32, gs: u32, mode: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
@group(0) @binding(1) var<storage, read> stats: array<f32>;
@group(0) @binding(2) var<storage, read_write> partial: array<f32>;
@group(0) @binding(3) var<uniform> p: P;
var<workgroup> sums: array<f32, 256>;
@compute @workgroup_size(256)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(local_invocation_index) li: u32) {
  let g = wg.y;
  let n = p.c * p.inner;
  let chunk = (n + p.parts - 1u) / p.parts;
  let lo = wg.x * chunk;
  let hi = min(n, lo + chunk);
  let mean = stats[g * 4u];
  var s = 0.0;
  for (var e = lo + li; e < hi; e += 256u) {
    let ch = e / p.inner;
    let v = x[ch * p.cs + g * p.gs + e - ch * p.inner];
    if (p.mode == 0u) { s += v; } else { let d = v - mean; s = fma(d, d, s); }
  }
  sums[li] = s;
  workgroupBarrier();
  for (var span = 128u; span > 0u; span >>= 1u) {
    if (li < span) { sums[li] += sums[li + span]; }
    workgroupBarrier();
  }
  if (li == 0u) { partial[g * p.parts + wg.x] = sums[0]; }
}
`,te=`
struct P { parts: u32, n: u32, mode: u32 }
@group(0) @binding(0) var<storage, read> partial: array<f32>;
@group(0) @binding(1) var<storage, read_write> stats: array<f32>;
@group(0) @binding(2) var<uniform> p: P;
var<workgroup> sums: array<f32, 256>;
@compute @workgroup_size(256)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(local_invocation_index) li: u32) {
  let g = wg.x;
  var s = 0.0;
  for (var i = li; i < p.parts; i += 256u) { s += partial[g * p.parts + i]; }
  sums[li] = s;
  workgroupBarrier();
  for (var span = 128u; span > 0u; span >>= 1u) {
    if (li < span) { sums[li] += sums[li + span]; }
    workgroupBarrier();
  }
  if (li != 0u) { return; }
  let total = sums[0];
  let n = f32(p.n);
  if (p.mode == 0u) {
    stats[g * 4u] = total / n;
  } else if (p.mode == 1u) {
    stats[g * 4u + 1u] = 1.0 / sqrt(total / n + 1e-5);
  } else {
    let sd = sqrt(total / (n - 1.0));
    stats[g * 4u + 1u] = 1.0 / (sd + 1e-5);
    stats[g * 4u + 2u] = sd;
  }
}
`,j=`
${S}
struct P { c: u32, h: u32, w: u32, perH: u32, gelu: u32 }
@group(0) @binding(0) var<storage, read_write> x: array<f32>;
@group(0) @binding(1) var<storage, read> stats: array<f32>;
@group(0) @binding(2) var<storage, read> gamma: array<f32>;
@group(0) @binding(3) var<storage, read> beta: array<f32>;
@group(0) @binding(4) var<uniform> p: P;
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) id: vec3u, @builtin(num_workgroups) groups: vec3u) {
  let i = id.x + id.y * groups.x * 256u;
  let plane = p.h * p.w;
  if (i >= p.c * plane) { return; }
  let ch = i / plane;
  let g = select(0u, (i - ch * plane) / p.w, p.perH == 1u);
  var v = (x[i] - stats[g * 4u]) * stats[g * 4u + 1u] * gamma[ch] + beta[ch];
  if (p.gelu == 1u) { v = gelu(v); }
  x[i] = v;
}
`,ne=`
struct P { c: u32, h: u32, w: u32, perH: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
@group(0) @binding(1) var<storage, read> stats: array<f32>;
@group(0) @binding(2) var<storage, read> gamma: array<f32>;
@group(0) @binding(3) var<storage, read> beta: array<f32>;
@group(0) @binding(4) var<storage, read> scale: array<f32>;
@group(0) @binding(5) var<storage, read_write> res: array<f32>;
@group(0) @binding(6) var<uniform> p: P;
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) id: vec3u, @builtin(num_workgroups) groups: vec3u) {
  let i = id.x + id.y * groups.x * 256u;
  let plane = p.h * p.w;
  if (i >= p.c * plane) { return; }
  let ch = i / plane;
  let at = i - ch * plane;
  let g = select(0u, at / p.w, p.perH == 1u);
  let mean = stats[g * 4u];
  let rstd = stats[g * 4u + 1u];
  let a = (x[i] - mean) * rstd * gamma[ch] + beta[ch];
  let gch = ch + p.c;
  let gate = (x[gch * plane + at] - mean) * rstd * gamma[gch] + beta[gch];
  res[i] = res[i] + scale[ch] * (a / (1.0 + exp(-gate)));
}
`,re=`
struct P { c: u32, h: u32, w: u32, emb: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
@group(0) @binding(1) var<storage, read> emb: array<f32>;
@group(0) @binding(2) var<storage, read_write> y: array<f32>;
@group(0) @binding(3) var<uniform> p: P;
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) id: vec3u, @builtin(num_workgroups) groups: vec3u) {
  let i = id.x + id.y * groups.x * 256u;
  let plane = p.h * p.w;
  if (i >= p.c * plane) { return; }
  var v = x[i] / (1.0 + exp(-x[i + p.c * plane]));
  if (p.emb == 1u) {
    let ch = i / plane;
    v += emb[ch * p.h + (i - ch * plane) / p.w];
  }
  y[i] = v;
}
`,ie=`
struct P { n: u32 }
@group(0) @binding(0) var<storage, read_write> x: array<f32>;
@group(0) @binding(1) var<storage, read> y: array<f32>;
@group(0) @binding(2) var<uniform> p: P;
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) id: vec3u, @builtin(num_workgroups) groups: vec3u) {
  let i = id.x + id.y * groups.x * 256u;
  if (i < p.n) { x[i] = x[i] + y[i]; }
}
`,ae=`
struct P { n: u32 }
@group(0) @binding(0) var<storage, read_write> x: array<f32>;
@group(0) @binding(1) var<storage, read> stats: array<f32>;
@group(0) @binding(2) var<uniform> p: P;
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) id: vec3u, @builtin(num_workgroups) groups: vec3u) {
  let i = id.x + id.y * groups.x * 256u;
  if (i < p.n) { x[i] = (x[i] - stats[0]) * stats[1]; }
}
`,oe=`
struct P { c: u32, n: u32, pos: u32 }
@group(0) @binding(0) var<storage, read> x: array<f32>;
@group(0) @binding(1) var<storage, read> gamma: array<f32>;
@group(0) @binding(2) var<storage, read> beta: array<f32>;
@group(0) @binding(3) var<storage, read> pos: array<f32>;
@group(0) @binding(4) var<storage, read_write> y: array<f32>;
@group(0) @binding(5) var<uniform> p: P;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3u) {
  let t = id.x;
  if (t >= p.n) { return; }
  var s = 0.0;
  for (var ch = 0u; ch < p.c; ch++) { s += x[ch * p.n + t]; }
  let mean = s / f32(p.c);
  var q = 0.0;
  for (var ch = 0u; ch < p.c; ch++) { let d = x[ch * p.n + t] - mean; q = fma(d, d, q); }
  let rstd = 1.0 / sqrt(q / f32(p.c) + 1e-5);
  for (var ch = 0u; ch < p.c; ch++) {
    var v = (x[ch * p.n + t] - mean) * rstd * gamma[ch] + beta[ch];
    if (p.pos == 1u) { v += pos[ch * p.n + t]; }
    y[ch * p.n + t] = v;
  }
}
`,se=(e=`f32`)=>`
${D(e)}
struct P { nq: u32, nk: u32, qr0: u32, kr0: u32, head0: u32 }
@group(0) @binding(0) var<storage, read> q: array<f32>;
@group(0) @binding(1) var<storage, read> kv: array<f32>;
@group(0) @binding(2) var<storage, read_write> s: array<f32>;
@group(0) @binding(3) var<uniform> p: P;
${w(e)} // [16 d][16 query quads], [16 d][16 key quads]
@compute @workgroup_size(16, 16)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(local_invocation_id) l: vec3u, @builtin(local_invocation_index) li: u32) {
  let h = p.head0 + wg.z;
  let row0 = wg.y * 64u;
  let col0 = wg.x * 64u;
  var acc: array<vec4f, 4>;
  for (var d0 = 0u; d0 < 64u; d0 += 16u) {
    // Invocation li: queries and keys 4 (li % 16).. at dimension li / 16.
    let d = d0 + li / 16u;
    var av = vec4f(0.0);
    var bv = vec4f(0.0);
    for (var c = 0u; c < 4u; c++) {
      let e = (li % 16u) * 4u + c;
      let i = row0 + e;
      let j = col0 + e;
      av[c] = select(0.0, q[(p.qr0 + h * 64u + d) * p.nq + min(i, p.nq - 1u)], i < p.nq);
      bv[c] = select(0.0, kv[(p.kr0 + h * 64u + d) * p.nk + min(j, p.nk - 1u)], j < p.nk);
    }
    as_[li] = ${T(e,`av`)};
    bs_[li] = ${T(e,`bv`)};
    workgroupBarrier();${E(e)}
    workgroupBarrier();
  }
  for (var i = 0u; i < 4u; i++) {
    let r = row0 + l.y * 4u + i;
    if (r >= p.nq) { continue; }
    for (var j = 0u; j < 4u; j++) {
      let c = col0 + l.x * 4u + j;
      if (c < p.nk) { s[(wg.z * p.nq + r) * p.nk + c] = acc[i][j]; }
    }
  }
}
`,ce=`
struct P { nk: u32 }
@group(0) @binding(0) var<storage, read_write> s: array<f32>;
@group(0) @binding(1) var<uniform> p: P;
var<workgroup> red: array<f32, 256>;
@compute @workgroup_size(256)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(num_workgroups) groups: vec3u, @builtin(local_invocation_index) li: u32) {
  let base = (wg.x + wg.y * groups.x) * p.nk;
  var m = -3.0e38;
  for (var j = li; j < p.nk; j += 256u) { m = max(m, s[base + j]); }
  red[li] = m;
  workgroupBarrier();
  for (var span = 128u; span > 0u; span >>= 1u) {
    if (li < span) { red[li] = max(red[li], red[li + span]); }
    workgroupBarrier();
  }
  m = red[0];
  workgroupBarrier();
  var sum = 0.0;
  for (var j = li; j < p.nk; j += 256u) {
    let e = exp(s[base + j] - m);
    s[base + j] = e;
    sum += e;
  }
  red[li] = sum;
  workgroupBarrier();
  for (var span = 128u; span > 0u; span >>= 1u) {
    if (li < span) { red[li] += red[li + span]; }
    workgroupBarrier();
  }
  let inv = 1.0 / red[0];
  for (var j = li; j < p.nk; j += 256u) { s[base + j] *= inv; }
}
`,M=(e=`f32`)=>`
${D(e)}
struct P { nq: u32, nk: u32, vr0: u32, head0: u32 }
@group(0) @binding(0) var<storage, read> s: array<f32>;
@group(0) @binding(1) var<storage, read> kv: array<f32>;
@group(0) @binding(2) var<storage, read_write> o: array<f32>;
@group(0) @binding(3) var<uniform> p: P;
${w(e)} // [16 keys][16 query quads], [16 keys][16 d quads]
@compute @workgroup_size(16, 16)
fn main(@builtin(workgroup_id) wg: vec3u, @builtin(local_invocation_id) l: vec3u, @builtin(local_invocation_index) li: u32) {
  let h = p.head0 + wg.z;
  let row0 = wg.y * 64u;
  var acc: array<vec4f, 4>;
  for (var j0 = 0u; j0 < p.nk; j0 += 16u) {
    // Invocation li: queries and dimensions 4 (li / 16).. at key li % 16.
    let jj = li % 16u;
    let j = j0 + jj;
    var a = vec4f(0.0);
    var b = vec4f(0.0);
    for (var c = 0u; c < 4u; c++) {
      let r = (li / 16u) * 4u + c;
      let i = row0 + r;
      if (j < p.nk) {
        if (i < p.nq) { a[c] = s[(wg.z * p.nq + i) * p.nk + j]; }
        b[c] = kv[(p.vr0 + h * 64u + r) * p.nk + j];
      }
    }
    as_[jj * 16u + li / 16u] = ${T(e,`a`)};
    bs_[jj * 16u + li / 16u] = ${T(e,`b`)};
    workgroupBarrier();${E(e)}
    workgroupBarrier();
  }
  for (var i = 0u; i < 4u; i++) {
    let r = row0 + l.y * 4u + i;
    if (r >= p.nq) { continue; }
    for (var j = 0u; j < 4u; j++) {
      let d = l.x * 4u + j;
      o[(h * 64u + d) * p.nq + r] = acc[i][j];
    }
  }
}
`,N=4096,le=`
struct P { frames: u32, len: u32 }
@group(0) @binding(0) var<storage, read> mix: array<f32>;      // [2, len]
@group(0) @binding(1) var<storage, read> win: array<f32>;
@group(0) @binding(2) var<storage, read> tw: array<vec2f>;
@group(0) @binding(3) var<storage, read_write> y: array<vec2f>;  // [2 frames, 64 k1, 64 n2]
@group(0) @binding(4) var<uniform> p: P;
fn reflect(i: i32, n: i32) -> i32 {
  var j = select(i, -i, i < 0);
  return select(j, 2 * (n - 1) - j, j >= n);
}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3u) {
  let n2 = id.x;
  let k1 = id.y;
  let s = id.z;
  if (n2 >= 64u || k1 >= 64u || s >= 2u * p.frames) { return; }
  let ch = s / p.frames;
  let f = s - ch * p.frames;
  let padded = i32(p.len) + 3072;
  var acc = vec2f(0.0);
  for (var n1 = 0u; n1 < 64u; n1++) {
    let n = n1 * 64u + n2;
    let at = reflect(reflect(i32((f + 2u) * 1024u + n) - 2048, padded) - 1536, i32(p.len));
    let v = mix[ch * p.len + u32(at)] * win[n];
    let t = tw[((k1 * n1) % 64u) * 64u];
    acc += v * vec2f(t.x, -t.y);
  }
  let t = tw[k1 * n2];
  y[(s * 64u + k1) * 64u + n2] = vec2f(acc.x * t.x + acc.y * t.y, acc.y * t.x - acc.x * t.y);
}
`,ue=`
struct P { frames: u32 }
@group(0) @binding(0) var<storage, read> y: array<vec2f>;
@group(0) @binding(1) var<storage, read> tw: array<vec2f>;
@group(0) @binding(2) var<storage, read_write> spec: array<f32>;
@group(0) @binding(3) var<uniform> p: P;
@compute @workgroup_size(32)
fn main(@builtin(global_invocation_id) id: vec3u) {
  let k2 = id.x;
  let k1 = id.y;
  let s = id.z;
  if (k2 >= 32u || k1 >= 64u || s >= 2u * p.frames) { return; }
  var acc = vec2f(0.0);
  for (var n2 = 0u; n2 < 64u; n2++) {
    let v = y[(s * 64u + k1) * 64u + n2];
    let t = tw[((k2 * n2) % 64u) * 64u];
    acc += vec2f(v.x * t.x + v.y * t.y, v.y * t.x - v.x * t.y);
  }
  let ch = s / p.frames;
  let f = s - ch * p.frames;
  let k = k1 + 64u * k2;
  spec[((ch * 2u) * 2048u + k) * p.frames + f] = acc.x;
  spec[((ch * 2u + 1u) * 2048u + k) * p.frames + f] = acc.y;
}
`,de=`
struct P { frames: u32, source: u32 }
@group(0) @binding(0) var<storage, read> z: array<f32>;     // [16, 2048, frames]
@group(0) @binding(1) var<storage, read> stats: array<f32>;
@group(0) @binding(2) var<storage, read> tw: array<vec2f>;
@group(0) @binding(3) var<storage, read_write> y: array<vec2f>;
@group(0) @binding(4) var<uniform> p: P;
fn bin(ch: u32, f: u32, k: u32) -> vec2f {
  let kk = select(k, 4096u - k, k > 2048u);
  if (kk == 2048u) { return vec2f(0.0); }
  let base = (p.source * 4u + ch * 2u) * 2048u + kk;
  let re = z[base * p.frames + f] * stats[2] + stats[0];
  let im = z[(base + 2048u) * p.frames + f] * stats[2] + stats[0];
  return select(vec2f(re, im), vec2f(re, -im), k > 2048u);
}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3u) {
  let n2 = id.x;
  let k1 = id.y;
  let s = id.z;
  if (n2 >= 64u || k1 >= 64u || s >= 2u * p.frames) { return; }
  let ch = s / p.frames;
  let f = s - ch * p.frames;
  var acc = vec2f(0.0);
  for (var k2 = 0u; k2 < 64u; k2++) {
    let v = bin(ch, f, k1 + 64u * k2);
    let t = tw[((k2 * n2) % 64u) * 64u];
    acc += vec2f(v.x * t.x - v.y * t.y, v.x * t.y + v.y * t.x);
  }
  let t = tw[k1 * n2];
  y[(s * 64u + k1) * 64u + n2] = vec2f(acc.x * t.x - acc.y * t.y, acc.x * t.y + acc.y * t.x);
}
`,fe=`
struct P { frames: u32 }
@group(0) @binding(0) var<storage, read> y: array<vec2f>;
@group(0) @binding(1) var<storage, read> tw: array<vec2f>;
@group(0) @binding(2) var<storage, read> win: array<f32>;
@group(0) @binding(3) var<storage, read_write> out: array<f32>;  // [2 frames, 4096]
@group(0) @binding(4) var<uniform> p: P;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3u) {
  let n2 = id.x;
  let n1 = id.y;
  let s = id.z;
  if (n2 >= 64u || n1 >= 64u || s >= 2u * p.frames) { return; }
  var acc = 0.0;
  for (var k1 = 0u; k1 < 64u; k1++) {
    let v = y[(s * 64u + k1) * 64u + n2];
    let t = tw[((k1 * n1) % 64u) * 64u];
    acc += v.x * t.x - v.y * t.y;
  }
  let n = n1 * 64u + n2;
  out[s * 4096u + n] = acc / 4096.0 * win[n];
}
`,pe=`
struct P { frames: u32, len: u32, source: u32 }
@group(0) @binding(0) var<storage, read> fr: array<f32>;
@group(0) @binding(1) var<storage, read> env: array<f32>;
@group(0) @binding(2) var<storage, read> xt: array<f32>;      // [8, len]
@group(0) @binding(3) var<storage, read> tstats: array<f32>;
@group(0) @binding(4) var<storage, read_write> out: array<f32>; // [2, len]
@group(0) @binding(5) var<uniform> p: P;
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) id: vec3u, @builtin(num_workgroups) groups: vec3u) {
  let i = id.x + id.y * groups.x * 256u;
  if (i >= 2u * p.len) { return; }
  let ch = i / p.len;
  let j = i - ch * p.len;
  let pos = 3584u + j;
  var acc = 0.0;
  let last = pos / 1024u;
  for (var jj = select(0u, last - 3u, last >= 3u); jj <= last; jj++) {
    if (jj < 2u || jj >= p.frames + 2u) { continue; }
    let n = pos - jj * 1024u;
    if (n < 4096u) { acc += fr[(ch * p.frames + jj - 2u) * 4096u + n]; }
  }
  let t = xt[(p.source * 2u + ch) * p.len + j] * tstats[2] + tstats[0];
  out[i] = acc / env[1536u + j] + t;
}
`,me=172032,P=2048,he=e=>e.reduce((e,t)=>e*t,1);var F=class e{constructor(e,t,n,r,i){this.device=e,this.steps=t,this.mixBuffer=n,this.outBuffer=r,this.owned=i,this.fineStages=/* @__PURE__ */ new Set,this.autoRuns=0}static async create(t,n,r,i={}){if(r.segment!==172032)throw Error(`htdemucs weights are for a ${r.segment}-sample segment, not ${me}`);if(i.f16&&!t.features.has(`shader-f16`))throw Error(`htdemucs f16 needs a device opened with shader-f16`);let a=i.source??0,o=i.f16?`f16`:`f32`,s=se(o),c=M(o);t.pushErrorScope(`validation`);let l=me,u=[],d=e=>{let n=t.createBuffer({size:Math.max(16,Math.ceil(e)*4),usage:GPUBufferUsage.STORAGE|GPUBufferUsage.COPY_SRC|GPUBufferUsage.COPY_DST});return u.push(n),n},f=d(Math.ceil(n.byteLength/4));t.queue.writeBuffer(f,0,n.buffer,n.byteOffset,n.byteLength);let p=e=>{let t=r.tensors[e];if(!t)throw Error(`htdemucs weights: missing tensor ${e}`);return t},m=e=>{let{byte_offset:t,shape:n,dtype:r}=p(e),i=he(n)*(r===`f16`?2:4);return{buffer:f,offset:t,size:Math.ceil(i/4)*4}},h=e=>p(e).dtype===`f16`,g=e=>p(e).shape[0],_=new Float32Array(N*2);for(let e=0;e<N;e+=1)_[e*2]=Math.cos(2*Math.PI*e/N),_[e*2+1]=Math.sin(2*Math.PI*e/N);let v=d(_.length);t.queue.writeBuffer(v,0,_);let y=P/4*48*168,b=d(2*l),x=d(2*l),S=d(4),C=d(4),w=d(4*P),T=d(4096),E=d(64),D=d(2752512),O=d(4*P*168),F=d(y),I=d(y/8),L=d(2*y),R=[d(16*P*168),d(16*P*168)],ge=d(16*P*168),z=d(8*l),B=d(1376256),V=[],_e=[];for(let e=0;e<4;e+=1)V.push(d(48*2**e*(P/4**(e+1))*168)),_e.push(d(48*2**e*(l/4**(e+1))));let H=1344,U=d(512*H),W=d(344064),ve=d(512*H),ye=d(344064),be=d(512*H),xe=d(344064),Se=d(1536*H),Ce=d(1032192),we=d(688128),Te=d(1024*H),Ee=d(512*H),De=d(344064),Oe=d(2048*H),ke=d(4*H*H),Ae=/* @__PURE__ */ new Map,je=e=>{let n=Ae.get(e);return n||(n=t.createComputePipeline({layout:`auto`,compute:{module:t.createShaderModule({code:e}),entryPoint:`main`}}),Ae.set(e,n)),n},Me=e=>{let n=new Uint32Array(Math.ceil(e.length/4)*4);n.set(e);let r=t.createBuffer({size:n.byteLength,usage:GPUBufferUsage.UNIFORM|GPUBufferUsage.COPY_DST});return t.queue.writeBuffer(r,0,n),u.push(r),r},Ne=[],G=`input`,Pe=/* @__PURE__ */ new Map([[ie,`add`],[c,`attn_out`],[s,`attn_scores`],[ce,`softmax`],[re,`glu`],[ne,`gn_glu`],[j,`gn`],[de,`istft_a`],[fe,`istft_b`],[oe,`layer_norm`],[ae,`normalize`],[pe,`overlap_add`],[te,`stats_final`],[ee,`stats`],[le,`stft_a`],[ue,`stft_b`]]),K=(e,n,r,i,a=``)=>{let o=je(e),s=[...n,Me(r)].map((e,t)=>({binding:t,resource:e instanceof GPUBuffer?{buffer:e}:e})),c=`${G} ${Pe.get(e)??`gemm`}${a?` ${a}`:``}`;Ne.push({kind:`dispatch`,label:c,pipeline:o,group:t.createBindGroup({layout:o.getBindGroupLayout(0),entries:s}),count:i})},q=e=>{let t=Math.ceil(e/256),n=Math.min(t,32768);return[n,Math.ceil(t/n),1]},J=(e,t,n)=>Ne.push({kind:`stage`,name:e,buffer:t,floats:n}),Fe=(e,t,n,r,i,a,o,s)=>{let c=r*i,l=Math.max(1,Math.min(256,Math.floor(4096/n),Math.ceil(c/16384)));K(ee,[e,t,T],[l,r,i,a,o,0],[l,n,1]),K(te,[T,t],[l,c,0],[n,1,1]),K(ee,[e,t,T],[l,r,i,a,o,1],[l,n,1]),K(te,[T,t],[l,c,s],[n,1,1])},Ie=(e,t,n,r,i)=>{i?Fe(e,w,n,t,r,n*r,r,1):Fe(e,w,1,1,t*n*r,0,0,1)},Y=(e,t,n,r,i=0,a=E)=>{let{sh:s=1,sw:c=1,ph:l=0,pw:u=0,dh:d=1,dw:f=1}=r,_=g(`${t}.w`),v=p(`${t}.w`).shape[1],y=v/(r.kh*r.kw),b=Math.floor((r.hin+2*l-d*(r.kh-1)-1)/s)+1,x=Math.floor((r.win+2*u-f*(r.kw-1)-1)/c)+1;return K(k(h(`${t}.w`),o),[e,m(`${t}.w`),m(`${t}.b`),a,n],[_,v,y,r.kh,r.kw,s,c,l,u,d,f,r.hin,r.win,b,x,i],[Math.ceil(b*x/64),Math.ceil(_/64),1],t.split(`.`).slice(1).join(`.`)),[b,x]},Le=(e,t,n,r,i,a,s)=>{let c=g(`${t}.w`),l=p(`${t}.w`).shape[1],u=a===0?(r+1)*i:r*(i+1);K(A(h(`${t}.w`),o),[e,m(`${t}.w`),m(`${t}.b`),n],[c,l,l/2,r,i,a,s],[Math.ceil(u/64),Math.ceil(c/64),1],`tr`)},Re=(e,t,n,r,i)=>{for(let a=0;a<2;a+=1){let o=`${e}.dc${a}`,s=2**a,c=g(`${o}.c1.w`);Y(F,`${o}.c1`,I,{hin:n,win:r,kh:1,kw:3,pw:s,dw:s}),Ie(I,c,n,r,i),K(j,[I,w,m(`${o}.n1.g`),m(`${o}.n1.b`)],[c,n,r,+!!i,1],q(c*n*r)),Y(I,`${o}.c2`,L,{hin:n,win:r,kh:1,kw:1}),Ie(L,2*t,n,r,i),K(ne,[L,w,m(`${o}.n2.g`),m(`${o}.n2.b`),m(`${o}.ls`),F],[t,n,r,+!!i],q(t*n*r))}},ze=z;Fe(b,S,1,1,2*l,0,0,2),Ne.push({kind:`copy`,from:b,to:ze,bytes:2*l*4}),K(ae,[ze,S],[2*l],q(2*l)),K(le,[b,m(`stft.window`),v,D],[168,l],[1,64,336]),K(ue,[D,v,O],[168],[1,64,336]),Fe(O,C,1,1,4*P*168,0,0,2),K(ae,[O,C],[4*P*168],q(4*P*168)),J(`norm_spec`,O,4*P*168);let Be=ze,Ve=O,X=l,Z=P;for(let e=0;e<4;e+=1){let t=48*2**e;G=`tenc${e}`;let[,n]=Y(Be,`tenc${e}.conv`,F,{hin:1,win:X,kh:1,kw:8,sw:4,pw:2},1);X=n,Re(`tenc${e}`,t,1,X,!1),Y(F,`tenc${e}.rw`,L,{hin:1,win:X,kh:1,kw:1}),K(re,[L,E,_e[e]],[t,1,X,0],q(t*X)),J(`tenc${e}`,_e[e],t*X),Be=_e[e],G=`enc${e}`;let[r]=Y(Ve,`enc${e}.conv`,F,{hin:Z,win:168,kh:8,kw:1,sh:4,ph:2},1);Z=r,Re(`enc${e}`,t,Z,168,!0),Y(F,`enc${e}.rw`,L,{hin:Z,win:168,kh:1,kw:1}),K(re,[L,e===0?m(`freq_emb`):E,V[e]],[t,Z,168,+(e===0)],q(t*Z*168)),J(`enc${e}`,V[e],t*Z*168),Ve=V[e]}G=`tf_in`,Y(V[3],`up`,ve,{hin:1,win:H,kh:1,kw:1}),Y(_e[3],`up_t`,ye,{hin:1,win:672,kh:1,kw:1}),K(oe,[ve,m(`norm_in.g`),m(`norm_in.b`),m(`pos`),U],[512,H,1],[21,1,1]),K(oe,[ye,m(`norm_in_t.g`),m(`norm_in_t.b`),m(`pos_t`),W],[512,672,1],[11,1,1]),J(`tf_in`,U,512*H),J(`tf_in_t`,W,344064);let He=(e,t,n,r)=>K(oe,[e,m(`${t}.g`),m(`${t}.b`),E,n],[512,r,0],[Math.ceil(r/64),1,1]),Q=(e,t,n,r,i=0,a=E)=>Y(e,t,n,{hin:1,win:r,kh:1,kw:1},i,a),Ue=(e,t,n,r,i,a,o,l)=>{for(let u=0;u<8;u+=4){let d=`heads ${u}-${u+4-1}`;K(s,[e,t,ke],[r,i,a,o,u],[Math.ceil(i/64),Math.ceil(r/64),4],d);let f=4*r,p=Math.min(f,32768);K(ce,[ke],[i],[p,Math.ceil(f/p),1],d),K(c,[ke,t,n],[r,i,l,u],[1,Math.ceil(r/64),4],d)}},We=(e,t,n,r)=>{He(e,`${t}.nf`,r,n),Q(r,`${t}.ff1`,Oe,n,1),Q(Oe,`${t}.ff2`,e,n,2,m(`${t}.g2`)),Fe(e,w,1,1,512*n,0,0,1),K(j,[e,w,m(`${t}.no.g`),m(`${t}.no.b`)],[512,1,n,0,0],q(512*n))};for(let e=0;e<5;e+=1){let t=`tf${e}`,n=`tft${e}`;if(G=t,e%2==0)for(let[e,r,i,a,o,s]of[[U,t,H,ve,Se,Ee],[W,n,672,ye,Ce,De]])He(e,`${r}.n1`,a,i),Q(a,`${r}.qkv`,o,i),Ue(o,o,s,i,i,0,512,1024),Q(s,`${r}.out`,e,i,2,m(`${r}.g1`)),We(e,r,i,a);else He(U,`${t}.n1`,ve,H),Q(ve,`${t}.q`,Se,H),He(W,`${t}.nkv`,xe,672),Q(xe,`${t}.kv`,we,672),He(W,`${n}.n1`,ye,672),Q(ye,`${n}.q`,Ce,672),He(U,`${n}.nkv`,be,H),Q(be,`${n}.kv`,Te,H),Ue(Se,we,Ee,H,672,0,0,512),Ue(Ce,Te,De,672,H,0,0,512),Q(Ee,`${t}.out`,U,H,2,m(`${t}.g1`)),Q(De,`${n}.out`,W,672,2,m(`${n}.g1`)),We(U,t,H,ve),We(W,n,672,ye);J(`tf${e}`,U,512*H),J(`tft${e}`,W,344064)}let $=R[0];G=`dec0`,Q(U,`down`,$,H);for(let e=0;e<4;e+=1){let t=48*2**(3-e);G=`dec${e}`;let n=Z*168;K(ie,[$,V[3-e]],[t*n],q(t*n)),Y($,`dec${e}.rw`,L,{hin:Z,win:168,kh:3,kw:3,ph:1,pw:1}),K(re,[L,E,F],[t,Z,168,0],q(t*n)),Re(`dec${e}`,t,Z,168,!0);let r=e===3?ge:R[(e+1)%2];Le(F,`dec${e}.tr`,r,Z,168,0,e===3?0:1),Z*=4,J(`dec${e}`,r,g(`dec${e}.tr.w`)/4*Z*168),$=r}$=R[0],G=`tdec0`,Q(W,`down_t`,$,672);for(let e=0;e<4;e+=1){let t=48*2**(3-e);G=`tdec${e}`,K(ie,[$,_e[3-e]],[t*X],q(t*X)),Y($,`tdec${e}.rw`,L,{hin:1,win:X,kh:1,kw:3,pw:1}),K(re,[L,E,F],[t,1,X,0],q(t*X)),Re(`tdec${e}`,t,1,X,!1);let n=e===3?z:R[(e+1)%2];Le(F,`tdec${e}.tr`,n,1,X,1,e===3?0:1),X*=4,J(`tdec${e}`,n,g(`tdec${e}.tr.w`)/4*X),$=n}G=`output`,K(de,[ge,C,v,D],[168,a],[1,64,336]),K(fe,[D,v,m(`istft.window`),B],[168],[1,64,336]),K(pe,[B,m(`istft.env`),z,S,x],[168,l,a],q(2*l)),J(`out`,x,2*l);let Ge=await t.popErrorScope();if(Ge)throw u.forEach(e=>e.destroy()),Error(`htdemucs graph rejected: ${Ge.message}`);let Ke=new e(t,Ne,b,x,u);return await t.queue.onSubmittedWorkDone(),Ke}async run(e,t,n={}){let r=me;if(e.length!==r||t.length!==r)throw Error(`htdemucs segment must be ${r} samples per channel, got ${e.length} and ${t.length}`);let{device:i}=this;i.queue.writeBuffer(this.mixBuffer,0,e.buffer,e.byteOffset,e.byteLength),i.queue.writeBuffer(this.mixBuffer,r*4,t.buffer,t.byteOffset,t.byteLength);let a=i.createCommandEncoder(),o=null,s=[],c=0,l=0,u=n.slice??`none`,d=async()=>{o?.end(),o=null;let e=performance.now();i.queue.submit([a.finish()]),await i.queue.onSubmittedWorkDone();let t=performance.now()-e;return c+=t,l+=1,a=i.createCommandEncoder(),t},f=n.budgetMs??8,p=[],m=``;u===`auto`&&++this.autoRuns%50==0&&this.fineStages.clear();let h=0,g=performance.now();for(let e of this.steps){if((u===`dispatch`||u===`auto`&&this.fineStages.has(h))&&o){let e=await d();n.timings&&p.push({label:m,ms:e})}if(e.kind===`dispatch`){m=e.label,o??=a.beginComputePass(),o.setPipeline(e.pipeline),o.setBindGroup(0,e.group),o.dispatchWorkgroups(...e.count);continue}if(e.kind===`copy`){o?.end(),o=null,a.copyBufferToBuffer(e.from,0,e.to,0,e.bytes);continue}if((u===`stage`||u===`auto`&&!this.fineStages.has(h))&&e.name!==`out`){let e=await d();u===`auto`&&e>f&&this.fineStages.add(h)}if(h+=1,!n.stages&&e.name!==`out`)continue;o?.end(),o=null;let t=i.createBuffer({size:e.floats*4,usage:GPUBufferUsage.MAP_READ|GPUBufferUsage.COPY_DST});a.copyBufferToBuffer(e.buffer,0,t,0,e.floats*4),s.push({name:e.name,buffer:t})}await d();let _=performance.now()-g,v={};for(let{name:e,buffer:t}of s)await t.mapAsync(GPUMapMode.READ),v[e]=new Float32Array(t.getMappedRange().slice(0)),t.destroy();let y=v.out;return n.stages||delete v.out,{left:y.subarray(0,r),right:y.subarray(r,2*r),gpuMs:c,wallMs:_,slices:l,...n.stages?{stages:v}:{},...n.timings?{timings:p}:{}}}destroy(){this.owned.forEach(e=>e.destroy())}};const I=/* @__PURE__ */ new Map;let L=null,R=null,ge=``,z=!1,B=null;const V=async()=>{if(R)return R;if(!navigator.gpu)throw Error(`no WebGPU in this worker`);let e=await navigator.gpu.requestAdapter({powerPreference:`high-performance`});if(!e)throw Error(`no WebGPU adapter`);z=e.features.has(`shader-f16`);let t=await e.requestDevice({requiredFeatures:z?[`shader-f16`]:[]});return t.lost.then(e=>{B=`device lost (${e.reason}): ${e.message}`,I.clear(),L=null}),ge=`${e.info.vendor} ${e.info.architecture}${z?` f16`:``}`,R=t,t};self.onmessage=async e=>{let t=e.data;try{if(t.kind===`load`){let e;try{e=await V()}catch(e){self.postMessage({id:t.id,error:String(e?.message??e),scope:`device`});return}if(t.model===`htdemucs`){if(!L)try{L=await F.create(e,t.weights,t.manifest,{f16:z})}catch(e){self.postMessage({id:t.id,error:String(e?.message??e),scope:`model`});return}self.postMessage({id:t.id,result:ge});return}if(!I.has(t.model))try{I.set(t.model,await v.create(e,t.weights,t.manifest,{f16:z?`storage`:void 0}))}catch(e){self.postMessage({id:t.id,error:String(e?.message??e),scope:`model`});return}self.postMessage({id:t.id,result:ge});return}if(t.kind===`percussive`){let e;try{e=await V()}catch(e){self.postMessage({id:t.id,error:String(e?.message??e),scope:`device`});return}if(B)throw Error(B);let n=await x(e,t.mel,t.frames);self.postMessage({id:t.id,result:n},{transfer:[n.percussive.buffer,n.total.buffer]});return}if(t.kind===`separate`){if(B)throw Error(B);if(!L)throw Error(`htdemucs not loaded`);let e=await L.run(t.left,t.right,{slice:`auto`}),n=e.left.slice(),r=e.right.slice();self.postMessage({id:t.id,result:{left:n,right:r}},{transfer:[n.buffer,r.buffer]});return}if(t.kind===`dispose`){for(let e of I.values())e.destroy();I.clear(),L?.destroy(),L=null,R?.destroy(),self.postMessage({id:t.id,result:!0});return}let e=I.get(t.model),n=[],r=[];for(let i of t.chunks){if(B)throw Error(B);if(!e)throw Error(`${t.model} not loaded`);let a=await e.run(i,{slice:`auto`});n.push(a.beat.slice()),r.push(a.downbeat.slice())}let i;if(t.percussive&&R&&!B)try{i=await x(R,t.percussive.mel,t.percussive.frames)}catch(e){console.warn(`[beat-this-gpu] percussive energy failed: ${e?.message??e}`)}let a=[...n,...r,...i?[i.percussive,i.total]:[]];self.postMessage({id:t.id,result:{beat:n,downbeat:r,percussive:i}},{transfer:a.map(e=>e.buffer)})}catch(e){self.postMessage({id:t.id,error:String(e?.message??e)})}};