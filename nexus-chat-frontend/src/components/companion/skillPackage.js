export const MAX_SKILL_BYTES = 262144
export function skillBundle(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > MAX_SKILL_BYTES) throw new Error('Skill package exceeds 256 KiB')
  const raw = text.trim()
  const bundle = raw.startsWith('---') ? { files: { 'SKILL.md': text } } : JSON.parse(raw)
  if (!bundle || typeof bundle !== 'object' || Array.isArray(bundle) || !bundle.files || typeof bundle.files !== 'object'
      || typeof bundle.files['SKILL.md'] !== 'string') throw new Error('A package must contain SKILL.md')
  return bundle
}
export function isSkillArtifact(artifact) {
  if (!artifact || typeof artifact.content !== 'string') return false
  try { skillBundle(artifact.content); return true } catch { return false }
}
export function contextSkillNames(receipt) {
  return Array.isArray(receipt?.skills) ? receipt.skills.filter(s => s && s.status === 'loaded' && typeof s.name === 'string').map(s => `${s.name} · r${s.revision}`) : []
}
