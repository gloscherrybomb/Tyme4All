// Same formula as the phone app's Reserve object. Keep them in step.
function positive(n) { return typeof n === 'number' && Number.isFinite(n) && n > 0 }

export function percentHrr(hr, r) {
  const range = r.maxHr - r.restingHr
  if (!positive(hr) || !(range > 0)) return null
  return Math.max(0, ((hr - r.restingHr) / range) * 100)
}

export function percentBrr(br, r) {
  const range = r.maxBr - r.restingBr
  if (!positive(br) || !(range > 0)) return null
  return Math.max(0, ((br - r.restingBr) / range) * 100)
}

export function mobilizationIndex(br, hr, r) {
  const brr = percentBrr(br, r)
  const hrr = percentHrr(hr, r)
  if (brr === null || hrr === null) return null
  return hrr >= 1 ? (brr / hrr) * 100 : 0
}
