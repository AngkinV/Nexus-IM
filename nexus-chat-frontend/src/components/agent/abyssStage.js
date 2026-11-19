import * as THREE from 'three'

// The AI canvas stage from the design export: an abyssal glyph-ring shader behind
// a wireframe bust. Two layers, one clock. Nothing is drawn while the stage is
// hidden (short windows) or the tab is in the background.

const VERTEX = `attribute vec2 a_position;
void main() {
  gl_Position = vec4(a_position, 0.0, 1.0);
}`

const FRAGMENT = `precision highp float;
uniform float u_time;
uniform vec2 u_resolution;
uniform vec2 u_mouse;
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
  mat2 rot = mat2(cos(0.5), sin(0.5), -sin(0.5), cos(0.5));
  for (int i = 0; i < 4; i++) {
    v += a * noise(p);
    p = rot * p * 2.0 + vec2(10.0);
    a *= 0.5;
  }
  return v;
}
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
  }
  if (grid.y == 2.0 || grid.x == 1.0) return 1.0;
  return 0.0;
}
void main() {
  vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / min(u_resolution.x, u_resolution.y);
  float t = u_time * 0.8;
  vec2 m = (u_mouse - 0.5 * u_resolution.xy) / min(u_resolution.x, u_resolution.y);
  vec2 p = uv - m * 0.04;
  float r = length(p);
  float a = atan(p.y, p.x);
  float vortex = a + t * 0.4 + sin(r * 8.0 - t);
  float flameNoise = fbm(vec2(p.x * 3.5 + sin(t * 0.5), p.y * 3.5 - t * 0.6));
  float fluidPulse = smoothstep(0.4, 0.75, flameNoise);
  float ring1 = smoothstep(0.015, 0.0, abs(r - 0.42 - 0.02 * sin(t * 1.5)));
  float ring1Code = glyph(vec2(fract(vortex * 28.0 / 6.2831), fract(r * 40.0)), floor(vortex * 28.0));
  float abyssalRing = ring1 * (0.4 + 0.6 * ring1Code);
  float ring2 = smoothstep(0.008, 0.0, abs(r - 0.65));
  float ring2Code = glyph(vec2(fract(a * 45.0 / 6.2831 - t * 0.1), fract(r * 50.0)), floor(a * 45.0));
  float outerOrbit = ring2 * ring2Code * 0.45;
  vec2 emberCoord = uv * vec2(32.0, 18.0) + vec2(0.0, t * 1.5);
  float emberCell = hash(floor(emberCoord));
  float ember = 0.0;
  if (emberCell > 0.68) {
    ember = glyph(fract(emberCoord), emberCell * 90.0) * smoothstep(0.8, 0.1, r) * 0.65;
  }
  float coreLight = smoothstep(0.65, 0.0, r) * fluidPulse * 0.35;
  float lum = coreLight + abyssalRing * 0.9 + outerOrbit * 0.7 + ember;
  float chX = smoothstep(0.0015, 0.0, abs(p.x)) * step(0.15, abs(p.y)) * smoothstep(0.55, 0.25, abs(p.y));
  float chY = smoothstep(0.0015, 0.0, abs(p.y)) * step(0.15, abs(p.x)) * smoothstep(0.55, 0.25, abs(p.x));
  lum += (chX + chY) * 0.25;
  float vig = smoothstep(0.85, 0.2, length(uv));
  lum = clamp(lum * vig, 0.0, 1.0);
  gl_FragColor = vec4(vec3(lum), lum * vig * 1.6);
}`

/**
 * Mount the stage into `container` and return a teardown function.
 * @param {HTMLElement} container positioned element that sizes the stage
 */
export function mountAbyssStage(container, { shouldRender = () => true } = {}) {
  // ── Layer 1: glyph-ring shader ──────────────────────────────────────────────
  const shaderCanvas = document.createElement('canvas')
  shaderCanvas.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;opacity:0.5;pointer-events:none'
  container.appendChild(shaderCanvas)
  const gl = shaderCanvas.getContext('webgl')
  const compile = (type, source) => {
    const shader = gl.createShader(type)
    gl.shaderSource(shader, source)
    gl.compileShader(shader)
    return shader
  }
  const program = gl.createProgram()
  gl.attachShader(program, compile(gl.VERTEX_SHADER, VERTEX))
  gl.attachShader(program, compile(gl.FRAGMENT_SHADER, FRAGMENT))
  gl.linkProgram(program)
  gl.useProgram(program)
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer())
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
  const position = gl.getAttribLocation(program, 'a_position')
  gl.enableVertexAttribArray(position)
  gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
  const uTime = gl.getUniformLocation(program, 'u_time')
  const uResolution = gl.getUniformLocation(program, 'u_resolution')
  const uMouse = gl.getUniformLocation(program, 'u_mouse')

  // ── Layer 2: wireframe bust ─────────────────────────────────────────────────
  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(45, 2, 0.1, 100)
  camera.position.set(0, 0, 5.2)
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
  renderer.domElement.style.cssText = 'position:absolute;inset:0;width:100%;height:100%'
  container.appendChild(renderer.domElement)

  const figure = new THREE.Group()
  scene.add(figure)
  const lineMaterial = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.85 })
  const dimLineMaterial = new THREE.LineBasicMaterial({ color: 0x8e9192, transparent: true, opacity: 0.45 })
  const wire = (geometry, material) => new THREE.LineSegments(new THREE.WireframeGeometry(geometry), material)

  const headGeometry = new THREE.IcosahedronGeometry(0.72, 2)
  const head = wire(headGeometry, lineMaterial)
  head.position.set(0, 0.55, 0)
  const headPoints = new THREE.Points(
    headGeometry,
    new THREE.PointsMaterial({ color: 0xffffff, size: 0.04, transparent: true, opacity: 0.85 })
  )
  headPoints.position.set(0, 0.55, 0)
  headPoints.scale.setScalar(0.96)
  const halo = wire(new THREE.TorusGeometry(1.12, 0.012, 16, 64), lineMaterial)
  halo.position.set(0, 0.55, -0.22)
  halo.rotation.x = Math.PI * 0.12
  const outerHalo = wire(new THREE.TorusGeometry(1.35, 0.006, 8, 36), dimLineMaterial)
  outerHalo.position.set(0, 0.55, -0.25)
  outerHalo.rotation.x = Math.PI * 0.12
  const neck = wire(new THREE.CylinderGeometry(0.24, 0.32, 0.45, 12, 2, true), dimLineMaterial)
  neck.position.set(0, -0.05, 0)
  const cloakGeometry = new THREE.ConeGeometry(1.48, 1.15, 16, 4, true)
  cloakGeometry.scale(1.22, 0.92, 0.72)
  const cloak = wire(cloakGeometry, lineMaterial)
  cloak.position.set(0, -0.78, 0)
  cloak.rotation.y = Math.PI
  figure.add(head, headPoints, halo, outerHalo, neck, cloak)

  const DUST = 180
  const dustPositions = new Float32Array(DUST * 3)
  const dustSpeeds = new Float32Array(DUST)
  for (let i = 0; i < DUST; i++) {
    dustPositions[i * 3] = (Math.random() - 0.5) * 5.0
    dustPositions[i * 3 + 1] = (Math.random() - 0.5) * 4.0
    dustPositions[i * 3 + 2] = (Math.random() - 0.5) * 3.0
    dustSpeeds[i] = 0.005 + Math.random() * 0.012
  }
  const dustGeometry = new THREE.BufferGeometry()
  dustGeometry.setAttribute('position', new THREE.BufferAttribute(dustPositions, 3))
  const dust = new THREE.Points(
    dustGeometry,
    new THREE.PointsMaterial({ color: 0xffffff, size: 0.026, transparent: true, opacity: 0.65 })
  )
  scene.add(dust)

  // ── Shared sizing, pointer and clock ────────────────────────────────────────
  const resize = () => {
    const width = container.clientWidth
    const height = container.clientHeight
    if (!width || !height) return
    shaderCanvas.width = width
    shaderCanvas.height = height
    gl.viewport(0, 0, width, height)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    renderer.setSize(width, height, false)
  }
  const resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(container)
  resize()

  let targetX = 0
  let targetY = 0
  let gazeX = 0
  let gazeY = 0
  const shaderMouse = { x: 0, y: 0 }
  const onMouseMove = (event) => {
    const rect = container.getBoundingClientRect()
    // Hidden on short windows: a zero-size rect would turn the gaze into Infinity.
    if (!rect.width) return
    const nx = (event.clientX - rect.left) / rect.width
    const ny = (event.clientY - rect.top) / rect.height
    targetX = (nx * 2 - 1) * 0.5
    targetY = -(ny * 2 - 1) * 0.4
    shaderMouse.x = nx * shaderCanvas.width
    shaderMouse.y = (1 - ny) * shaderCanvas.height
  }
  window.addEventListener('mousemove', onMouseMove)

  const clock = new THREE.Clock()
  let frame = 0
  const animate = () => {
    frame = requestAnimationFrame(animate)
    if (!shouldRender() || !container.clientWidth || !container.clientHeight || document.hidden) return
    const time = clock.getElapsedTime()

    gl.uniform1f(uTime, time)
    gl.uniform2f(uResolution, shaderCanvas.width, shaderCanvas.height)
    gl.uniform2f(uMouse, shaderMouse.x, shaderMouse.y)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)

    gazeX += (targetX - gazeX) * 0.06
    gazeY += (targetY - gazeY) * 0.06
    figure.position.y = Math.sin(time * 1.6) * 0.065 - 0.1
    figure.rotation.y = gazeX * 0.85 + Math.sin(time * 0.7) * 0.05
    figure.rotation.x = -gazeY * 0.55 + Math.cos(time * 0.9) * 0.035
    halo.rotation.z = time * 0.2
    outerHalo.rotation.z = -time * 0.12
    for (let i = 0; i < DUST; i++) {
      dustPositions[i * 3 + 1] += dustSpeeds[i]
      if (dustPositions[i * 3 + 1] > 2.2) dustPositions[i * 3 + 1] = -2.2
    }
    dustGeometry.attributes.position.needsUpdate = true
    dust.rotation.y = time * 0.03
    renderer.render(scene, camera)
  }
  animate()

  return () => {
    cancelAnimationFrame(frame)
    window.removeEventListener('mousemove', onMouseMove)
    resizeObserver.disconnect()
    scene.traverse((node) => {
      node.geometry?.dispose()
      node.material?.dispose()
    })
    renderer.dispose()
    renderer.domElement.remove()
    gl.getExtension('WEBGL_lose_context')?.loseContext()
    shaderCanvas.remove()
  }
}

export function mountMiniHologram(container, { shouldRender = () => true } = {}) {
  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 20)
  camera.position.z = 2.8

  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'low-power' })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.setClearColor(0x000000, 0)
  renderer.domElement.style.cssText = 'display:block;width:100%;height:100%'
  container.appendChild(renderer.domElement)

  const group = new THREE.Group()
  const head = new THREE.LineSegments(
    new THREE.WireframeGeometry(new THREE.IcosahedronGeometry(0.82, 1)),
    new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.9 })
  )
  const ring = new THREE.LineSegments(
    new THREE.WireframeGeometry(new THREE.TorusGeometry(1.08, 0.015, 6, 32)),
    new THREE.LineBasicMaterial({ color: 0xc4c7c8, transparent: true, opacity: 0.42 })
  )
  ring.rotation.x = Math.PI * 0.36
  group.add(head, ring)
  scene.add(group)

  const resize = () => {
    const width = container.clientWidth
    const height = container.clientHeight
    if (!width || !height) return
    renderer.setSize(width, height, false)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
  }
  const resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(container)
  resize()

  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const clock = new THREE.Clock()
  let frame = 0
  const animate = () => {
    frame = requestAnimationFrame(animate)
    if (!shouldRender() || !container.clientWidth || !container.clientHeight || document.hidden) return
    const time = reducedMotion ? 0 : clock.getElapsedTime()
    group.rotation.y = time * 0.55
    group.rotation.x = 0.2 + Math.sin(time * 0.7) * 0.08
    ring.rotation.z = time * 0.35
    renderer.render(scene, camera)
  }
  animate()

  return () => {
    cancelAnimationFrame(frame)
    resizeObserver.disconnect()
    scene.traverse((node) => {
      node.geometry?.dispose()
      node.material?.dispose()
    })
    renderer.dispose()
    renderer.domElement.remove()
  }
}
