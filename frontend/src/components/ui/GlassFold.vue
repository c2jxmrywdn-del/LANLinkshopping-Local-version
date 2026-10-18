<template>
  <canvas ref="canvasRef" class="glass-fold" aria-hidden="true" />
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watchEffect } from 'vue'

/**
 * Glass Fold —— 折叠玻璃 WebGL 渐变背景（移植自 GradFlow）
 * 来源：https://gradflow.meerbahadin.dev （"Glass Fold" 着色器）
 *
 * 三色构成：pane 体色 / 背景色 / 受光边缘色；多层折叠面之间按 screen 混合，
 * 交叠处更亮而非更暗，故形成"液态玻璃折痕"的受光走向。
 *
 * 与原始实现的差异（均为工程加固，不改观感）：
 *  1) 保留 prefers-reduced-motion 下只画静态帧 + ResizeObserver 重绘的行为；
 *  2) 新增「标签页隐藏即停帧」——登录页为常驻背景，避免后台空转耗电；
 *  3) WebGL 上下文获取失败时静默跳过（由父容器 CSS 底色兜底，不报错、不白屏）。
 */
const props = defineProps({
  /** 玻璃体色 */
  color1: { type: String, default: '#1e3cc8' },
  /** 背景色 */
  color2: { type: String, default: '#06081e' },
  /** 受光边缘色 */
  color3: { type: String, default: '#96d7ff' },
  /** 动画速度，0 为冻结 */
  speed: { type: Number, default: 1 },
  /** 折痕密度，越大折痕越多越细 */
  scale: { type: Number, default: 1 },
  /** 胶片噪点，0 ~ 0.5 */
  noise: { type: Number, default: 0.1 },
  /**
   * 绘制帧率上限。着色器按真实时间累积动画进度（elapsed += dt × speed），
   * 因此限制"每秒绘制次数"不改变观感，只减少 GPU 开销：
   * 实测 240Hz 屏上每帧全屏片元着色 = 240 次/秒，对缓慢漂移的环境背景属多余。
   */
  maxFps: { type: Number, default: 60 }
})

const canvasRef = ref(null)

// 绘制循环从 live 读取最新参数：改色/改速度都不会重建 WebGL 程序
const live = { color1: props.color1, color2: props.color2, color3: props.color3, speed: props.speed, scale: props.scale, noise: props.noise, maxFps: props.maxFps }
watchEffect(() => {
  live.color1 = props.color1
  live.color2 = props.color2
  live.color3 = props.color3
  live.speed = props.speed
  live.scale = props.scale
  live.noise = props.noise
  live.maxFps = props.maxFps
})

const VERTEX = `
  attribute vec2 position;
  varying vec2 vUv;
  void main() {
    vUv = position * 0.5 + 0.5;
    gl_Position = vec4(position, 0.0, 1.0);
  }
`

const FRAGMENT = `
  precision highp float;

  uniform float u_time;
  uniform vec3 u_color1;
  uniform vec3 u_color2;
  uniform vec3 u_color3;
  uniform float u_scale;
  uniform float u_noise;
  uniform vec2 u_resolution;
  varying vec2 vUv;

  float grain(vec2 st) {
    return fract(sin(dot(st, vec2(12.9898, 78.233))) * 43758.5453);
  }

  // centered coordinates, y in [-0.5, 0.5], x stretched by the aspect ratio
  vec2 centered(vec2 uv) {
    vec2 p = uv - 0.5;
    p.x *= u_resolution.x / max(u_resolution.y, 1.0);
    return p;
  }

  mat2 rot(float a) {
    float s = sin(a);
    float c = cos(a);
    return mat2(c, -s, s, c);
  }

  // panes of glass folded over each other. Each pane has one creased edge
  // that catches the light and thins out to nothing behind it; panes screen
  // over one another, so overlaps get brighter instead of darker.
  vec3 shade(vec2 uv, float time) {
    vec2 q = rot(0.6) * centered(uv) * 2.0 * u_scale;
    float t = time * 0.6;
    float pixel = 2.0 * u_scale / max(u_resolution.y, 1.0);

    vec3 color = mix(u_color2, u_color2 * 0.45, clamp(length(centered(uv)) * 1.3, 0.0, 1.0));

    for (int i = 0; i < 6; i++) {
      float fi = float(i);
      float bend = mod(fi, 2.0) * 2.0 - 1.0;

      float crease = (fi - 2.5) * 0.4
                   + 0.26 * sin(q.x * (1.05 + 0.12 * fi) + fi * 1.9 + t * (0.25 + 0.05 * fi))
                   + 0.1 * bend * q.x * q.x;
      float s = q.y - crease;
      float pane = smoothstep(-pixel, pixel, s);
      float back = max(s, 0.0);

      // thick near the crease, clear further in
      float body = exp(-back * 1.7);
      float fresnel = exp(-back * 8.0);
      // the lit edge, and a fainter line just inside it for the glass's thickness
      float rim = exp(-back * 80.0);
      float inner = exp(-pow((back - 0.045) / 0.014, 2.0));
      float bloom = exp(-abs(s) * 20.0);

      vec3 tint = mix(u_color1, u_color3, clamp(fresnel * 0.6 + 0.08 + 0.14 * sin(fi * 2.1 + q.x * 0.8 + t * 0.2), 0.0, 1.0));
      vec3 light = tint * pane * (body * 0.42 + fresnel * 0.4)
                 + u_color3 * (pane * (rim * 0.6 + inner * 0.12) + bloom * 0.1);

      // screen blend
      color += light * (1.0 - color);
    }

    return clamp(color, 0.0, 1.0);
  }

  void main() {
    vec3 color = shade(vUv, u_time);
    if (u_noise > 0.001) {
      float g = grain(vUv * 200.0 + u_time * 0.1);
      color *= 1.0 - u_noise * 0.4 + u_noise * g * 0.4;
    }
    gl_FragColor = vec4(color, 1.0);
  }
`

function hexToVec3(hex) {
  const h = String(hex).replace('#', '')
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  const n = parseInt(full, 16) || 0
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255]
}

function compile(gl, type, source) {
  const shader = gl.createShader(type)
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  return shader
}

let frame = 0
let observer = null
let onVisibility = null

onMounted(() => {
  const canvas = canvasRef.value
  if (!canvas) return
  const gl = canvas.getContext('webgl', { antialias: false, alpha: false })
  if (!gl) return  // 无 WebGL：由父容器 CSS 底色兜底

  const program = gl.createProgram()
  gl.attachShader(program, compile(gl, gl.VERTEX_SHADER, VERTEX))
  gl.attachShader(program, compile(gl, gl.FRAGMENT_SHADER, FRAGMENT))
  gl.linkProgram(program)
  gl.useProgram(program)

  const buffer = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]), gl.STATIC_DRAW)
  const position = gl.getAttribLocation(program, 'position')
  gl.enableVertexAttribArray(position)
  gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)

  const uniform = (name) => gl.getUniformLocation(program, name)
  const u = {
    time: uniform('u_time'),
    color1: uniform('u_color1'),
    color2: uniform('u_color2'),
    color3: uniform('u_color3'),
    scale: uniform('u_scale'),
    noise: uniform('u_noise'),
    resolution: uniform('u_resolution')
  }

  // DPR 上限 2：全屏着色器按物理像素绘制，封顶可避免 4K/高 DPR 下像素量翻倍
  const resize = () => {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const width = Math.max(1, Math.round(canvas.clientWidth * dpr))
    const height = Math.max(1, Math.round(canvas.clientHeight * dpr))
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width
      canvas.height = height
    }
    gl.viewport(0, 0, width, height)
  }

  let elapsed = 0
  let last = null

  const draw = (now) => {
    const p = live
    // 按时间累积速度：运行中改速度不会让图案跳变
    if (last !== null) elapsed += Math.min((now - last) / 1000, 0.1) * p.speed
    last = now

    resize()
    gl.uniform1f(u.time, elapsed)
    gl.uniform3fv(u.color1, hexToVec3(p.color1))
    gl.uniform3fv(u.color2, hexToVec3(p.color2))
    gl.uniform3fv(u.color3, hexToVec3(p.color3))
    gl.uniform1f(u.scale, p.scale)
    gl.uniform1f(u.noise, p.noise)
    gl.uniform2f(u.resolution, canvas.width, canvas.height)
    gl.drawArrays(gl.TRIANGLES, 0, 6)
  }

  const still = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  let lastDraw = 0
  const loop = (now) => {
    // 帧率上限：rAF 照常回调度（开销极小），仅在到达间隔时真正绘制。
    // 动画进度按真实时间累积，故绘制次数减少不会让图案变慢或跳变。
    const interval = 1000 / Math.max(1, live.maxFps)
    if (now - lastDraw >= interval) {
      lastDraw = now
      draw(now)
    }
    if (!still) frame = requestAnimationFrame(loop)
  }
  frame = requestAnimationFrame(loop)

  // 静止模式没有循环，靠 resize 重绘那唯一一帧
  observer = new ResizeObserver(() => {
    if (still) draw(performance.now())
  })
  observer.observe(canvas)

  // 标签页隐藏即停帧：避免后台每帧重绘（回到前台恢复，且不跳变）
  onVisibility = () => {
    if (document.hidden) {
      cancelAnimationFrame(frame)
      frame = 0
      last = null
    } else if (!still && !frame) {
      frame = requestAnimationFrame(loop)
    }
  }
  document.addEventListener('visibilitychange', onVisibility)

  // 卸载清理：停帧 + 断开观察者 + 释放 GPU 资源
  onBeforeUnmount(() => {
    cancelAnimationFrame(frame)
    observer?.disconnect()
    if (onVisibility) document.removeEventListener('visibilitychange', onVisibility)
    gl.deleteBuffer(buffer)
    gl.deleteProgram(program)
  })
})

// 组件在 onMounted 前被卸载的兜底清理
onBeforeUnmount(() => {
  if (frame) cancelAnimationFrame(frame)
  observer?.disconnect()
  if (onVisibility) document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
.glass-fold {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
