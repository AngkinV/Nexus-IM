/**
 * The Creator's Eye — fullscreen WebGL stage for the AI warp transition.
 *
 * The GLSL below is the design prototype's shader, kept verbatim so the eye
 * renders exactly as designed. Everything around it is the app-side runtime:
 * the prototype rendered forever on page load, this one only draws while the
 * warp overlay is open and releases its listeners when it closes.
 */

const VERTEX_SHADER = `attribute vec2 a_position;
varying vec2 v_texCoord;
void main() {
  v_texCoord = a_position * 0.5 + 0.5;
  gl_Position = vec4(a_position, 0.0, 1.0);
}`

const FRAGMENT_SHADER = `precision highp float;
uniform float u_time;
uniform vec2 u_resolution;
uniform vec2 u_mouse;
varying vec2 v_texCoord;

// =========================================================================
// THE UNBOUND ABYSSAL CREATOR'S OCULAR ENTITY (无界造物主之眼 · 物理粒子与地狱凝视)
// 保证健壮性与高对比暗黑纯正渲染，彻底避免 WebGL 编译/除零导致的全白屏
// =========================================================================

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.87758, 0.47942, -0.47942, 0.87758);
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = rot * p * 2.0 + vec2(10.0);
        a *= 0.5;
    }
    return v;
}

// 3x5 Binary/ASCII Matrix Glyph Generator
float glyph(vec2 p, float seed) {
    vec2 grid = floor(p * vec2(3.0, 5.0));
    if (grid.x < 0.0 || grid.x > 2.0 || grid.y < 0.0 || grid.y > 4.0) return 0.0;
    
    float charType = fract(seed * 0.173);
    if (charType < 0.45) {
        if (grid.x == 1.0 && (grid.y >= 1.0 && grid.y <= 3.0)) return 0.0;
        return 1.0;
    } else if (charType < 0.85) {
        if (grid.x == 1.0) return 1.0;
        if (grid.x == 0.0 && grid.y == 3.0) return 1.0;
        if (grid.y == 0.0) return 1.0;
        return 0.0;
    } else {
        if (grid.y == 2.0 || grid.x == 1.0) return 1.0;
        return 0.0;
    }
}

void main() {
    vec2 res = max(u_resolution, vec2(1.0, 1.0));
    vec2 uv = (gl_FragCoord.xy - 0.5 * res) / min(res.x, res.y);
    float t = u_time * 1.35;
    
    // 微弱的视差压迫（微弱跟随光标）
    vec2 m = (u_mouse - 0.5 * res) / min(res.x, res.y);
    vec2 eyeCenterOffset = m * 0.04;
    vec2 lookUV = uv - eyeCenterOffset;

    float r = length(lookUV);
    float a = atan(lookUV.y, lookUV.x);

    // =========================================================
    // 1. 自然解剖眼裂与物理眨眼周期 (无实线外框，虚化黑炎与粒子消融)
    // =========================================================
    float blinkCycle = mod(t * 0.75, 6.2831);
    float blink = 1.0;
    float blinkPhase = 0.0; // 0.0 = 睁眼, 1.0 = 闭合峰值
    
    if (blinkCycle > 4.2 && blinkCycle < 5.2) {
        float bt = (blinkCycle - 4.2) / 1.0;
        blinkPhase = sin(bt * 3.14159);
        blink = 1.0 - pow(blinkPhase, 2.4);
    }
    float openFactor = clamp(blink, 0.015, 1.0);

    // 解剖学杏仁弧度，完全居中无硬框
    float eyeWidth = 0.74;
    float eyeHeight = 0.40 * openFactor;
    
    float xNorm = clamp(abs(uv.x) / eyeWidth, 0.0, 1.0);
    float arch = 1.0 - xNorm * xNorm;
    
    // 眼眶羽化边缘黑炎微扰动
    float flameEdgeUpper = (noise(vec2(uv.x * 10.0, t * 2.2)) - 0.5) * 0.035;
    float flameEdgeLower = (noise(vec2(uv.x * 10.0 + 3.0, t * 2.2)) - 0.5) * 0.035;
    
    float upperLid = eyeHeight * arch + flameEdgeUpper;
    float lowerLid = -eyeHeight * arch * 0.92 + flameEdgeLower;
    
    // 渐进羽化眼眶遮罩
    float inUpper = smoothstep(upperLid + 0.03, upperLid - 0.03, uv.y);
    float inLower = smoothstep(lowerLid - 0.03, lowerLid + 0.03, uv.y);
    float inWidth = smoothstep(eyeWidth + 0.06, eyeWidth - 0.06, abs(uv.x));
    float isInsideEye = inUpper * inLower * inWidth;

    // 虚化眼线：由沿弧线散落的二进制字符碎片羽化勾勒，绝无粗实线
    vec2 lidParticleUV = vec2(uv.x * 42.0, (uv.y - upperLid) * 32.0);
    float lidGlyphUpper = glyph(fract(lidParticleUV), floor(lidParticleUV.x) + 17.0);
    float lidGlowUpper = smoothstep(0.045, 0.0, abs(uv.y - upperLid)) * inWidth;
    float softUpperLid = lidGlowUpper * (0.35 + 0.65 * lidGlyphUpper) * noise(vec2(uv.x * 18.0, t * 3.0));

    float lidGlowLower = smoothstep(0.038, 0.0, abs(uv.y - lowerLid)) * inWidth;
    float softLowerLid = lidGlowLower * (0.3 + 0.5 * glyph(fract(lidParticleUV + vec2(0.5, 0.2)), floor(lidParticleUV.x) + 33.0));

    // =========================================================
    // 2. 巩膜眼白 (SCLERA): 冥界代码毛细血管与深渊侵蚀
    // =========================================================
    float scleraNoise = fbm(lookUV * 5.5 + vec2(t * 0.15, -t * 0.3));
    float veinRays = sin(a * 26.0 + noise(lookUV * 12.0) * 4.0);
    float veinMask = smoothstep(0.7, 0.95, abs(veinRays)) * smoothstep(0.18, 0.52, r);
    
    vec2 scleraCodeCoord = lookUV * vec2(36.0, 22.0);
    scleraCodeCoord.y += t * 1.6;
    float scleraChar = glyph(fract(scleraCodeCoord), hash(floor(scleraCodeCoord)) * 70.0);
    float scleraFlow = (scleraChar * 0.4 + veinMask * 0.65) * (1.0 - smoothstep(0.2, 0.55, r) * 0.5);

    // =========================================================
    // 3. 虹膜与深渊瞳孔 (地狱归来：撕裂视界、冥炎环流与极度压迫)
    // =========================================================
    float irisR = 0.245;
    float pupilR = 0.086 + 0.015 * sin(t * 2.2) + 0.006 * sin(t * 6.5);

    float inIris = smoothstep(irisR + 0.02, irisR - 0.01, r) * smoothstep(pupilR - 0.015, pupilR + 0.015, r);

    // 地狱虹膜：黑炎撕裂旋涡 + 断裂誓约二进制环
    float vortexAngle = a + t * 0.85 + 2.0 * noise(vec2(r * 9.0, t));
    float infernoSpokes = abs(sin(vortexAngle * 16.0));
    float infernoNoise = fbm(vec2(r * 11.0 - t * 2.0, vortexAngle * 3.0));
    
    vec2 ringCodeUV = vec2(fract(vortexAngle * 24.0 / 6.2831), fract(r * 38.0));
    float ringCode = glyph(ringCodeUV, floor(vortexAngle * 24.0) + floor(r * 38.0));
    float jaggedLimbal = smoothstep(0.025, 0.0, abs(r - irisR + (noise(vec2(a * 15.0, t)) - 0.5) * 0.03));
    
    float irisTexture = (infernoSpokes * 0.5 + infernoNoise * 0.7 + ringCode * 0.6) * inIris;
    irisTexture += jaggedLimbal * 1.5 * inIris;

    // 瞳孔设计：垂直深渊裂隙黑洞奇点 (Infernal Slit Singularity)
    float slitX = abs(lookUV.x) / (0.42 + 0.28 * sin(t * 1.5));
    float slitShape = smoothstep(pupilR * 1.35, pupilR * 0.25, slitX) * smoothstep(pupilR * 1.45, pupilR * 0.2, abs(lookUV.y));
    float roundPupil = smoothstep(pupilR + 0.01, pupilR - 0.01, r);
    float hellPupil = clamp(roundPupil + slitShape * 1.25, 0.0, 1.0);

    // 瞳孔事件视界
    float eventHorizon = smoothstep(0.018, 0.0, abs(r - pupilR)) * (0.8 + 0.6 * ringCode);

    // 冷酷微观针尖高光
    float specular1 = smoothstep(0.018, 0.0, length(lookUV - vec2(0.045, 0.055)));
    float specular2 = smoothstep(0.010, 0.0, length(lookUV - vec2(-0.038, -0.03)));
    float coldSpecular = specular1 * 2.0 + specular2 * 1.2;

    // =========================================================
    // 4. 物理学进入与常态粒子：万有引力坍缩内卷 (Inward Gravitational Collapse)
    // 遵循引力反平方定律向中心黑洞加速螺旋卷入
    // =========================================================
    float inwardCollapse = 0.0;
    for (int k = 1; k <= 3; k++) {
        float fk = float(k);
        float spiralSpeed = t * (0.85 + fk * 0.35);
        float inwardR = fract(r * 2.2 - spiralSpeed * 0.22);
        float spiralA = a + (1.0 / (r + 0.08)) * 0.42 + fk * 2.094;
        
        vec2 pGrid = vec2(fract(spiralA * 18.0 / 6.2831), fract(inwardR * 12.0));
        float pSeed = hash(vec2(floor(spiralA * 18.0 / 6.2831), floor(inwardR * 12.0)) + fk * 37.1);
        if (pSeed > 0.60) {
            float gChar = glyph(pGrid, pSeed * 80.0);
            float gravFactor = smoothstep(0.9, 0.15, r) * smoothstep(0.08, 0.25, r);
            inwardCollapse += gChar * gravFactor * (0.6 / fk);
        }
    }

    // =========================================================
    // 5. 物理学眨眼粉碎粒子系统：动量守恒暴风喷射 (Outward Pulverization Explosion)
    // 闭眼瞬间：能量自眼缝压缩至极限并向外爆炸喷射
    // =========================================================
    float outwardExplosion = 0.0;
    float shockwaveLuma = 0.0;

    if (blinkPhase > 0.02) {
        float bPower = pow(blinkPhase, 1.6);
        
        // 眼裂合拢中缝的剧烈能量激光剪切 (虚化剪切断口)
        float seamDist = abs(uv.y);
        float tearJitter = noise(vec2(uv.x * 45.0, t * 25.0));
        float seamShear = smoothstep(0.035, 0.0, seamDist + tearJitter * 0.01) * 
                          smoothstep(eyeWidth * 1.3, 0.0, abs(uv.x)) * bPower * 2.6;
        
        // 5 层高速暴风粉碎粒子，向四面八方喷射碎裂字符
        for (int i = 1; i <= 5; i++) {
            float fi = float(i);
            vec2 pDir = vec2(sin(fi * 2.11 + uv.x * 4.0), cos(fi * 1.83 + uv.y * 4.0) * 1.5);
            vec2 pCoord = uv + pDir * (bPower * 0.28 * fi);
            vec2 pGrid = pCoord * vec2(36.0 + fi * 10.0, 26.0 + fi * 8.0);
            
            float pSeed = hash(floor(pGrid) + fi * 31.7);
            if (pSeed > 0.48) {
                float microChar = glyph(fract(pGrid), pSeed * 120.0);
                float distFalloff = smoothstep(0.95, 0.05, length(uv));
                outwardExplosion += microChar * bPower * distFalloff * (1.2 / fi);
            }
        }

        // 空间粉碎激波
        float waveRadius = (1.0 - openFactor) * 0.72;
        float waveDist = abs(length(uv) - waveRadius);
        shockwaveLuma = smoothstep(0.025, 0.0, waveDist) * bPower * (1.0 - smoothstep(0.1, 0.75, waveRadius)) * 1.1;
        
        outwardExplosion += seamShear;
    }

    // =========================================================
    // 6. 最终光度融合与黑白极简暗黑渲染 (确保背景深邃纯黑，图案晶莹剔透)
    // =========================================================
    float finalLight = 0.0;

    // 眼球内部构造
    float eyeballLight = (scleraFlow * 0.7) + (irisTexture * 1.6) + eventHorizon;
    eyeballLight *= (1.0 - hellPupil * 0.98);
    eyeballLight += coldSpecular;

    // 注入眼眶内部
    finalLight += isInsideEye * eyeballLight;

    // 融入虚化羽化的上下眼线粒子烟雾 (无粗硬实线)
    finalLight += softUpperLid * 1.2 + softLowerLid * 1.0;

    // 注入引力坍缩内卷粒子（进入态）
    finalLight += inwardCollapse * (1.0 - isInsideEye * 0.35);

    // 融入眨眼撕裂粉碎爆炸粒子（瞬态与结束态）
    finalLight += outwardExplosion;
    finalLight += shockwaveLuma;

    // 空间背景漫射字符 (全屏自然消散，绝无外框)
    vec2 ambientCoord = uv * vec2(40.0, 22.0) + vec2(0.0, t * 1.2);
    float ambientGlyph = glyph(fract(ambientCoord), hash(floor(ambientCoord)));
    float ambientDust = ambientGlyph * smoothstep(0.95, 0.35, length(uv)) * 0.15;
    finalLight += ambientDust * (1.0 - isInsideEye * 0.8);

    // 全屏自然景深衰减（边缘淡出）
    float vignette = smoothstep(0.98, 0.25, length(uv));
    finalLight *= vignette;

    // 严格限制在 [0.0, 1.0]，黑白单色发光
    float brightness = clamp(finalLight, 0.0, 1.0);
    vec3 col = vec3(brightness);

    // 确保 Alpha 能够穿透，或者作为独立屏幕时背景全黑、高光清透
    gl_FragColor = vec4(col, clamp(brightness * 1.8, 0.0, 1.0));
}`

const QUAD = new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1])

function compile(gl, type, source) {
  const shader = gl.createShader(type)
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader)
    gl.deleteShader(shader)
    throw new Error(`shader compile failed: ${log}`)
  }
  return shader
}

/**
 * @param {HTMLCanvasElement} canvas
 * @param {{ maxPixelRatio?: number }} [options]
 */
export function createCreatorEyeRenderer(canvas, { maxPixelRatio = 1.5 } = {}) {
  let gl = null
  let program = null
  let buffer = null
  let uniforms = null
  let rafId = 0
  let running = false
  let startedAt = 0
  let resizeObserver = null
  // u_mouse is in drawing-buffer pixels, same space as u_resolution.
  const mouse = { x: 0, y: 0, moved: false }

  const syncSize = () => {
    const ratio = Math.min(window.devicePixelRatio || 1, maxPixelRatio)
    const width = Math.max(1, Math.round((canvas.clientWidth || 1280) * ratio))
    const height = Math.max(1, Math.round((canvas.clientHeight || 720) * ratio))
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width
      canvas.height = height
    }
    if (!mouse.moved) {
      mouse.x = width / 2
      mouse.y = height / 2
    }
  }

  const onMouseMove = (event) => {
    const rect = canvas.getBoundingClientRect()
    if (!rect.width || !rect.height) return
    mouse.x = ((event.clientX - rect.left) / rect.width) * canvas.width
    mouse.y = (1 - (event.clientY - rect.top) / rect.height) * canvas.height
    mouse.moved = true
  }

  const release = () => {
    if (!gl) return
    if (program) gl.deleteProgram(program)
    if (buffer) gl.deleteBuffer(buffer)
    program = null
    buffer = null
    uniforms = null
  }

  const init = () => {
    gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl')
    if (!gl) return false

    try {
      // Some mobile GPUs have no highp in fragment shaders; fall back rather than fail.
      const high = gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT)
      const fragmentSource = high && high.precision > 0
        ? FRAGMENT_SHADER
        : FRAGMENT_SHADER.replace('precision highp float;', 'precision mediump float;')

      const vertex = compile(gl, gl.VERTEX_SHADER, VERTEX_SHADER)
      const fragment = compile(gl, gl.FRAGMENT_SHADER, fragmentSource)
      program = gl.createProgram()
      gl.attachShader(program, vertex)
      gl.attachShader(program, fragment)
      gl.linkProgram(program)
      gl.deleteShader(vertex)
      gl.deleteShader(fragment)
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error(`program link failed: ${gl.getProgramInfoLog(program)}`)
      }
    } catch (error) {
      console.warn('[CreatorEye] WebGL unavailable, falling back to the plain stage:', error.message)
      release()
      gl = null
      return false
    }

    gl.useProgram(program)
    buffer = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, QUAD, gl.STATIC_DRAW)
    const position = gl.getAttribLocation(program, 'a_position')
    gl.enableVertexAttribArray(position)
    gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
    uniforms = {
      time: gl.getUniformLocation(program, 'u_time'),
      resolution: gl.getUniformLocation(program, 'u_resolution'),
      mouse: gl.getUniformLocation(program, 'u_mouse')
    }
    return true
  }

  const frame = (now) => {
    if (!running || !gl) return
    if (!resizeObserver) syncSize()
    gl.viewport(0, 0, canvas.width, canvas.height)
    // Time restarts with every warp, so the eye is always open when it appears.
    gl.uniform1f(uniforms.time, (now - startedAt) * 0.001)
    gl.uniform2f(uniforms.resolution, canvas.width, canvas.height)
    gl.uniform2f(uniforms.mouse, mouse.x, mouse.y)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
    rafId = requestAnimationFrame(frame)
  }

  const onContextLost = (event) => {
    event.preventDefault()
    cancelAnimationFrame(rafId)
    rafId = 0
    program = null
    buffer = null
    uniforms = null
    gl = null
  }

  const onContextRestored = () => {
    if (init() && running) rafId = requestAnimationFrame(frame)
  }

  canvas.addEventListener('webglcontextlost', onContextLost)
  canvas.addEventListener('webglcontextrestored', onContextRestored)
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(syncSize)
    resizeObserver.observe(canvas)
  }

  return {
    /** Begin rendering. Returns false when WebGL is unavailable. */
    start() {
      if (running) return true
      if (!gl && !init()) return false
      mouse.moved = false
      syncSize()
      running = true
      startedAt = performance.now()
      window.addEventListener('mousemove', onMouseMove, { passive: true })
      rafId = requestAnimationFrame(frame)
      return true
    },

    stop() {
      if (!running) return
      running = false
      cancelAnimationFrame(rafId)
      rafId = 0
      window.removeEventListener('mousemove', onMouseMove)
      // Leave no stale frame behind for the next warp to flash.
      if (gl) {
        gl.clearColor(0, 0, 0, 0)
        gl.clear(gl.COLOR_BUFFER_BIT)
      }
    },

    destroy() {
      this.stop()
      canvas.removeEventListener('webglcontextlost', onContextLost)
      canvas.removeEventListener('webglcontextrestored', onContextRestored)
      resizeObserver?.disconnect()
      resizeObserver = null
      release()
      gl = null
    },

    get isRunning() {
      return running
    }
  }
}
