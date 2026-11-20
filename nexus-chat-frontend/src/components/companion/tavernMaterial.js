export function styleTavernMaterial(material, light) {
  const compile = material.onBeforeCompile
  const cacheKey = material.customProgramCacheKey.bind(material)
  material.onBeforeCompile = function (shader, renderer) {
    compile.call(this, shader, renderer)
    shader.uniforms.tavernLight = light
    shader.fragmentShader = 'uniform float tavernLight;\n' + shader.fragmentShader
    shader.fragmentShader = shader.fragmentShader.replace(/}\s*$/, `
      gl_FragColor.rgb *= tavernLight;
    }`)
  }
  // MToon encodes outline and texture defines in its own cache key.
  material.customProgramCacheKey = () => `${cacheKey()}|tavern-color-v1`
  material.needsUpdate = true
}
