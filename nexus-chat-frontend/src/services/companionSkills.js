import api from './api'
const base = '/companion/skills'
const id = value => encodeURIComponent(String(value))
const config = ctx => ({ _settingsOwner: ctx.owner, _settingsSession: ctx.session })
const data = response => response.data
export const companionSkills = {
  list: ctx => api.get(base, config(ctx)).then(data),
  get: (skill, ctx) => api.get(`${base}/${id(skill)}`, config(ctx)).then(data),
  create: (bundle, ctx) => api.post(`${base}/import`, bundle, config(ctx)).then(data),
  fromArtifact: (sessionId, artifactId, ctx) => api.post(`${base}/from-artifact`, { sessionId, artifactId }, config(ctx)).then(data),
  revise: (skill, bundle, expectedHash, ctx) => api.post(`${base}/${id(skill)}/versions`, { ...bundle, expectedHash }, config(ctx)).then(data),
  publish: (skill, revision, expectedHash, ctx) => api.post(`${base}/${id(skill)}/versions/${revision}/publish`, { expectedHash }, config(ctx)).then(data),
  export: (skill, revision, ctx) => api.get(`${base}/${id(skill)}/versions/${revision}/export`, config(ctx)).then(data),
  trial: (skill, revision, body, ctx) => api.post(`${base}/${id(skill)}/versions/${revision}/trial`, body, config(ctx)).then(data),
  remove: (skill, ctx) => api.delete(`${base}/${id(skill)}`, config(ctx)).then(data),
  bindings: (role, ctx) => api.get(`${base}/roles/${id(role)}/bindings`, config(ctx)).then(data),
  bind: (role, body, ctx) => api.post(`${base}/roles/${id(role)}/bindings`, body, config(ctx)).then(data),
  unbind: (role, skill, expectedVersion, ctx) => api.delete(`${base}/roles/${id(role)}/bindings/${id(skill)}`, { ...config(ctx), params: { expectedVersion } }).then(data)
}
