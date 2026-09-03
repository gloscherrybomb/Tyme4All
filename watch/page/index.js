// If DataWidget(BasePage(...)) fails at runtime because ZML expects Page, fall back to the
// MessageBuilder pattern documented at the bottom of this file's task brief (Step 1 note).

import { BasePage } from '@zeppos/zml/base-page'
import { createWidget, widget, prop, align } from '@zos/ui'
import { HeartRate } from '@zos/sensor'
import { buildViewModel } from '../shared/view-model.js'
import { COLOR_TEXT, COLOR_MUTED, SCREEN } from '../shared/constants.js'

function text(x, y, w, h, size, color, alignH, value) {
  return createWidget(widget.TEXT, { x, y, w, h, text_size: size, color, align_h: alignH, align_v: align.CENTER_V, text: value })
}

DataWidget(
  BasePage({
    state: {
      payload: null,
      receivedAtMs: null,
      hr: null,
      widgets: null,
      timer: null,
      heartRate: null,
      onHr: null,
    },

    onInit() {
      this.request({ method: 'session.start' })
        .then((r) => console.log('session.start ->', JSON.stringify(r)))
        .catch((e) => console.log('session.start failed', e))
    },

    build() {
      const vm = buildViewModel(this.state, Date.now())
      const w = {}
      w.bg = createWidget(widget.FILL_RECT, { x: 0, y: 0, w: SCREEN.w, h: SCREEN.h, color: vm.bg })
      w.status = text(90, 34, 300, 36, 28, vm.statusColor, align.CENTER_H, vm.status)
      w.battery = text(300, 34, 120, 36, 24, COLOR_MUTED, align.RIGHT, vm.battery)
      w.ve = text(40, 120, 400, 150, 140, COLOR_TEXT, align.CENTER_H, vm.ve)
      w.unit = text(40, 262, 400, 30, 26, COLOR_MUTED, align.CENTER_H, 'L/min')
      w.zone = text(40, 292, 400, 40, 34, COLOR_TEXT, align.CENTER_H, vm.zoneName)
      w.br = text(40, 350, 130, 60, 56, COLOR_TEXT, align.CENTER_H, vm.br)
      w.tv = text(175, 350, 130, 60, 56, COLOR_TEXT, align.CENTER_H, vm.tv)
      w.mi = text(310, 350, 130, 60, 56, COLOR_TEXT, align.CENTER_H, vm.mi)
      text(40, 410, 130, 30, 22, COLOR_MUTED, align.CENTER_H, 'BR')
      text(175, 410, 130, 30, 22, COLOR_MUTED, align.CENTER_H, 'TV')
      text(310, 410, 130, 30, 22, COLOR_MUTED, align.CENTER_H, 'MI %')
      this.state.widgets = w
    },

    render() {
      const w = this.state.widgets
      if (!w) return
      const vm = buildViewModel(this.state, Date.now())
      w.bg.setProperty(prop.MORE, { color: vm.bg })
      w.status.setProperty(prop.MORE, { text: vm.status, color: vm.statusColor })
      w.battery.setProperty(prop.TEXT, vm.battery)
      w.ve.setProperty(prop.TEXT, vm.ve)
      w.zone.setProperty(prop.TEXT, vm.zoneName)
      w.br.setProperty(prop.TEXT, vm.br)
      w.tv.setProperty(prop.TEXT, vm.tv)
      w.mi.setProperty(prop.TEXT, vm.mi)
    },

    startHr() {
      if (this.state.heartRate) return
      const hr = new HeartRate()
      const onHr = () => { const v = hr.getCurrent(); this.state.hr = typeof v === 'number' && v > 0 ? v : null }
      hr.onCurrentChange(onHr)
      this.state.heartRate = hr
      this.state.onHr = onHr
    },

    stopHr() {
      if (!this.state.heartRate) return
      this.state.heartRate.offCurrentChange(this.state.onHr)
      this.state.heartRate = null
      this.state.onHr = null
    },

    onResume() {
      this.startHr()
      this.call({ method: 'live.poll.start' })
      if (this.state.timer === null) this.state.timer = setInterval(() => this.render(), 1000)
      this.render()
    },

    onPause() {
      this.call({ method: 'live.poll.stop' })
      this.stopHr()
      if (this.state.timer !== null) { clearInterval(this.state.timer); this.state.timer = null }
    },

    onCall(data) {
      if (!data || data.method !== 'live') return
      if (data.params && data.params.ok) {
        this.state.payload = data.params.payload
        this.state.receivedAtMs = Date.now()
      }
      this.render()
    },

    onDestroy() {
      this.onPause()
      this.request({ method: 'session.stop' })
        .then((r) => console.log('session.stop ->', JSON.stringify(r)))
        .catch((e) => console.log('session.stop failed', e))
    },
  }),
)
