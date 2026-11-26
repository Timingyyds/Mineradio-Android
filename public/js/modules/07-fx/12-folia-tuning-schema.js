// ============================================================
// Folia 各预设（可视化器）的调参表：字段顺序、范围、开关、枚举与中文标签
// 由 folia-android 参考工程的 tuning 定义 / 设置面板 / zh-CN 文案生成，
// 供 13-folia-preset-tuning.js 渲染控件。
// ============================================================

var FOLIA_TUNING_MODES = {
  sonnet: {
    settingsKey: "sonnetTuning",
    setterKey: "handleSetSonnetTuning",
    resetKey: "handleResetSonnetTuning",
    storageKey: "sonnet_tuning"
  },
  tempera: {
    settingsKey: "temperaTuning",
    setterKey: "handleSetTemperaTuning",
    resetKey: "handleResetTemperaTuning",
    storageKey: "tempera_tuning"
  },
  lumiere: {
    settingsKey: "lumiereTuning",
    setterKey: "handleSetLumiereTuning",
    resetKey: "handleResetLumiereTuning",
    storageKey: "lumiere_tuning"
  },
  hanabi: {
    settingsKey: "hanabiTuning",
    setterKey: "handleSetHanabiTuning",
    resetKey: "handleResetHanabiTuning",
    storageKey: "hanabi_tuning"
  },
  machi: {
    settingsKey: "machiTuning",
    setterKey: "handleSetMachiTuning",
    resetKey: "handleResetMachiTuning",
    storageKey: "machi_tuning"
  },
  nebbia: {
    settingsKey: "nebbiaTuning",
    setterKey: "handleSetNebbiaTuning",
    resetKey: "handleResetNebbiaTuning",
    storageKey: "nebbia_tuning"
  },
  classic: {
    settingsKey: "classicTuning",
    setterKey: "handleSetClassicTuning",
    resetKey: "handleResetClassicTuning",
    storageKey: "classic_tuning"
  },
  cadenza: {
    settingsKey: "cadenzaTuning",
    setterKey: "handleSetCadenzaTuning",
    resetKey: "handleResetCadenzaTuning",
    storageKey: "cadenza_tuning"
  },
  partita: {
    settingsKey: "partitaTuning",
    setterKey: "handleSetPartitaTuning",
    resetKey: "handleResetPartitaTuning",
    storageKey: "partita_tuning"
  },
  fume: {
    settingsKey: "fumeTuning",
    setterKey: "handleSetFumeTuning",
    resetKey: "handleResetFumeTuning",
    storageKey: "fume_tuning"
  },
  tilt: {
    settingsKey: "tiltTuning",
    setterKey: "handleSetTiltTuning",
    resetKey: "handleResetTiltTuning",
    storageKey: "tilt_tuning"
  },
  claddagh: {
    settingsKey: "claddaghTuning",
    setterKey: "handleSetCladdaghTuning",
    resetKey: "handleResetCladdaghTuning",
    storageKey: "claddagh_tuning"
  },
  monet: {
    settingsKey: "monetBackgroundTuning",
    setterKey: "handleSetMonetBackgroundTuning",
    resetKey: "handleResetMonetBackgroundTuning",
    storageKey: "monet_background_tuning"
  },
  pendolo: {
    settingsKey: "pendoloTuning",
    setterKey: "handleSetPendoloTuning",
    resetKey: "handleResetPendoloTuning",
    storageKey: "pendolo_tuning"
  },
  cappella: {
    settingsKey: "cappellaTuning",
    setterKey: "handleSetCappellaTuning",
    resetKey: "handleResetCappellaTuning",
    storageKey: "cappella_tuning"
  },
  diorama: {
    settingsKey: "dioramaTuning",
    setterKey: "handleSetDioramaTuning",
    resetKey: "handleResetDioramaTuning",
    storageKey: "diorama_tuning"
  }
};

var FOLIA_TUNING_MODE_ALIAS = { still: 'classic' };

var FOLIA_TUNING_ORDER = {
  sonnet: [
    "cameraIntensity",
    "typographyMotion",
    "mgDensity",
    "showOnlyText",
    "showGuide",
    "showBackgroundMg",
    "showFixedGeo",
    "showGiantDecorativeText",
    "showBackgroundDecor",
    "enableTransitions",
    "outerFrameMode",
    "textureResolution",
    "postProcessEnabled",
    "postProcessGrain",
    "postProcessContrast",
    "postProcessRgbShift",
    "postProcessHalftone",
    "postProcessVignette",
    "postProcessLensDistortion",
    "postProcessLensDispersion"
  ],
  tempera: [
    "cameraIntensity",
    "glyphMotion",
    "wholeLineLyrics",
    "glyphSettleStretch",
    "depthOfField",
    "depthPan",
    "glyphDepth",
    "colorMode",
    "showBlocks",
    "showDecor",
    "showCornerMarks",
    "textInversion",
    "layerImageDepth",
    "layerImageFrequency",
    "enableTransitions",
    "textureResolution",
    "postProcessEnabled",
    "postProcessTextureCompression",
    "postProcessGrain",
    "postProcessContrast",
    "postProcessRgbShift",
    "postProcessVignette",
    "postProcessLensDistortion"
  ],
  lumiere: [
    "lightIntensity",
    "audioResponse",
    "fogDensity",
    "darkField",
    "moteAmount",
    "bloom",
    "textBloom",
    "unlitOpacity",
    "windowNeighbors",
    "decay",
    "echo",
    "fogOctaves",
    "lineArt",
    "frontBokeh",
    "trails",
    "hideTrails",
    "seamlessTransitions",
    "overlayFrame",
    "textOnly",
    "keywordColors",
    "themeIcons",
    "themeColorMix",
    "renderQuality"
  ],
  hanabi: [
    "colorMode",
    "sparkAmount",
    "ornamentAmount",
    "textMotion",
    "blockOpacity",
    "riseSpeed",
    "spin",
    "ambient",
    "glow",
    "bloom",
    "chorusBloom",
    "launches",
    "overlayFrame",
    "textOnly",
    "sky",
    "collage",
    "renderQuality"
  ],
  machi: [
    "scene",
    "timeOfDay",
    "weather",
    "camera",
    "motion",
    "motionSpeed",
    "aperture",
    "dof",
    "dofRange",
    "tiltShift",
    "focus",
    "focusOffset"
  ],
  nebbia: [
    "appear",
    "fade",
    "layouts",
    "credits",
    "jitter",
    "warp",
    "mist",
    "hold",
    "refog",
    "strokeWidth",
    "writeSpeed",
    "doodle",
    "busSpeed",
    "neon",
    "spot",
    "spotX",
    "rainHit",
    "rainBig",
    "bump",
    "resolution"
  ],
  classic: [
    "enableWordRotation",
    "breathingFloatMultiplier",
    "useLegacyLayout",
    "wordSpacing"
  ],
  cadenza: [
    "fontScale",
    "widthRatio",
    "motionAmount",
    "glowIntensity",
    "beamIntensity"
  ],
  partita: [
    "showGuideLines",
    "useSemanticLayout",
    "staggerMin",
    "staggerMax"
  ],
  fume: [
    "hidePrintSymbols",
    "disableGeometricBackground",
    "backgroundObjectOpacity",
    "textHoldRatio",
    "cameraTrackingMode",
    "cameraSpeed",
    "glowIntensity",
    "heroScale"
  ],
  tilt: [
    "splitProbability",
    "tiltStyleProbability",
    "colorScheme"
  ],
  claddagh: [
    "focusScaleRatio",
    "radiusScale",
    "ellipseTiltDeg",
    "showAxisLine",
    "letterSpacingOffset"
  ],
  monet: [
    "keywordColoringEnabled",
    "showDescription",
    "showAudioVisualization",
    "audioStyle",
    "fontScale",
    "portraitSource",
    "portraitOffsetX",
    "portraitStyle",
    "showPortraitDragHanger"
  ],
  pendolo: [
    "arcRadius",
    "arcAngleDeg",
    "wheelCenterX",
    "wheelCenterY",
    "tickSnappiness",
    "activeScale",
    "showGearDecor",
    "showCenterGradient",
    "showCoverOnWatchFace",
    "enableLineGlow"
  ],
  cappella: [
    "showEmoMessages",
    "emojiPackSource",
    "avatarSource"
  ],
  diorama: [
    "cameraSpeed",
    "motionAmount",
    "audioReactivity",
    "particleDensity",
    "particleScale",
    "particleGlowEnabled",
    "particleGlowIntensity",
    "showParticles",
    "backgroundParticleCircumference",
    "backgroundParticleRadial",
    "glowEnabled",
    "glowIntensity",
    "soulEnabled",
    "soulIntensity",
    "soulActiveEnabled",
    "gradientEnabled",
    "gradientIntensity",
    "keywordColoringEnabled"
  ]
};

var FOLIA_TUNING_FIELDS = {
  cameraIntensity: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "镜头运动强度"
  },
  typographyMotion: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "文字动势"
  },
  mgDensity: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "背景短线密度"
  },
  showOnlyText: {
    bool: true,
    label: "仅显示文字"
  },
  showGuide: {
    bool: true,
    label: "轨迹线"
  },
  showBackgroundMg: {
    bool: true,
    label: "主场景"
  },
  showFixedGeo: {
    bool: true,
    label: "文字浮标"
  },
  showGiantDecorativeText: {
    bool: true,
    label: "巨型装饰镂空文字"
  },
  showBackgroundDecor: {
    bool: true,
    label: "背景装饰"
  },
  enableTransitions: {
    bool: true,
    label: "场景转场"
  },
  outerFrameMode: {
    bool: true,
    label: "外层边框"
  },
  textureResolution: {
    min: 0.5,
    max: 4,
    step: 0.25,
    label: "纹理分辨率"
  },
  postProcessEnabled: {
    bool: true,
    label: "整体后处理滤镜"
  },
  postProcessGrain: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "胶片颗粒"
  },
  postProcessContrast: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "对比度增强"
  },
  postProcessRgbShift: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "RGB 色差"
  },
  postProcessHalftone: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "半调网点"
  },
  postProcessVignette: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "暗角"
  },
  postProcessLensDistortion: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "透镜扭曲"
  },
  postProcessLensDispersion: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "透镜色散"
  },
  glyphMotion: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "逐字动势"
  },
  wholeLineLyrics: {
    bool: true,
    label: "整句显示歌词"
  },
  glyphSettleStretch: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "逐字入场时序"
  },
  depthOfField: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "景深"
  },
  depthPan: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "镜头平移"
  },
  glyphDepth: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "歌词远近"
  },
  showBlocks: {
    bool: true,
    label: "色块场景"
  },
  showDecor: {
    bool: true,
    label: "装饰元素"
  },
  showCornerMarks: {
    bool: true,
    label: "边角线框"
  },
  textInversion: {
    bool: true,
    label: "文字动态反色"
  },
  layerImageFrequency: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "出现频率"
  },
  postProcessTextureCompression: {
    bool: true,
    label: "后处理纹理压缩"
  },
  lightIntensity: {
    min: 0.3,
    max: 2,
    step: 0.05,
    label: "光强"
  },
  audioResponse: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "随音乐变亮"
  },
  fogDensity: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "烟雾浓度"
  },
  darkField: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "暗场强度"
  },
  moteAmount: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "浮尘数量"
  },
  bloom: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "整体辉光"
  },
  textBloom: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "文字辉光"
  },
  unlitOpacity: {
    min: 0.05,
    max: 0.6,
    step: 0.05,
    label: "未唱字透明度"
  },
  decay: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "崩解强度"
  },
  echo: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "背景歌词"
  },
  fogOctaves: {
    min: 2,
    max: 6,
    step: 0.05,
    label: "烟雾细节"
  },
  lineArt: {
    bool: true,
    label: "线稿"
  },
  frontBokeh: {
    bool: true,
    label: "前景散景"
  },
  trails: {
    bool: true,
    label: "所有换位都走轨迹线"
  },
  hideTrails: {
    bool: true,
    label: "隐藏轨迹线"
  },
  seamlessTransitions: {
    bool: true,
    label: "轨迹过渡"
  },
  overlayFrame: {
    bool: true,
    label: "画框"
  },
  textOnly: {
    bool: true,
    label: "仅显示歌词文字"
  },
  keywordColors: {
    bool: true,
    label: "关键字着色"
  },
  themeIcons: {
    bool: true,
    label: "主题图标"
  },
  themeColorMix: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "主题色占比"
  },
  sparkAmount: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "火花数量"
  },
  ornamentAmount: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "MG 装饰件"
  },
  textMotion: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "文字动作"
  },
  blockOpacity: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "字底色块"
  },
  riseSpeed: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "上升"
  },
  spin: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "旋转"
  },
  ambient: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "环境火星"
  },
  glow: {
    bool: true,
    label: "辉光与光条"
  },
  chorusBloom: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "副歌辉光"
  },
  launches: {
    bool: true,
    label: "升空尾迹"
  },
  sky: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "夜空"
  },
  collage: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "PV 拼贴"
  },
  motion: {
    bool: true,
    label: "运镜"
  },
  motionSpeed: {
    min: 0.25,
    max: 3,
    step: 0.05,
    label: "运镜速度"
  },
  aperture: {
    min: 1.4,
    max: 16,
    step: 0.1,
    label: "光圈"
  },
  dof: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "景深强度"
  },
  dofRange: {
    min: 0.3,
    max: 3,
    step: 0.05,
    label: "清晰范围"
  },
  tiltShift: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "移轴"
  },
  focusOffset: {
    min: -10,
    max: 10,
    step: 0.1,
    label: "对焦前后"
  },
  credits: {
    bool: true,
    label: "片尾写曲名和歌手"
  },
  jitter: {
    min: 0,
    max: 1.5,
    step: 0.05,
    label: "手写歪斜"
  },
  warp: {
    min: 0,
    max: 1.5,
    step: 0.05,
    label: "手写变形"
  },
  mist: {
    min: 0.2,
    max: 1,
    step: 0.05,
    label: "雾气浓度"
  },
  hold: {
    min: 0,
    max: 6,
    step: 0.05,
    label: "下一句开始后保持"
  },
  refog: {
    min: 0.5,
    max: 12,
    step: 0.1,
    label: "重新起雾用时"
  },
  strokeWidth: {
    min: 0.04,
    max: 0.16,
    step: 0.005,
    label: "笔画粗细"
  },
  writeSpeed: {
    min: 0.4,
    max: 2.5,
    step: 0.05,
    label: "书写速度"
  },
  doodle: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "句尾画图标"
  },
  busSpeed: {
    min: 0,
    max: 3,
    step: 0.05,
    label: "车速"
  },
  neon: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "霓虹"
  },
  spot: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "聚光灯"
  },
  spotX: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "聚光灯位置"
  },
  rainHit: {
    min: 0,
    max: 3,
    step: 0.05,
    label: "雨点打在玻璃上"
  },
  rainBig: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "吐字时的大雨点"
  },
  bump: {
    min: 0,
    max: 2.5,
    step: 0.05,
    label: "颠簸"
  },
  resolution: {
    min: 0.5,
    max: 2,
    step: 0.05,
    label: "渲染分辨率"
  },
  enableWordRotation: {
    bool: true,
    label: "逐字旋转"
  },
  breathingFloatMultiplier: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "呼吸浮动范围"
  },
  useLegacyLayout: {
    bool: true,
    label: "排版模式"
  },
  wordSpacing: {
    min: 0,
    max: 2,
    step: 0.05,
    label: "单词间距"
  },
  fontScale: {
    min: 0.6,
    max: 2,
    step: 0.05,
    label: "字体缩放"
  },
  widthRatio: {
    min: 0.4,
    max: 1,
    step: 0.02,
    label: "行宽比"
  },
  motionAmount: {
    min: 0.4,
    max: 1.6,
    step: 0.05,
    label: "运动幅度"
  },
  glowIntensity: {
    min: 0,
    max: 1.8,
    step: 0.05,
    label: "当前句辉光强度"
  },
  showGuideLines: {
    bool: true,
    label: "引导线"
  },
  useSemanticLayout: {
    bool: true,
    label: "语义排列"
  },
  staggerMin: {
    min: 0,
    max: 180,
    step: 5,
    label: "错位最小值"
  },
  staggerMax: {
    min: 0,
    max: 180,
    step: 5,
    label: "错位最大值"
  },
  hidePrintSymbols: {
    bool: true,
    label: "隐藏打印方块"
  },
  disableGeometricBackground: {
    bool: true,
    label: "通用几何图形"
  },
  backgroundObjectOpacity: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "世界背景物体透明度"
  },
  textHoldRatio: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "文字停留比例"
  },
  cameraSpeed: {
    min: 0.55,
    max: 1.85,
    step: 0.05,
    label: "镜头速度"
  },
  heroScale: {
    min: 0.82,
    max: 1.32,
    step: 0.02,
    label: "大标题比例"
  },
  splitProbability: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "分行概率"
  },
  tiltStyleProbability: {
    min: 0,
    max: 1,
    step: 0.05,
    label: "斜体强调概率"
  },
  focusScaleRatio: {
    min: 0,
    max: 1.5,
    step: 0.05,
    label: "主歌词放大倍率"
  },
  radiusScale: {
    min: 0.5,
    max: 1.5,
    step: 0.05,
    label: "轨道半径"
  },
  ellipseTiltDeg: {
    min: 0,
    max: 60,
    step: 1,
    label: "轨道倾斜度"
  },
  showAxisLine: {
    bool: true,
    label: "中间轴线"
  },
  letterSpacingOffset: {
    min: -5,
    max: 20,
    step: 0.5,
    label: "字符间距"
  },
  keywordColoringEnabled: {
    bool: true,
    label: "关键字着色"
  },
  showDescription: {
    bool: true,
    label: "显示歌曲描述"
  },
  showAudioVisualization: {
    bool: true,
    label: "音频图案"
  },
  showPortraitDragHanger: {
    bool: true,
    label: "拖拽调整按钮"
  },
  arcRadius: {
    min: 0.25,
    max: 0.8,
    step: 0.01,
    label: "轮盘半径"
  },
  arcAngleDeg: {
    min: 40,
    max: 160,
    step: 5,
    label: "弧度角度"
  },
  wheelCenterX: {
    min: -0.2,
    max: 0.4,
    step: 0.01,
    label: "轮盘水平位置 (0 = 左边缘)"
  },
  wheelCenterY: {
    min: 0.2,
    max: 0.8,
    step: 0.05,
    label: "圆心上下"
  },
  tickSnappiness: {
    min: 0.5,
    max: 2,
    step: 0.05,
    label: "擒纵咬合力度"
  },
  activeScale: {
    min: 1,
    max: 1.6,
    step: 0.05,
    label: "聚焦句缩放"
  },
  showCenterGradient: {
    bool: true,
    label: "齿轮中央深色渐变"
  },
  showCoverOnWatchFace: {
    bool: true,
    label: "表盘显示专辑封面"
  },
  enableLineGlow: {
    bool: true,
    label: "线条发光效果"
  },
  showEmoMessages: {
    bool: true,
    label: "显示表情包"
  },
  audioReactivity: {
    min: 0,
    max: 1.5,
    step: 0.05,
    label: "点云音频响应"
  },
  particleDensity: {
    min: 96,
    max: 1536,
    step: 16,
    label: "点云密度"
  },
  particleScale: {
    min: 0.65,
    max: 1.6,
    step: 0.05,
    label: "点云整体体积"
  },
  particleGlowEnabled: {
    bool: true,
    label: "点云辉光"
  },
  particleGlowIntensity: {
    min: 0.1,
    max: 1.5,
    step: 0.05,
    label: "辉光强度"
  },
  showParticles: {
    bool: true,
    label: "背景粒子"
  },
  backgroundParticleCircumference: {
    min: 4,
    max: 48,
    step: 1,
    label: "圆周密度"
  },
  backgroundParticleRadial: {
    min: 1,
    max: 4,
    step: 0.1,
    label: "径向密度"
  },
  glowEnabled: {
    bool: true,
    label: "普通辉光跟唱"
  },
  soulEnabled: {
    bool: true,
    label: "灵魂出窍跟唱"
  },
  soulIntensity: {
    min: 0.1,
    max: 1.5,
    step: 0.05,
    label: "出窍强度"
  },
  soulActiveEnabled: {
    bool: true,
    label: "当前字出窍"
  },
  gradientEnabled: {
    bool: true,
    label: "渐变跟唱"
  },
  gradientIntensity: {
    min: 0.1,
    max: 1.5,
    step: 0.05,
    label: "渐变强度"
  }
};

var FOLIA_TUNING_ENUMS = {
  "tempera|colorMode": [
    [
      "duo",
      "双色"
    ],
    [
      "mono",
      "单色"
    ],
    [
      "gradient",
      "渐变"
    ]
  ],
  "tempera|layerImageDepth": [
    [
      "front",
      "前景"
    ],
    [
      "back",
      "背景"
    ]
  ],
  "lumiere|windowNeighbors": [
    [
      "1",
      "只看上一个"
    ],
    [
      2,
      "前后都看"
    ]
  ],
  "lumiere|renderQuality": [
    [
      "full",
      "全分辨率"
    ],
    [
      "balanced",
      "平衡"
    ],
    [
      "low",
      "省电"
    ]
  ],
  "hanabi|colorMode": [
    [
      "mixed",
      "混色"
    ],
    [
      "vivid",
      "鲜艳"
    ],
    [
      "theme",
      "主题色"
    ]
  ],
  "hanabi|renderQuality": [
    [
      "full",
      "全分辨率"
    ],
    [
      "balanced",
      "平衡"
    ],
    [
      "low",
      "省电"
    ]
  ],
  "machi|scene": [
    [
      "auto",
      "自动"
    ],
    [
      "konbini",
      "便利店"
    ],
    [
      "station",
      "加油站"
    ],
    [
      "kowloon",
      "城寨酒吧"
    ]
  ],
  "machi|timeOfDay": [
    [
      "auto",
      "自动"
    ],
    [
      "day",
      "白天"
    ],
    [
      "dusk",
      "黄昏"
    ],
    [
      "night",
      "夜晚"
    ]
  ],
  "machi|weather": [
    [
      "auto",
      "自动"
    ],
    [
      "clear",
      "晴"
    ],
    [
      "rain",
      "雨"
    ],
    [
      "snow",
      "雪"
    ],
    [
      "fog",
      "雾"
    ]
  ],
  "machi|camera": [
    [
      "default",
      "默认视角"
    ],
    [
      "custom",
      "自定义"
    ]
  ],
  "machi|focus": [
    [
      "center",
      "居中"
    ],
    [
      "lyrics",
      "歌词"
    ],
    [
      "manual",
      "手动"
    ]
  ],
  "fume|cameraTrackingMode": [
    [
      "stepped",
      "分段"
    ],
    [
      "smooth",
      "平滑"
    ]
  ],
  "tilt|colorScheme": [
    [
      "default",
      "默认"
    ],
    [
      "swap",
      "交换"
    ],
    [
      "accentAll",
      "全强调色"
    ],
    [
      "primaryAll",
      "全主色"
    ]
  ],
  "monet|audioStyle": [
    [
      "bar",
      "条形"
    ],
    [
      "line",
      "线形"
    ]
  ],
  "monet|portraitSource": [
    [
      "cover",
      "封面"
    ],
    [
      "custom",
      "自定义"
    ]
  ],
  "monet|portraitStyle": [
    [
      "rectangular",
      "矩形"
    ],
    [
      "square",
      "方形"
    ]
  ],
  "pendolo|showGearDecor": [
    [
      "none",
      "无"
    ],
    [
      "subtle",
      "简约"
    ],
    [
      "full",
      "完整"
    ]
  ],
  "cappella|emojiPackSource": [
    [
      "builtin",
      "内置"
    ],
    [
      "custom",
      "自定义"
    ]
  ],
  "cappella|avatarSource": [
    [
      "cover",
      "封面"
    ],
    [
      "custom",
      "自定义"
    ]
  ]
};

// ------------------------------------------------------------
// 补充与修正：字段名在不同预设间重名时，渲染层按「运行时值的类型」决定用滑块还是开关；
// 多选型（数组）单独放在 FOLIA_TUNING_MULTI 里。
// ------------------------------------------------------------
FOLIA_TUNING_FIELDS.glow = { label: '辉光', min: 0, max: 2, step: 0.05 };
FOLIA_TUNING_FIELDS.fogOctaves = { label: '雾层数', min: 2, max: 6, step: 1 };

FOLIA_TUNING_ENUMS['sonnet|outerFrameMode'] = [['none', '完全隐藏'], ['frame', '仅显示框架'], ['full', '完全显示']];

var FOLIA_TUNING_MULTI = {
  'nebbia|appear': [['write', '手指书写'], ['fade', '慢慢显现'], ['breath', '哈气后书写']],
  'nebbia|fade': [['fog', '慢慢起雾'], ['fingers', '手指抹掉']],
  'nebbia|layouts': [
    ['ribbon', '横条'], ['lines2', '两行错落'], ['lines3', '三行错落'], ['cross', '十字'],
    ['pillar', '竖柱'], ['header', '刊头'], ['badge', '徽章'], ['columns', '竖排几列'], ['orbit', '环绕']
  ]
};
