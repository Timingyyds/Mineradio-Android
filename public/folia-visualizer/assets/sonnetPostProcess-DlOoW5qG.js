import{t as e}from"./pixiFilterVertex-Dv50nXV8.js";var t=e=>Math.min(1,Math.max(0,e)),n=(e,t,n)=>{let r=1-n;return 3*r*r*n*e+3*r*n*n*t+n*n*n},r=(e,r,i,a,o)=>{let s=t(o);if(s===0||s===1)return s;let c=0,l=1,u=s;for(let t=0;t<12;t+=1)n(e,i,u)<s?c=u:l=u,u=(c+l)/2;return n(r,a,u)},i=e=>r(.65,0,.35,1,e),a=e=>e.animationIntensity===`calm`?.65:e.animationIntensity===`chaotic`?1.35:1,o=e=>e===1?1:1-2**(-10*e),s=e=>{let t=.3;return 2**(-10*e)*Math.sin((e-t/4)*(2*Math.PI)/t)+1},c=(e,n)=>t((n-e.startTime)/Math.max(e.endTime-e.startTime,.001)),l=(e,n,r)=>o(t((r-e)/Math.max(n-e,.08))),u=(e,t=Math.random)=>e===`decoration`?t()>.5?.5+t()*.8:-.5-t()*.8:0,d=(e,t,n,r,i)=>{if(e!==`support`)return{x:0,y:0};let a=(Math.min(1,Math.max(0,i))*2-1)*r*.3,o=n+(t===`vertical`?0:Math.PI/2);return{x:Math.cos(o)*a,y:Math.sin(o)*a}},f=[{offset:-1,weight:1},{offset:-.5,weight:4},{offset:0,weight:6},{offset:.5,weight:4},{offset:1,weight:1}],p=(e,t,n,r,i=.12,a=96)=>{let o=Math.min(t,n),s=Math.max(t,n),c=Math.max(0,i);if(c===0||o===s)return r(Math.min(s,Math.max(o,e)));let l=f.map(({offset:t,weight:n})=>({point:r(Math.min(s,Math.max(o,e+t*c))),weight:n})),u=l[2].point,d=Math.max(0,a)**2,p=0,m=0,h=0;return l.forEach(({point:e,weight:t})=>{(e.x-u.x)**2+(e.y-u.y)**2>d||(p+=e.x*t,m+=e.y*t,h+=t)}),{x:p/h,y:m/h}},m=(e,t,n=.35)=>{if(e.length===0)return[];let r=Math.max(.001,n),i=e.map(e=>{let n=Math.min(e.startTime,e.endTime),i=Math.max(e.startTime,e.endTime),a=t<n?n-t:t>i?t-i:0;return-(a*a)/(2*r*r)}),a=Math.max(...i),o=i.map(e=>Math.exp(e-a)),s=o.reduce((e,t)=>e+t,0);return o.map(e=>e/s)},h=(e,n)=>{let r=t(n);if(e===`tracking-ribbon`||e===`fragment-collage`||e===`quiet-tableau`||e===`poster-blocks`)return r*.55+i(r)*.45;if(r<.18)return o(r/.18)*.22;if(r<.78)return .22+(r-.18)/.6*.56;let a=(r-.78)/.22;return .78+(1-(1-a)*(1-a))*.22},g=(e,n)=>{let r=t(n),i=h(e,r);return{"editorial-column":{x:-.055+i*.095,y:.025-i*.04,scale:.98+i*.07,rotation:-.006+i*.01},"type-impact":{x:-.035+i*.07,y:.018-i*.028,scale:1+(1-o(Math.min(r/.18,1)))*.22+i*.08,rotation:-.01+i*.016},"fragment-collage":{x:-.045+i*.085,y:.028-Math.sin(i*Math.PI)*.055,scale:.97+i*.09,rotation:-.014+i*.028},"tracking-ribbon":{x:-.16+i*.28,y:.05-i*.085,scale:.98+i*.07,rotation:.008-i*.014},"mask-reveal":{x:.035-i*.065,y:.1-i*.135,scale:.96+i*.12,rotation:-.006+i*.009},"poster-blocks":{x:-.012+i*.024,y:.008-i*.016,scale:.99+i*.025,rotation:-.0015+i*.003},"quiet-tableau":{x:-.022+i*.04,y:.014-i*.025,scale:1+i*.028,rotation:-.002+i*.003}}[e]},_=.006,v=.002,y=.0015,b=(e,t=0)=>{let n=e*Math.PI*2;return{x:(Math.sin(n*.13+t)*.65+Math.sin(n*.31+t*1.7)*.35)*_,y:(Math.cos(n*.11+t*2.3)*.65+Math.sin(n*.29+t*.9)*.35)*_,scale:Math.sin(n*.09+t*1.3)*v,rotation:Math.sin(n*.07+t*2.9)*y}},x=(e,n,r=1.2)=>r<=0?+(e>=n):i(t((e-n)/r)),S=(e,t)=>{if(t<=0)return{x:0,y:0,rotation:0};let n=Math.sin(e*123.456)*Math.cos(e*789.123),r=Math.cos(e*345.678)*Math.sin(e*901.234),i=Math.sin(e*567.89);return{x:n*.02*t,y:r*.02*t,rotation:i*.005*t}},C=`
in vec2 vTextureCoord;
out vec4 finalColor;

uniform sampler2D uTexture;
uniform highp vec4 uInputSize;
uniform vec4 uInputClamp;
uniform highp vec4 uOutputFrame;
uniform float uDistortion;
uniform float uDispersion;

vec2 screenToTextureUv(vec2 screenUv) {
    return screenUv * uOutputFrame.zw * uInputSize.zw;
}

vec4 sampleInside(vec2 uv) {
    if (uv.x < uInputClamp.x || uv.y < uInputClamp.y
        || uv.x > uInputClamp.z || uv.y > uInputClamp.w) {
        return vec4(0.0);
    }
    return texture(uTexture, uv);
}

void main(void) {
    vec2 screenUv = vTextureCoord * uInputSize.xy / max(uOutputFrame.zw, vec2(1.0));
    vec2 centered = screenUv - 0.5;
    float aspect = uOutputFrame.z / max(uOutputFrame.w, 1.0);
    centered.x *= aspect;

    float radiusSquared = dot(centered, centered);
    // Keep the low end subtle but leave enough headroom for the broad barrel warp in the
    // reference look; the UI/store expose this as a 0..2 amount.
    float curvature = uDistortion * 0.32;
    float radialScale = 1.0 - curvature * radiusSquared
        + curvature * 0.16 * radiusSquared * radiusSquared;
    vec2 lensCentered = centered * radialScale;
    lensCentered.x /= aspect;
    vec2 lensUv = lensCentered + 0.5;

    float radius = sqrt(radiusSquared);
    vec2 radialDirection = radius > 0.0001 ? centered / radius : vec2(0.0);
    float edgeWeight = smoothstep(0.12, 0.9, radius);
    vec2 dispersion = radialDirection * uDispersion * 0.012 * edgeWeight;
    dispersion.x /= aspect;

    vec4 center = sampleInside(screenToTextureUv(lensUv));
    vec4 redSample = sampleInside(screenToTextureUv(lensUv + dispersion));
    vec4 blueSample = sampleInside(screenToTextureUv(lensUv - dispersion));
    float alpha = max(center.a, max(redSample.a, blueSample.a));
    float coreWeight = 0.84 - clamp(uDispersion, 0.0, 1.0) * 0.18;
    vec3 core = center.rgb * coreWeight;
    vec3 separated = vec3(redSample.r, center.g, blueSample.b);
    // Keep a neutral core for thin MG strokes, then add the displaced channels as colored
    // fringes. Without this fallback, a one-pixel line can sample transparent red/blue texels
    // and become an unintended green-only stroke.
    vec3 color = max(core, separated);

    // Pixi render textures are premultiplied; the max channel values remain premultiplied.
    finalColor = vec4(color, alpha);
}
`,w=(t,n)=>{let r=new t.UniformGroup({uDistortion:{value:n.distortion,type:`f32`},uDispersion:{value:n.dispersion,type:`f32`}});return new t.Filter({glProgram:t.GlProgram.from({vertex:e,fragment:C,name:`sonnet-lens-distortion`}),resources:{lensUniforms:r},antialias:`on`})},T=`
in vec2 vTextureCoord;
out vec4 finalColor;

uniform sampler2D uTexture;
uniform highp vec4 uInputSize;
uniform vec4 uInputClamp;
uniform float uAmount;

vec4 sampleInside(vec2 uv) {
    if (uv.x < uInputClamp.x || uv.y < uInputClamp.y
        || uv.x > uInputClamp.z || uv.y > uInputClamp.w) {
        return vec4(0.0);
    }
    return texture(uTexture, uv);
}

void main(void) {
    vec2 offset = vec2(0.9063, 0.4226) * uAmount * 3.0 * uInputSize.zw;
    vec4 redSample = sampleInside(vTextureCoord + offset);
    vec4 center = sampleInside(vTextureCoord);
    vec4 blueSample = sampleInside(vTextureCoord - offset);
    float alpha = max(center.a, max(redSample.a, blueSample.a));
    float coreWeight = 0.84 - clamp(uAmount, 0.0, 1.0) * 0.18;
    vec3 core = center.rgb * coreWeight;
    vec3 separated = vec3(redSample.r, center.g, blueSample.b);
    // Preserve a neutral core for thin strokes so transparent red/blue offset samples cannot
    // collapse a white MG line into an unintended green-only result.
    finalColor = vec4(max(core, separated), alpha);
}
`,E=`
in vec2 vTextureCoord;
out vec4 finalColor;

uniform sampler2D uTexture;
uniform float uAmount;

float dotScreen(vec2 fragCoord, float angle, float value, float cellSize) {
    float c = cos(angle);
    float s = sin(angle);
    vec2 rotated = mat2(c, s, -s, c) * fragCoord;
    float dist = length(fract(rotated / cellSize) - 0.5) * cellSize;
    float radius = sqrt(clamp(value, 0.0, 1.0)) * cellSize * 0.62;
    return 1.0 - smoothstep(radius - 1.2, radius + 1.2, dist);
}

void main(void) {
    vec4 color = texture(uTexture, vTextureCoord);
    if (color.a > 0.0) {
        color.rgb /= color.a;
    }
    float cellSize = 5.0;
    vec3 screened = vec3(
        dotScreen(gl_FragCoord.xy, radians(15.0), color.r, cellSize),
        dotScreen(gl_FragCoord.xy, radians(75.0), color.g, cellSize),
        dotScreen(gl_FragCoord.xy, radians(0.0), color.b, cellSize)
    );
    color.rgb = mix(color.rgb, screened, uAmount);
    color.rgb *= color.a;
    finalColor = color;
}
`,D=`
in vec2 vTextureCoord;
out vec4 finalColor;

uniform sampler2D uTexture;
uniform highp vec4 uInputSize;
uniform highp vec4 uOutputFrame;
uniform float uAmount;

void main(void) {
    vec4 color = texture(uTexture, vTextureCoord);
    // The filtered container uses a viewport-sized filterArea, so this recovers stable
    // 0..1 viewport coordinates instead of coordinates derived from lyric bounds.
    vec2 screenUv = vTextureCoord * uInputSize.xy / uOutputFrame.zw;
    vec2 centered = screenUv - 0.5;
    centered.x *= uOutputFrame.z / uOutputFrame.w;
    float vignette = clamp(smoothstep(0.52, 1.08, length(centered)) * uAmount * 0.6, 0.0, 1.0);
    finalColor = mix(color, vec4(0.0, 0.0, 0.0, 1.0), vignette);
}
`,O=(t,n,r,i)=>new t.Filter({glProgram:t.GlProgram.from({vertex:e,fragment:r,name:n}),resources:{printUniforms:new t.UniformGroup({uAmount:{value:i,type:`f32`}})},antialias:`on`}),k=(e,t)=>{let n=[];return t.rgbShift>0&&n.push(O(e,`sonnet-print-rgb-shift`,T,t.rgbShift)),t.halftone>0&&n.push(O(e,`sonnet-print-halftone`,E,t.halftone)),t.vignette>0&&n.push(O(e,`sonnet-print-vignette`,D,t.vignette)),n},A={rgbShift:0,halftone:0,vignette:0},j=(e,t,n)=>{if(n)return{glowStrength:0,glowAlpha:0,noise:0,contrast:0,glitchIntensity:0,lensDistortion:0,lensDispersion:0,printEffects:A};let r=t.typographyMotion*a(e),i=t.postProcessEnabled;return{glowStrength:2.8+r*1.8,glowAlpha:Math.min(.62,.28+r*.12),noise:i?t.postProcessGrain*.35:0,contrast:i?t.postProcessContrast*.5:0,glitchIntensity:1,lensDistortion:i?t.postProcessLensDistortion:0,lensDispersion:i?t.postProcessLensDispersion:0,printEffects:i?{rgbShift:t.postProcessRgbShift,halftone:t.postProcessHalftone,vignette:t.postProcessVignette}:A}},M=(e,t)=>{let n=new e.Container,r=[];if(t.glowStrength>0){let i=new e.BlurFilter({strength:t.glowStrength,quality:2,kernelSize:5,resolution:.75});n.filters=[i],n.alpha=t.glowAlpha,n.blendMode=`screen`,r.push(i)}return{layer:n,filters:r}},N=(e,t,n)=>{let r=[];if((t.lensDistortion>0||t.lensDispersion>0)&&r.push(w(e,{distortion:t.lensDistortion,dispersion:t.lensDispersion})),t.noise>0){let i=new e.NoiseFilter({noise:t.noise,seed:n%1e4/1e4,antialias:`on`});r.push(i)}if(t.contrast>0){let n=new e.ColorMatrixFilter;n.contrast(t.contrast,!1),n.antialias=`on`,r.push(n)}let i=k(e,t.printEffects);return i.length>0&&r.push(...i),r},P=(e,t,n,r)=>{let i=N(e,n,r);return i.length>0&&(t.filters=i),i};export{d as _,t as a,i as c,c as d,a as f,u as g,m as h,j as i,l,b as m,M as n,s as o,x as p,N as r,o as s,P as t,g as u,p as v,S as y};