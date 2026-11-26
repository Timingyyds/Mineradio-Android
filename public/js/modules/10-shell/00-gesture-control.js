// ============================================================
//  粒子旋转 —— 指针 / 触摸拖动 + 惯性
//  ------------------------------------------------------------
//  只保留 3D 主循环真正需要的旋转状态:
//    gestureRotation  累计旋转角, 输出给 particles / bloomParticles /
//                     floatGroup / backCoverGroup / 歌单架骷髅层
//    particleSpin     角速度 + 每帧衰减 (松手后保留惯性)
//  摄像头手势识别 (MediaPipe / 手部关键点 / 捏合 / 握拳 / 滑切歌 /
//  音量 / V 手势 / 拇指喜欢 / 三指歌词 / 手势 HUD / 手骨架 canvas)
//  已按需求整体删除。
//  被以下文件使用: 11-main-loop.js (tickGestureRotation)、
//  02-visual/00-pointer-cover-particles.js (applyParticleSpinDrag)、
//  01-scene/03-focus-cinema-camera.js、02-visual/01-float-skull-backcover.js、
//  07-fx/00-preset-archive-data.js (预设存档读写旋转角)。
// ============================================================

// 物理旋转: 给 particles 一个角速度, 每帧衰减
var particleSpin = { vx: 0, vy: 0, damping: 0.90 };
// 拖动驱动的总旋转 (累计角度), 输出到 particles
var gestureRotation = { x: 0, y: 0 };

// 指针 / 触摸拖动 => 每像素旋转量
var PARTICLE_POINTER_SPIN_X = 0.0032;
var PARTICLE_POINTER_SPIN_Y = 0.0034;
// 角速度上限
var PARTICLE_SPIN_MAX = 6.2;

function clampParticleSpinVelocity(v) {
  if (!isFinite(v)) return 0;
  return Math.max(-PARTICLE_SPIN_MAX, Math.min(PARTICLE_SPIN_MAX, v));
}

function applyParticleSpinDrag(dx, dy, dt) {
  var rx = dy * PARTICLE_POINTER_SPIN_X;
  var ry = dx * PARTICLE_POINTER_SPIN_Y;
  gestureRotation.x += rx;
  gestureRotation.y += ry;
  if (dt > 0) {
    particleSpin.vx = clampParticleSpinVelocity(rx / dt * 0.46);
    particleSpin.vy = clampParticleSpinVelocity(ry / dt * 0.46);
  }
}

function resetParticleRotationTarget(syncVisual) {
  gestureRotation.x = 0;
  gestureRotation.y = 0;
  particleSpin.vx = 0;
  particleSpin.vy = 0;
  if (syncVisual && particles) {
    particles.rotation.set(0, 0, 0);
    if (bloomParticles) bloomParticles.rotation.set(0, 0, 0);
    if (floatGroup) floatGroup.rotation.set(0, 0, 0);
    if (backCoverGroup) backCoverGroup.rotation.set(0, 0, 0);
  }
}

function rebaseParticleRotationAxis(axis) {
  var limit = Math.PI * 10;
  if (Math.abs(gestureRotation[axis]) < limit) return;
  var offset = Math.round(gestureRotation[axis] / (Math.PI * 2)) * Math.PI * 2;
  gestureRotation[axis] -= offset;
  if (particles) particles.rotation[axis] -= offset;
  if (bloomParticles) bloomParticles.rotation[axis] -= offset;
  if (floatGroup) floatGroup.rotation[axis] -= offset;
  if (backCoverGroup) backCoverGroup.rotation[axis] -= offset;
  if (skullParticleGroup) skullParticleGroup.rotation[axis] -= offset;
  if (stageLyrics.group) stageLyrics.group.rotation[axis] -= offset;
}

function rebaseParticleRotationIfNeeded() {
  rebaseParticleRotationAxis('x');
  rebaseParticleRotationAxis('y');
}

// 每帧调用 — 应用惯性旋转 + 角速度衰减
function tickGestureRotation(dt) {
  if (Math.abs(particleSpin.vx) > 0.0001 || Math.abs(particleSpin.vy) > 0.0001) {
    var rx = particleSpin.vx * dt;
    var ry = particleSpin.vy * dt;
    gestureRotation.x += rx;
    gestureRotation.y += ry;
    rebaseParticleRotationIfNeeded();
  }
  particleSpin.vx *= Math.pow(particleSpin.damping, dt * 60);
  particleSpin.vy *= Math.pow(particleSpin.damping, dt * 60);
  if (Math.abs(particleSpin.vx) < 0.01) particleSpin.vx = 0;
  if (Math.abs(particleSpin.vy) < 0.01) particleSpin.vy = 0;
}

// ============================================================
//  Resize / 快捷键
