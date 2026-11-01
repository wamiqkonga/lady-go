import { render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { map, layer, tile, marker } = vi.hoisted(() => {
  const map = { setView: vi.fn(), fitBounds: vi.fn(), panTo: vi.fn(), remove: vi.fn() }
  map.setView.mockReturnValue(map)
  const layer = { addTo: vi.fn(), clearLayers: vi.fn() }
  layer.addTo.mockReturnValue(layer)
  const tile = { addTo: vi.fn() }
  const markerObj = { on: vi.fn(), addTo: vi.fn() }
  markerObj.on.mockReturnValue(markerObj)
  return { map, layer, tile, marker: markerObj }
})
vi.mock('leaflet', () => ({
  default: {
    map: vi.fn(() => map),
    tileLayer: vi.fn(() => tile),
    layerGroup: vi.fn(() => layer),
    marker: vi.fn(() => marker),
    divIcon: vi.fn((o: unknown) => o),
    latLngBounds: vi.fn((p: unknown) => p),
  },
}))
vi.mock('leaflet/dist/leaflet.css', () => ({}))

import L from 'leaflet'
import LiveMapCanvas from './LiveMapCanvas'

beforeEach(() => vi.clearAllMocks())

describe('LiveMapCanvas', () => {
  it('uses the configured tile URL with the OpenStreetMap attribution by default', () => {
    render(<LiveMapCanvas markers={[]} selectedId={null} onSelect={() => {}} />)
    expect(L.tileLayer).toHaveBeenCalledWith(
      'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      expect.objectContaining({ attribution: expect.stringContaining('OpenStreetMap') }),
    )
  })

  it('draws a marker per item, fits the view once and removes the map on unmount', () => {
    const markers = [
      { id: 'driver:1', kind: 'driver' as const, lat: 34.08, lon: 74.8, label: 'A' },
      { id: 'incident:1', kind: 'incident' as const, lat: 34.1, lon: 74.9, label: 'B' },
    ]
    const { rerender, unmount } = render(<LiveMapCanvas markers={markers} selectedId={null} onSelect={() => {}} />)
    expect(L.marker).toHaveBeenCalledTimes(2)
    expect(map.fitBounds).toHaveBeenCalledTimes(1)
    rerender(<LiveMapCanvas markers={[...markers]} selectedId={null} onSelect={() => {}} />)
    expect(layer.clearLayers).toHaveBeenCalledTimes(2)
    expect(map.fitBounds).toHaveBeenCalledTimes(1) // not re-fitted on later polls
    unmount()
    expect(map.remove).toHaveBeenCalled()
  })

  it('reports a marker click and pans to the selection', () => {
    const onSelect = vi.fn()
    const markers = [{ id: 'driver:1', kind: 'driver' as const, lat: 34.08, lon: 74.8, label: 'A' }]
    const { rerender } = render(<LiveMapCanvas markers={markers} selectedId={null} onSelect={onSelect} />)
    const handler = marker.on.mock.calls.find((c) => c[0] === 'click')?.[1] as () => void
    handler()
    expect(onSelect).toHaveBeenCalledWith('driver:1')
    rerender(<LiveMapCanvas markers={markers} selectedId="driver:1" onSelect={onSelect} />)
    expect(map.panTo).toHaveBeenCalledWith([34.08, 74.8])
  })
})
